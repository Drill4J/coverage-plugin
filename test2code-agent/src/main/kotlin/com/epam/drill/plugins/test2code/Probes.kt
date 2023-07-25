/**
 * Copyright 2020 - 2022 EPAM Systems
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

import com.epam.drill.jacoco.AgentProbes
import com.epam.drill.jacoco.StubAgentProbes
import com.epam.drill.logger.api.Logger
import com.epam.drill.plugin.api.processing.AgentContext
import com.epam.drill.plugins.test2code.common.api.DEFAULT_TEST_NAME
import com.epam.drill.plugins.test2code.common.api.ExecClassData
import com.epam.drill.plugins.test2code.common.api.toBitSet
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

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
    fun getActiveSessions(): Set<String>

}

const val DRIlL_TEST_NAME_HEADER = "drill-test-name"
const val DRILL_TEST_ID_HEADER = "drill-test-id"

data class ExecDatum(
    val id: Long,
    val name: String,
    val probes: AgentProbes,
    val testName: String = "",
    val testId: String = "",
)

class ProbeDescriptor(
    val id: Long,
    val name: String,
    val probeCount: Int,
)

typealias TestKey = Pair<String, String>

internal fun ExecDatum.toExecClassData() = ExecClassData(
    id = id,
    className = name,
    probes = probes.values.toBitSet(),
    testName = testName,
    testId = testId,
)

internal fun ProbeDescriptor.toExecDatum(testName: String, testId: String) = ExecDatum(
    id = id,
    name = name,
    probes = AgentProbes(probeCount),
    testName = testName,
    testId = testId
)
// key - classId ; value - ExecDatum
typealias ExecData = ConcurrentHashMap<Long, ExecDatum>

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

    // TODO add timer to close runtime at N seconds after last put
    abstract fun put(index: Long, updater: (TestKey) -> ExecDatum)

    fun close() {
        job.cancel()
    }
}

/**
 * A container for session runtime data and optionally runtime data of tests
 * TODO ad hoc implementation, rewrite to something more descent
 */
class ExecRuntime(
    private val logger: Logger? = null,
    realtimeHandler: RealtimeHandler,
) : Runtime(realtimeHandler) {
    private val _execData = ConcurrentHashMap<TestKey, ExecData>()

    private val isPerformanceMode = System.getProperty("drill.probes.perf.mode")?.toBoolean() ?: false

    init {
        logger?.debug { "drill.probes.perf.mode=$isPerformanceMode" }
    }

    override fun collect() = _execData.values.flatMap { data ->
        data.values.filter { datum -> datum.probes.values.any { it } }
    }.asSequence()
//         TODO add clean up
//        .also {
//        val passedTest = _completedTests.getAndUpdate { it.clear() }
//        if (isPerformanceMode) {
//            _execData.clear()
//        } else {
//            passedTest.forEach { _execData.remove(TestKey(DEFAULT_TEST_NAME, it)) }
//        }
//    }

    fun getOrPut(
        testKey: TestKey,
        updater: () -> ExecData,
    ): ConcurrentHashMap<Long, ExecDatum>? {
        if (_execData[testKey] == null) {
            _execData[testKey] = updater()
        }
        return _execData[testKey]
    }

    override fun put(
        index: Long,
        updater: (TestKey) -> ExecDatum,
    ) = _execData.forEach { (testName, execDataset) ->
        execDataset[index] = updater(testName)
    }
}

class GlobalExecRuntime(
    private val testName: String,
    private val logger: Logger? = null,
    realtimeHandler: RealtimeHandler,
) : Runtime(realtimeHandler) {
   internal val execData = ExecData()

    /**
     * Get probes from the completed tests
     * @features Coverage data sending
     */
    override fun collect(): Sequence<ExecDatum> = execData.values.filter { datum ->
        datum.probes.values.any { it }
    }.map { datum ->
        val probesToSend = datum.probes.values.copyOf()
        probesToSend.forEachIndexed { index, value ->
            if (value)
                datum.probes.values[index] = false
        }
        datum.copy(
            probes = AgentProbes(
                values = probesToSend
            )
        )
    }.asSequence()

    override fun put(index: Long, updater: (TestKey) -> ExecDatum) {
        execData[index] = updater(TestKey(testName, testName.id()))
    }

    fun get(num: Long): AgentProbes {
        return execData.values.toList()[num.toInt()].probes
    }
}

class ProbeMetaContainer {
    //key: class-id ; value: ProbeDescriptor
    private val probesDescriptor = ConcurrentHashMap<Long, ProbeDescriptor?>()

    fun addDescriptor(
        // number of class at instrumentation
        index: Int,
        probeDescriptor: ProbeDescriptor,
        globalRuntime: GlobalExecRuntime?,
        runtimes: Collection<ExecRuntime>,
    ) {
        probesDescriptor[probeDescriptor.id] = probeDescriptor

        globalRuntime?.put(probeDescriptor.id) { (testName, testId) ->
            probeDescriptor.toExecDatum(testName, testId)
        }

        runtimes.forEach {
            it.put(probeDescriptor.id) { (testName, testId) ->
                probeDescriptor.toExecDatum(testName, testId)
            }
        }
    }

    fun forEachIndexed(
        action: (ProbeDescriptor?) -> Unit,
    ) {
        probesDescriptor.values.forEach { p ->
            action(p)
        }
    }
}


/**
 * Simple probe array provider that employs a lock-free map for runtime data storage.
 * This class is intended to be an ancestor for a concrete probe array provider object.
 * The provider must be a Kotlin singleton object, otherwise the instrumented probe calls will fail.
 */
open class SimpleSessionProbeArrayProvider(
    defaultContext: AgentContext? = null,
) : SessionProbeArrayProvider {

    // TODO EPMDJ-8256 When application is async we must use this implementation «com.alibaba.ttl.TransmittableThreadLocal»
    val requestThreadLocal = ThreadLocal<ConcurrentHashMap<Long, ExecDatum>>()

    val probeMetaContainer = ProbeMetaContainer()

    val runtimes = mutableMapOf<String, ExecRuntime>()

    @Volatile
    var global: Pair<String, GlobalExecRuntime>? = null

    var defaultContext: AgentContext?
        get() = _defaultContext.value
        set(value) {
            _defaultContext.value = value
        }

    var logger: Logger?
        get() = _logger.value
        set(value) {
            _logger.value = value
        }

    private val _logger = atomic<Logger?>(null)

    private val _defaultContext = atomic(defaultContext)

    @Volatile
    private var _context: AgentContext? = null

    @Volatile
    private var _globalContext: AgentContext? = null

    private val stubProbes = StubAgentProbes()

    override fun invoke(
        id: Long,
        num: Int,
        name: String,
        probeCount: Int,
    ): AgentProbes = requestThreadLocal.get()?.get(id)?.probes
        ?: global?.second?.get(id)
        ?: stubProbes.also { logger?.trace { "Stub probes call. Class id: $id, class name: $name" } }

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

    /**
     * Remove the test session from the active session list and return probes
     * @features Session finishing
     */
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
//        runtimes[sessionId]?.addCompletedTests(tests)
    }

    override fun getActiveSessions(): Set<String> = (global?.let {
        setOf(it.first)
    } ?: emptySet()) + runtimes.keys

    private fun add(sessionId: String, realtimeHandler: RealtimeHandler) {
        if (sessionId !in runtimes) {
            logger?.trace { "Create new runtime" }
            val value = ExecRuntime(logger, realtimeHandler)
            runtimes[sessionId] = value
        } else runtimes
    }

    private fun addGlobal(sessionId: String, testName: String?, realtimeHandler: RealtimeHandler) {
        val name = testName ?: DEFAULT_TEST_NAME
        val testId = name.id()
        val runtime = GlobalExecRuntime(name, logger, realtimeHandler).apply {
            execData.fillFromMeta(TestKey(name, testId))
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

    fun ExecData.fillFromMeta(testKey: TestKey) {
        val (testName, testId) = testKey
        probeMetaContainer.forEachIndexed { probeDescriptor ->
            if (probeDescriptor != null)
                this[probeDescriptor.id] = probeDescriptor.toExecDatum(testName, testId)
        }
    }
}

private class GlobalContext(
    private val sessionId: String,
    private val testName: String?,
) : AgentContext {
    override fun get(key: String): String? = testName?.takeIf { key == DRIlL_TEST_NAME_HEADER }

    override fun invoke(): String = sessionId
}
