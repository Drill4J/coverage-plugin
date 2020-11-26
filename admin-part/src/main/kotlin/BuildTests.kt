package com.epam.drill.plugins.test2code

import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.coverage.*
import kotlinx.serialization.*
import mu.*

private val logger = KotlinLogging.logger {}

@Serializable
data class BuildTests(
    val tests: GroupedTests = emptyMap(),
    val assocTests: Set<AssociatedTests> = emptySet()
)

internal fun BundleCounters.testsWith(
    methods: Iterable<Method>
): GroupedTests = byTest.asSequence().takeIf { it.any() }?.run {
    val lazyMethodMap = lazy(LazyThreadSafetyMode.NONE) {
        methods.groupBy(Method::ownerClass)
    }
    val lazyPackageSet = lazy(LazyThreadSafetyMode.NONE) { methods.toPackageSet() }
    mapNotNull { (test, counter) ->
        test.takeIf {
            val packageSeq = counter.packages.asSequence()
            packageSeq.toCoveredMethods(lazyMethodMap::value, lazyPackageSet::value).any()
        }
    }.distinct().groupBy(TypedTest::type, TypedTest::name)
}.orEmpty()

internal fun GroupedTests.filter(
    predicate: (String, String) -> Boolean
): GroupedTests = asSequence().mapNotNull { (type, tests) ->
    val filtered = tests.filter { predicate(it, type) }
    filtered.takeIf { it.any() }?.let { type to it }
}.toMap()

internal fun GroupedTests.withoutCoverage(
    bundleCounters: BundleCounters
): GroupedTests = filter { name, type ->
    TypedTest(name, type) !in bundleCounters.byTest
}

internal fun CoverContext.testsToRunDto(
    bundleCounters: BundleCounters = build.bundleCounters
): List<TestCoverageDto> = testsToRun.flatMap { (type, tests) ->
    tests.map { name ->
        val typedTest = TypedTest(type = type, name = name)
        TestCoverageDto(
            id = typedTest.id(),
            type = type,
            name = name,
            toRun = typedTest !in bundleCounters.byTest,
            coverage = bundleCounters.byTest[typedTest]?.toCoverDto(packageTree) ?: CoverDto(),
            stats = bundleCounters.statsByTest[typedTest] ?: TestStats(0, TestResult.PASSED)
        )
    }
}

internal fun CoverContext.testsDurationDto(
    parentScopes: Sequence<FinishedScope>?
): TestsDurationDto {
    val parentTests = parentScopes?.map { scope ->
        scope.data.sessions.map { session -> session.duration }.sum()
    }?.sum() ?: 0

    val parentTestsToRun = parentBuild?.let { testsToRunDuration(it) } ?: 0

    val testsToRun = testsToRunDuration()

    val testsDurationDto = TestsDurationDto(parentTests, parentTestsToRun, testsToRun)
    logger.debug { "testsDurationDto=$testsDurationDto for buildVersion ${build.version}." }
    return testsDurationDto
}

private fun CoverContext.testsToRunDuration(
    build: CachedBuild = this.build
): Long = this.testsToRun.flatMap { (type, tests) ->
    tests.map { name ->
        val typedTest = TypedTest(type = type, name = name)
        build.bundleCounters.statsByTest[typedTest]?.duration
    }
}.filterNotNull().sum()

internal fun GroupedTests.toDto() = GroupedTestsDto(
    totalCount = totalCount(),
    byType = this
)

internal fun GroupedTests.totalCount(): Int = values.sumBy { it.count() }

internal fun GroupedTests.toTypeCounts(): List<TestTypeCount> = map { (testType, list) ->
    TestTypeCount(
        type = testType,
        count = list.count()
    )
}
