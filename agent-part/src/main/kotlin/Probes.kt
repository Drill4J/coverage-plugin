/**
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.plugins.test2code

import com.epam.drill.jacoco.*
import com.epam.drill.plugin.api.processing.*
import com.epam.drill.plugins.test2code.common.api.*
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.coroutines.*

/**
 * Provides boolean array for the probe.
 * Implementations must be kotlin singleton objects.
 */
typealias ProbeArrayProvider = (Long, Int, String, Int) -> AgentProbes

typealias RealtimeHandler = (Sequence<ExecDatum>) -> Unit

interface SessionProbeArrayProvider : ProbeArrayProvider {

    fun start(
        sessionId: String,
        isGlobal: Boolean,
        testName: String? = null,
        realtimeHandler: RealtimeHandler = {},
    )

    fun stop(sessionId: String): Sequence<ExecDatum>?
    fun stopAll(): List<Pair<String, Sequence<ExecDatum>>>
    fun cancel(sessionId: String)
    fun cancelAll(): List<String>
    fun addCompletedTests(sessionId: String, tests: List<String>)

}

const val DRIlL_TEST_NAME = "drill-test-name"

class ExecDatum(
    val id: Long,
    val name: String,
    val probes: AgentProbes,
    val testName: String = "",
)

class ProbeDescriptor(
    val id: Long,
    val name: String,
    val probeCount: Int,
)

fun ExecDatum.toExecClassData() = ExecClassData(
    id = id,
    className = name,
    probes = probes.values.toBitSet(),
    testName = testName
)

typealias ExecData = Array<ExecDatum?>

const val MAX_CLASS_COUNT = 50_000

internal object ProbeWorker : CoroutineScope {
    override val coroutineContext: CoroutineContext = run {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher() + SupervisorJob()
    }
}

abstract class Runtime(
    realtimeHandler: RealtimeHandler,
) {
    private val job = ProbeWorker.launch {
        while (true) {
            delay(2000L)
            realtimeHandler(collect())
        }
    }

    abstract fun collect(): Sequence<ExecDatum>

    fun close() {
        job.cancel()
    }
}

/**
 * A container for session runtime data and optionally runtime data of tests
 * TODO ad hoc implementation, rewrite to something more descent
 */
class ExecRuntime(
    realtimeHandler: RealtimeHandler,
) : Runtime(realtimeHandler) {
    private val _execData = ConcurrentHashMap<String, ExecData>()

    private val _completedTests = atomic(persistentListOf<String>())
    private val isPerformanceMode = System.getProperty("drill.probes.perf.mode")?.toBoolean() ?: false

    override fun collect(): Sequence<ExecDatum> = _execData.values.flatMap { data ->
        data.filterNotNull().filter { datum -> datum.probes.values.any { it } }
    }.asSequence().also {
        val passedTest = _completedTests.getAndUpdate { it.clear() }
        if (isPerformanceMode) {
            _execData.clear()
        } else {
            passedTest.forEach { _execData.remove(it) }
        }
    }

    fun getOrPut(
        testName: String,
        updater: () -> ExecData,
    ): Array<ExecDatum?> = _execData.getOrPut(testName) { updater() }

    fun putIndex(
        indx: Int,
        updater: (String) -> ExecDatum,
    ) = _execData.forEach { (testName, v) ->
        v[indx] = updater(testName)
    }

    fun addCompletedTests(tests: List<String>) = _completedTests.update { it + tests }
}

class GlobalExecRuntime(
    val testName: String?,
    realtimeHandler: RealtimeHandler,
) : Runtime(realtimeHandler) {
    val execDatum = arrayOfNulls<ExecDatum?>(MAX_CLASS_COUNT)

    override fun collect(): Sequence<ExecDatum> = execDatum.copyOf().asSequence().filterNotNull().filter { datum ->
        datum.probes.values.any { it }
    }

    fun checkGlobalProbes(num: Int) = execDatum[num]?.probes
}


class ProbeMetaContainer {
    private val probesDescriptor = arrayOfNulls<ProbeDescriptor?>(MAX_CLASS_COUNT)


    fun addDescriptor(
        inx: Int,
        probeDescriptor: ProbeDescriptor,
        globalRuntime: GlobalExecRuntime?,
        runtimes: Collection<ExecRuntime>,
    ) {
        probesDescriptor[inx] = probeDescriptor

        globalRuntime?.run {
            execDatum[inx] = probeDescriptor.toExecDatum(testName)
        }

        runtimes.forEach {
            it.putIndex(inx) { testName ->
                probeDescriptor.toExecDatum(testName)
            }
        }
    }

    fun forEachIndexed(
        action: (Int, ProbeDescriptor?) -> Unit,
    ) {
        probesDescriptor.forEachIndexed { index, probeDescriptor ->
            action(index, probeDescriptor)
        }
    }

    private fun ProbeDescriptor.toExecDatum(testName: String?) = ExecDatum(
        id = id,
        name = name,
        probes = AgentProbes(probeCount),
        testName = testName ?: "undefined"
    )
}


/**
 * Simple probe array provider that employs a lock-free map for runtime data storage.
 * This class is intended to be an ancestor for a concrete probe array provider object.
 * The provider must be a Kotlin singleton object, otherwise the instrumented probe calls will fail.
 */
open class SimpleSessionProbeArrayProvider(
    defaultContext: AgentContext? = null,
) : SessionProbeArrayProvider {

    var requestThreadLocal = ThreadLocal<Array<ExecDatum?>>() //todo TTL

    val probeMetaContainer = ProbeMetaContainer()

    val runtimes = mutableMapOf<String, ExecRuntime>()

    var global: Pair<String, GlobalExecRuntime>? = null

    var defaultContext: AgentContext?
        get() = _defaultContext.value
        set(value) {
            _defaultContext.value = value
        }

    private val _defaultContext = atomic(defaultContext)

    private var _context: AgentContext? = null

    private var _globalContext: AgentContext? = null

    private val stubProbes = StubAgentProbes()

    override fun invoke(
        id: Long,
        num: Int,
        name: String,
        probeCount: Int,
    ): AgentProbes = global?.second?.checkGlobalProbes(num)
        ?: checkLocalProbes(num)
        ?: stubProbes

    private fun checkLocalProbes(num: Int) = requestThreadLocal.get()?.get(num)?.probes

    override fun start(
        sessionId: String,
        isGlobal: Boolean,
        testName: String?,
        realtimeHandler: RealtimeHandler,
    ) {
        if (isGlobal) {
            _globalContext = GlobalContext(sessionId, testName)
            addGlobal(sessionId, testName, realtimeHandler)
        } else {
            _context = _context ?: defaultContext
            add(sessionId, realtimeHandler)
        }
    }

    override fun stop(sessionId: String): Sequence<ExecDatum>? {
        return if (sessionId !in runtimes) {
            removeGlobal()?.collect()
        } else {
            remove(sessionId)?.collect()
        }
    }

    override fun stopAll(): List<Pair<String, Sequence<ExecDatum>>> = (global?.let {
        runtimes + it
    } ?: runtimes.toMap()).apply {
        _context = null
        _globalContext = null
        runtimes.clear()
        global = null
    }.map { (id, runtime) ->
        runtime.close()
        id to runtime.collect()
    }

    override fun cancel(sessionId: String) {
        if (sessionId !in runtimes) {
            removeGlobal()
        } else {
            remove(sessionId)
        }
    }

    override fun cancelAll(): List<String> = (global?.let { runtimes + it } ?: runtimes.toMap()).apply {
        _context = null
        _globalContext = null
        runtimes.clear()
        global = null
    }.map { (id, runtime) ->
        runtime.close()
        id
    }

    override fun addCompletedTests(sessionId: String, tests: List<String>) {
        runtimes[sessionId]?.addCompletedTests(tests)
    }

    private fun add(sessionId: String, realtimeHandler: RealtimeHandler) {
        if (sessionId !in runtimes) {
            val value = ExecRuntime(realtimeHandler)
            runtimes[sessionId] = value
        } else runtimes

    }

    private fun addGlobal(sessionId: String, testName: String?, realtimeHandler: RealtimeHandler) {
        val runtime = GlobalExecRuntime(testName, realtimeHandler).apply {
            execDatum.fillFromMeta(testName)
        }
        global = sessionId to runtime
    }

    private fun removeGlobal(): GlobalExecRuntime? = global?.copy()?.second?.apply {
        _globalContext = null
        global = null
        close()
    }

    private fun remove(sessionId: String): ExecRuntime? = runtimes.remove(sessionId).also {
        if (runtimes.none()) {
            _context = null
        }
    }?.also(ExecRuntime::close)

    fun ExecData.fillFromMeta(testName: String?) {
        probeMetaContainer.forEachIndexed { inx, probeDescriptor ->
            if (probeDescriptor != null)
                this[inx] = ExecDatum(
                    id = probeDescriptor.id,
                    name = probeDescriptor.name,
                    probes = AgentProbes(probeDescriptor.probeCount),
                    testName = testName ?: "undefined"
                )
        }
    }
}

private class GlobalContext(
    private val sessionId: String,
    private val testName: String?,
) : AgentContext {
    override fun get(key: String): String? = testName?.takeIf { key == DRIlL_TEST_NAME }

    override fun invoke(): String? = sessionId
}
