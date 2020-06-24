package com.epam.drill.plugins.test2code

import com.epam.drill.common.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.test.js.*
import com.epam.kodux.*
import jetbrains.exodus.entitystore.*
import kotlinx.coroutines.*
import java.io.*
import java.util.*
import kotlin.test.*

class JsCoverageTest {

    private val storageDir = File("build/tmp/test/storages/${this::class.simpleName}-${UUID.randomUUID()}")

    private val storeClient = StoreClient(PersistentEntityStores.newInstance(storageDir))

    @AfterTest
    fun cleanStore() {
        storeClient.store.close()
        storageDir.deleteRecursively()
    }

    @Test
    fun `coverageData for active scope with custom js probes`() = runBlocking {
        val buildManager = buildManager()
        val state = PluginInstanceState(
            storeClient, jsAgentInfo, buildManager
        )
        state.init()
        (state.data as DataBuilder) += ast
        state.initialized()
        val active = state.activeScope
        active.execSession("MANUAL") { sessionId ->
            addProbes(sessionId, probes)
        }
        active.execSession("AUTO") { sessionId ->
            addProbes(sessionId, IncorrectProbes.underCount)
            addProbes(sessionId, IncorrectProbes.overCount)
            addProbes(sessionId, IncorrectProbes.notExisting)
        }
        val finished = active.finish(enabled = true)
        val context = state.coverContext()
        val coverageData = finished.calculateCoverageData(context, scopeCount = 1)
        coverageData.run {
            assertEquals(Count(3, 5), coverage.count)
            assertEquals(listOf("foo/bar"), packageCoverage.map { it.name })
            packageCoverage[0].classes.run {
                assertEquals(listOf("foo/bar"), map { it.path })
                assertEquals(listOf("baz.js"), map { it.name })
                assertEquals(listOf(100.0, 0.0, 50.0), flatMap { it.methods }.map { it.coverage })
            }
            assertEquals(
                setOf(TypedTest("default", "MANUAL"), TypedTest("default", "AUTO")),
                associatedTests.flatMap { it.tests }.toSet()
            )
        }
    }

    private fun ActiveScope.execSession(testType: String, block: ActiveScope.(String) -> Unit) {
        val sessionId = genUuid()
        startSession(sessionId, testType)
        block(sessionId)
        finishSession(sessionId) {}
    }

    private fun buildManager(): BuildManager {
        return object : BuildManager {
            val info = BuildInfo(jsAgentInfo.buildVersion).let { mapOf(it.version to it) }

            override val builds = info.values

            override fun get(version: String) = info[version]

        }
    }
}
