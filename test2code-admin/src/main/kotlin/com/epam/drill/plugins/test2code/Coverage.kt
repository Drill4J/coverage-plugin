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

import com.epam.drill.plugin.api.end.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.global_filter.*
import com.epam.drill.plugins.test2code.storage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.dsm.*
import com.epam.dsm.find.*
import kotlinx.collections.immutable.*
import java.io.*
import java.util.stream.*

private val logger = logger {}

internal fun Sequence<Session>.calcBundleCounters(
    context: CoverContext,
    cache: Map<TestKey, BundleCounter> = emptyMap(),
) = run {
    logger.trace {
        "CalcBundleCounters for ${context.build.agentKey} sessions(size=${this.toList().size}, ids=${
            this.toList().map { it.id + " " }
        })..."
    }
    val probesByTestType = groupBy(Session::testType)
    val testTypeOverlap: Sequence<ExecClassData> = if (probesByTestType.size > 1) {
        probesByTestType.values.asSequence().run {
            val initial: PersistentMap<Long, ExecClassData> = first().asSequence().flatten().merge()
            drop(1).fold(initial) { intersection, sessions ->
                intersection.intersect(sessions.asSequence().flatten())
            }
        }.values.asSequence()
    } else emptySequence()
    logger.trace { "Starting to create the bundle with probesId count ${context.probeIds.size}..." }
    val execClassData = flatten()
    BundleCounters(
        all = execClassData.bundle(context),
        testTypeOverlap = testTypeOverlap.bundle(context),
        overlap = execClassData.overlappingBundle(context),
        byTestType = probesByTestType.mapValues {
            it.value.asSequence().flatten().bundle(context)
        },
        byTest = trackTime("bundlesByTests") { probesByTestType.bundlesByTests(context, cache) },
        byTestOverview = fold(mutableMapOf()) { map, session ->
            session.tests.forEach { overview ->
                val testKey = TestKey(id = overview.testId, type = session.testType)
                map[testKey] = map[testKey]?.run {
                    copy(
                        duration = duration + overview.duration,
                        result = overview.result,
                        details = overview.details.copy(labels = overview.details.labels + details.labels)
                    )
                } ?: overview
            }
            map
        },
    )
}

suspend fun List<TestOverviewFilter>.calcBundleCounters(
    context: CoverContext,
    storeClient: StoreClient,
    agentKey: AgentKey,
) = run {
    logger.trace { "Starting to create the bundle with probesId count ${context.probeIds.size}..." }
    val sessionsIds = storeClient.sessionIds(agentKey)
    val testIds = findTestsByFilters(storeClient, sessionsIds, this)
    val byTestOverview: Map<TestKey, TestOverview> = findByTestType(storeClient, sessionsIds, testIds)
    val byTestType: Map<String, List<TestOverview>> = byTestOverview.toList().groupBy({ it.first.type }, { it.second })
    val allProbes: Sequence<ExecClassData> = findProbes(storeClient, sessionsIds, testIds).asSequence()
    val probesByTestId = groupProbes(storeClient, sessionsIds, testIds)
    BundleCounters(
        all = allProbes.bundle(context),
        testTypeOverlap = BundleCounter.empty,
        overlap = BundleCounter.empty,
        byTestType = byTestType.map {
            val tests: List<String> = it.value.map { overview -> overview.testId }
            val probes = findProbes(storeClient, sessionsIds, tests).asSequence()
            it.key to probes.bundle(context)
        }.toMap(),
        byTest = byTestOverview.map {
            it.key to (probesByTestId[it.key.id]?.bundle(context) ?: BundleCounter.empty)
        }.toMap(),
        byTestOverview = byTestOverview,
    )
}

internal fun BundleCounters.calculateCoverageData(
    context: CoverContext,
    scope: Scope? = null,
): CoverageInfoSet {
    val bundle = all
    val bundlesByTests = byTest

    val assocTestsMap = trackTime("assocTestsMap") { associatedTests() }

    val tree = context.packageTree
    val coverageCount = bundle.count.copy(total = tree.totalCount)
    val totalCoveragePercent = coverageCount.percentage()

    val coverageByTests = trackTime("coverageByTests") {
        CoverageByTests(
            all = TestSummary(
                coverage = bundle.toCoverDto(tree),
                testCount = bundlesByTests.keys.count(),
                duration = byTestOverview.values.sumOf { it.duration }
            ),
            byType = byTestType.coveragesByTestType(byTest, context, byTestOverview)
        )
    }
    logger.info { coverageByTests.byType }

    val methodCount = bundle.methodCount.copy(total = tree.totalMethodCount)
    val classCount = bundle.classCount.copy(total = tree.totalClassCount)
    val packageCount = bundle.packageCount.copy(total = tree.packages.count())
    val coverageBlock: Coverage = when (scope) {
        null -> {
            BuildCoverage(
                percentage = totalCoveragePercent,
                count = coverageCount,
                methodCount = methodCount,
                classCount = classCount,
                packageCount = packageCount,
                testTypeOverlap = testTypeOverlap.toCoverDto(tree),
                byTestType = coverageByTests.byType
            )
        }
        is FinishedScope -> scope.summary.coverage
        else -> ScopeCoverage(
            percentage = totalCoveragePercent,
            count = coverageCount,
            overlap = overlap.toCoverDto(tree),
            methodCount = methodCount,
            classCount = classCount,
            packageCount = packageCount,
            testTypeOverlap = testTypeOverlap.toCoverDto(tree),
            byTestType = coverageByTests.byType
        )
    }
    logger.info { coverageBlock }

    val buildMethods = trackTime("calculateBundleMethods") { context.calculateBundleMethods(bundle) }

    val packageCoverage = tree.packages.treeCoverage(bundle, assocTestsMap)

    val tests = bundlesByTests.map { (testKey, bundle) ->
        val testOverview = byTestOverview[testKey] ?: TestOverview.empty
        val typedTest = TypedTest(type = testKey.type, details = testOverview.details)
        TestCoverageDto(
            id = testKey.id(),
            type = typedTest.type,
            coverage = bundle.toCoverDto(tree),
            overview = testOverview,
        )
    }.sortedBy { it.type }

    return CoverageInfoSet(
        assocTestsMap,
        coverageBlock,
        buildMethods,
        packageCoverage,
        tests,
        coverageByTests,
    )
}

internal fun Map<String, BundleCounter>.coveragesByTestType(
    bundleMap: Map<TestKey, BundleCounter>,
    context: CoverContext,
    byOverview: Map<TestKey, TestOverview>,
): List<TestTypeSummary> = map { (testType, bundle) ->
    TestTypeSummary(
        type = testType,
        summary = TestSummary(
            coverage = bundle.toCoverDto(context.packageTree),
            testCount = bundleMap.keys.filter { it.type == testType }.distinct().count(),
            duration = byOverview.filter { it.key.type == testType }.map { it.value.duration }.sum()
        )
    )
}

private fun Map<String, List<Session>>.bundlesByTests(
    context: CoverContext,
    cache: Map<TestKey, BundleCounter>,
): Map<TestKey, BundleCounter> = run {
    val bundleByTests = values.asSequence().flatten().testsWithBundle()
    bundleByTests.putAll(cache)
    map { (testType, sessions: List<Session>) ->
        sessions.asSequence().flatten()
            .mapNotNull { execData ->
                execData.testId.testKey(testType).takeIf { it !in cache }?.to(execData)
            }
            .groupBy(Pair<TestKey, ExecClassData>::first) { it.second }
            .mapValuesTo(bundleByTests) {
                it.value.asSequence().bundle(context)
            }
    }.takeIf { it.isNotEmpty() }?.reduce { m1, m2 ->
        m1.apply { putAll(m2) }
    }
    bundleByTests
}

private fun Sequence<Session>.testsWithBundle(
): MutableMap<TestKey, BundleCounter> = flatMap { session ->
    session.tests.map { it.testId.testKey(session.testType) }.asSequence()
}.associateWithTo(mutableMapOf()) {
    BundleCounter.empty
}


internal fun Sequence<ExecClassData>.overlappingBundle(
    context: CoverContext,
): BundleCounter = context.build.probes.intersect(this).run {
    values.asSequence()
}.bundle(context)

internal fun Sequence<ExecClassData>.bundle(
    context: CoverContext,
): BundleCounter = bundle(context.packageTree)

internal fun BundleCounters.associatedTests(
    onlyPackages: Boolean = true,
): Map<CoverageKey, List<TypedTest>> = byTest.entries.parallelStream().flatMap { (test, bundle) ->
    val typedTest = byTestOverview[test]?.details?.typedTest(test.type) ?: TypedTest(test.type)
    bundle.coverageKeys(onlyPackages).map { it to typedTest }.distinct()
}.collect(Collectors.groupingBy({ it.first }, Collectors.mapping({ it.second }, Collectors.toList())))

internal suspend fun Plugin.exportCoverage(exportBuildVersion: String) = runCatching {
//todo temporarily unsupported

    val coverage = File(System.getProperty("java.io.tmpdir"))
        .resolve("jacoco.exec")
//    coverage.outputStream().use { outputStream ->
//        val executionDataWriter = ExecutionDataWriter(outputStream)
//        val allFinishedScopes = state.scopeManager.byVersion(AgentKey(agentId, exportBuildVersion), true)
//        allFinishedScopes.filter { it.enabled }.flatMap { finishedScope ->
//            finishedScope.data.sessions.flatMap { it.probes }
//        }.writeCoverage(executionDataWriter)
//        if (buildVersion == exportBuildVersion) {
//            activeScope.flatMap {
//                it.probes
//            }.writeCoverage(executionDataWriter)
//        }
//    }
    ActionResult(StatusCodes.OK, coverage)
}.getOrElse {
    logger.error(it) { "Can't get coverage. Reason:" }
    ActionResult(StatusCodes.BAD_REQUEST, "Can't get coverage.")
}


internal suspend fun Plugin.importCoverage(
    inputStream: InputStream,
    sessionId: String = genUuid(),
) = activeScope.startSession(sessionId, "UNIT").runCatching {
//    todo temporarily unsupported

//    val jacocoFile = inputStream.use { ExecFileLoader().apply { load(it) } }
//    val probeIds = state.coverContext().probeIds
//    val execDatum = jacocoFile.executionDataStore.contents.map {
//        ExecClassData(className = it.name, probes = it.probes.toBitSet(), testName = "All unit tests")
//    }.asSequence()
//    execDatum.bundle(probeIds) { bytes, execData ->
//        analyzeClass(bytes, execData.name)
//    }
//    activeScope.addProbes(sessionId) { execDatum.toList() }
//    state.finishSession(sessionId)
    ActionResult(StatusCodes.OK, "Coverage successfully imported")
}.getOrElse {
    state.activeScope.cancelSession(sessionId)
    logger.error { "Can't import coverage. Session was cancelled." }
    ActionResult(StatusCodes.ERROR, "Can't import coverage. An error occurred: ${it.message}")
}
