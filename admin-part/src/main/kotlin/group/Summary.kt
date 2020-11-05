package com.epam.drill.plugins.test2code.group

import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.coverage.*
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.*

data class AgentSummary(
    val name: String,
    val buildVersion: String,
    val coverage: Count,
    val scopeCount: Int,
    val arrow: ArrowType,
    val risks: Int,
    val tests: GroupedTests,
    val testsToRun: GroupedTests,
    val recommendations: Set<String>
)

internal typealias AgentSummaries = PersistentMap<String, AgentSummary>

internal val summaryAggregator = SummaryAggregator()

internal class SummaryAggregator : (String, String, AgentSummary) -> AgentSummary {
    private val _summaryCache = atomic(
        persistentHashMapOf<String, AgentSummaries>()
    )

    override operator fun invoke(
        serviceGroup: String,
        agentId: String,
        agentSummary: AgentSummary
    ): AgentSummary = run {
        val cache = _summaryCache.updateAndGet {
            val curAgentSummary = it[serviceGroup] ?: persistentHashMapOf()
            it.put(serviceGroup, curAgentSummary.put(agentId, agentSummary))
        }
        cache[serviceGroup]?.values?.reduce { acc, element ->
            acc + element
        } ?: agentSummary
    }

    fun getSummaries(serviceGroup: String): Map<String, AgentSummary> = _summaryCache.value[serviceGroup] ?: emptyMap()
}

internal fun CachedBuild.toSummary(
    agentName: String,
    testsToRun: GroupedTests,
    parentCoverageCount: Count? = null
): AgentSummary = testsToRun.withoutCoverage(bundleCounters).let { toRun ->
    AgentSummary(
        name = agentName,
        buildVersion = version,
        coverage = coverage.count,
        scopeCount = coverage.scopeCount,
        arrow = parentCoverageCount.arrowType(coverage.count),
        risks = coverage.risks,
        tests = tests.tests,
        testsToRun = toRun,
        recommendations = coverage.recommendations(toRun)
    )
}

internal fun AgentSummary.toDto(agentId: String) = AgentSummaryDto(
    id = agentId,
    buildVersion = buildVersion,
    name = name,
    summary = toDto()
)

internal fun AgentSummary.toDto() = SummaryDto(
    coverage = coverage.percentage(),
    coverageCount = coverage,
    scopeCount = scopeCount,
    arrow = arrow,
    risks = risks,
    tests = tests.toTestCountDto(),
    testsToRun = testsToRun.toTestCountDto(),
    recommendations = recommendations
)

internal operator fun AgentSummary.plus(
    other: AgentSummary
): AgentSummary = copy(
    coverage = (coverage + other.coverage),
    scopeCount = scopeCount + other.scopeCount,
    arrow = ArrowType.UNCHANGED,
    risks = risks + other.risks,
    tests = tests + other.tests,
    testsToRun = testsToRun + other.testsToRun,
    recommendations = recommendations.union(other.recommendations)
)

operator fun Count.plus(other: Count): Count = copy(
    covered = covered + other.covered,
    total = total + other.total
)

internal fun GroupedTests.toDto() = GroupedTestsDto(
    totalCount = totalCount(),
    byType = this
)

fun GroupedTests.totalCount(): Int = this.values.sumBy { it.count() }

fun GroupedTests.toSummary(): List<TestTypeCount> = map { (testType, list) ->
    TestTypeCount(
        type = testType,
        count = list.count()
    )
}

private fun GroupedTests.toTestCountDto() = TestCountDto(
    count = totalCount(),
    byType = mapValues { it.value.count() }
)

private operator fun GroupedTests.plus(other: GroupedTests): GroupedTests = sequenceOf(this, other)
    .flatMap { it.asSequence() }
    .groupBy({ it.key }, { it.value })
    .mapValues { (_, values) ->
        values.flatten().distinct()
    }
