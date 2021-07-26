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

import com.epam.drill.logger.api.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugin.api.processing.*
import com.epam.drill.plugins.test2code.common.api.*
import com.github.luben.zstd.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.*
import org.jacoco.core.internal.data.*
import org.mapdb.*
import java.util.*

@Suppress("unused")
class Plugin(
    id: String,
    agentContext: AgentContext,
    sender: Sender,
    logging: LoggerFactory
) : AgentPart<AgentAction>(id, agentContext, sender, logging), Instrumenter {
    private val logger = logging.logger("Plugin $id")

    internal val json = Json { encodeDefaults = true }

    private val _enabled = atomic(false)

    private val enabled: Boolean get() = _enabled.value

    private val instrContext: SessionProbeArrayProvider = DrillProbeArrayProvider.apply {
        defaultContext = agentContext
    }

    private val instrumenter: DrillInstrumenter = instrumenter(instrContext, logger)

    private val _retransformed = atomic(false)
    private var db: DB
    val probesDb: HTreeMap.KeySet<Any>

    init {
        //TODO this is hack to initialize mapDB
        Thread.currentThread().contextClassLoader = ClassLoader.getSystemClassLoader()
        //TODO crash on agent restart without db clearing
        db = DBMaker.fileDB("test2code.db")
            .transactionEnable()
            .closeOnJvmShutdown()
            .make()
        probesDb = db.hashSet("probes")
            .serializer(Serializer.JAVA)
            .createOrOpen()
    }

    val probeSenderJob = ProbeWorker.launch {
        //TODO may be necessary to close this coroutine
        while (true) {
            probesDb.mapNotNull { coverDataPart ->
                (coverDataPart as? CoverageInfo)?.also { probesDb.remove(it) }
            }.forEach { coverInfo ->
                coverInfo.coverMessage.forEach { send(it) }
                val count = coverInfo.count
                if (coverInfo.sendChanged && count > 0) {
                    sendMessage(SessionChanged(coverInfo.sessionId, count))
                }
            }
            delay(3000L)
        }
    }

    //TODO remove
    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    //TODO remove
    override fun isEnabled(): Boolean = _enabled.value

    override fun on() {
        val initInfo = InitInfo(message = "Initializing plugin $id...")
        sendMessage(initInfo)
        if (_retransformed.compareAndSet(expect = false, update = true)) {
            retransform()
        }
        sendMessage(Initialized(msg = "Initialized"))
        logger.info { "Plugin $id initialized!" }
    }

    override fun off() {
        logger.info { "Enabled $enabled" }
        val cancelledCount = instrContext.cancelAll()
        logger.info { "Plugin $id is off" }
        if (_retransformed.compareAndSet(expect = true, update = false)) {
            retransform()
        }
        sendMessage(SessionsCancelled(cancelledCount, currentTimeMillis()))
    }

    /**
     * Retransforming does not require an agent part instance.
     * This method is used in integration tests.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun retransform() {
        try {
            Native.RetransformClassesByPackagePrefixes(byteArrayOf())
        } catch (ex: Throwable) {
            logger.error(ex) { "Error retransforming classes." }
        }
    }

    override fun instrument(
        className: String,
        initialBytes: ByteArray
    ): ByteArray? = takeIf { enabled }?.run {
        val idFromClassName = CRC64.classId(className.encodeToByteArray())
        instrumenter(className, idFromClassName, initialBytes)
    }

    override fun destroyPlugin(unloadReason: UnloadReason) {}

    override fun initPlugin() {
        logger.info { "Plugin $id: initializing..." }
        retransform()
        _retransformed.value = true
    }

    override suspend fun doAction(action: AgentAction) {
        when (action) {
            is InitActiveScope -> action.payload.apply {
                logger.info { "Initializing scope $id, $name, prevId=$prevId" }
                instrContext.cancelAll()
                sendMessage(
                    ScopeInitialized(
                        id = id,
                        name = name,
                        prevId = prevId,
                        ts = currentTimeMillis()
                    )
                )
            }
            is StartAgentSession -> action.payload.run {
                logger.info { "Start recording for session $sessionId (isGlobal=$isGlobal)" }
                val handler = probeCollector(sessionId, isRealtime)
                instrContext.start(sessionId, isGlobal, testName, handler)
                sendMessage(SessionStarted(sessionId, testType, isRealtime, currentTimeMillis()))
            }
            is AddAgentSessionData -> {
                //ignored
            }
            is StopAgentSession -> {
                val sessionId = action.payload.sessionId
                logger.info { "End of recording for session $sessionId" }
                val runtimeData = instrContext.stop(sessionId) ?: emptySequence()
                if (runtimeData.any()) {
                    probeCollector(sessionId)(runtimeData)
                } else logger.info { "No data for session $sessionId" }
                sendMessage(SessionFinished(sessionId, currentTimeMillis()))
            }
            is StopAllAgentSessions -> {
                val stopped = instrContext.stopAll()
                logger.info { "End of recording for sessions $stopped" }
                for ((sessionId, data) in stopped) {
                    if (data.any()) {
                        probeCollector(sessionId)(data)
                    }
                }
                val ids = stopped.map { it.first }
                sendMessage(SessionsFinished(ids, currentTimeMillis()))
            }
            is CancelAgentSession -> {
                val sessionId = action.payload.sessionId
                logger.info { "Cancellation of recording for session $sessionId" }
                instrContext.cancel(sessionId)
                sendMessage(SessionCancelled(sessionId, currentTimeMillis()))
            }
            is CancelAllAgentSessions -> {
                val cancelled = instrContext.cancelAll()
                logger.info { "Cancellation of recording for sessions $cancelled" }
                sendMessage(SessionsCancelled(cancelled, currentTimeMillis()))
            }
            else -> Unit
        }
    }

    override fun parseAction(
        rawAction: String,
    ): AgentAction = json.decodeFromString(AgentAction.serializer(), rawAction)
}

fun Plugin.probeCollector(
    sessionId: String,
    sendChanged: Boolean = false
): RealtimeHandler = { execData ->
    execData.map(ExecDatum::toExecClassData)
        .chunked(0xffff) { CoverDataPart(sessionId, it) }
        .takeIf { it.any() }
        ?.let { messages ->
            val encoded = messages.map {
                val encoded = ProtoBuf.encodeToByteArray(CoverMessage.serializer(), it)
                val compressed = Zstd.compress(encoded)
                Base64.getEncoder().encodeToString(compressed)
            }
            val count = messages.sumBy { it.data.count() }
            probesDb.add(encoded.toCoverageInfo(sessionId, sendChanged, count))
        }
}

fun Plugin.sendMessage(message: CoverMessage) {
    val messageStr = json.encodeToString(CoverMessage.serializer(), message)
    send(messageStr)
}

private fun Sequence<String>.toCoverageInfo(
    sessionId: String,
    sendChanged: Boolean,
    count: Int
) = CoverageInfo(sessionId, toList(), sendChanged, count)

data class CoverageInfo(
    val sessionId: String,
    val coverMessage: List<String>,
    val sendChanged: Boolean,
    val count: Int,
) : JvmSerializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoverageInfo) return false
        if (sessionId != other.sessionId) return false
        if (coverMessage != other.coverMessage) return false
        if (sendChanged != other.sendChanged) return false
        if (count != other.count) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + coverMessage.hashCode()
        result = 31 * result + sendChanged.hashCode()
        result = 31 * result + count
        return result
    }
}
