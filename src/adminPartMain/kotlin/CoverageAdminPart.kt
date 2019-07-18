package com.epam.drill.plugins.coverage


import com.epam.drill.common.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugin.api.end.*
import com.epam.drill.plugin.api.message.*
import kotlinx.serialization.*
import org.jacoco.core.analysis.*
import org.jacoco.core.data.*

internal val agentStates = AtomicCache<String, AgentState>()

@Suppress("unused", "MemberVisibilityCanBePrivate")
class CoverageAdminPart(sender: Sender, agentInfo: AgentInfo, id: String) :
    AdminPluginPart<Action>(sender, agentInfo, id) {

    override val serDe: SerDe<Action> = commonSerDe

    private val buildVersion = agentInfo.buildVersion

    private val agentState: AgentState = agentStates(agentInfo.id) { state ->
        when (state?.agentInfo) {
            agentInfo -> state
            else -> AgentState(agentInfo, state)
        }
    }!!
    
    private val activeScope get() = agentState.activeScope

    override suspend fun doAction(action: Action): Any {
        return when (action) {
            is SwitchActiveScope -> changeActiveScope(action.payload)
            is RenameActiveScope -> renameActiveScope(action.payload)
            is ToggleScope -> toggleScope(action.payload.scopeId)
            is DropScope -> dropScope(action.payload.scopeId)
            is StartNewSession -> {
                val startAgentSession = StartSession(
                    payload = StartSessionPayload(
                        sessionId = genUuid(),
                        startPayload = action.payload
                    )
                )
                serDe.actionSerializer stringify startAgentSession
            }
            else -> Unit
        }
    }

    internal suspend fun renameActiveScope(payload: ActiveScopePayload) {
        val oldSummary = activeScope.rename(payload.scopeName)
        println("Renamed $activeScope: ${oldSummary.name} -> ${activeScope.name}")
        sendActiveScope()
    }

    override suspend fun processData(dm: DrillMessage): Any {
        val content = dm.content
        val message = CoverMessage.serializer() parse content!!
        return processData(message)
    }

    internal suspend fun processData(coverMsg: CoverMessage): Any {
        when (coverMsg) {
            is InitInfo -> {
                agentState.init(coverMsg)
                println(coverMsg.message) //log init message
                println("${coverMsg.classesCount} classes to load")
            }
            is ClassBytes -> {
                val className = coverMsg.className
                val bytes = coverMsg.bytes.decode()
                agentState.addClass(className, bytes)
            }
            is Initialized -> {
                println(coverMsg.msg) //log initialized message
                agentState.initialized()
                val classesData = agentState.classesData()
                if (classesData.changed) {
                    calculateAndSendActiveScopeCoverage()
                    calculateAndSendBuildCoverage()
                    sendScopeMessages()
                }
            }
            is SessionStarted -> {
                agentState.startSession(coverMsg)
                println("Session ${coverMsg.sessionId} started.")
                sendActiveSessions()
            }
            is SessionCancelled -> {
                agentState.cancelSession(coverMsg)
                println("Session ${coverMsg.sessionId} cancelled.")
                sendActiveSessions()
            }
            is CoverDataPart -> {
                agentState.addProbes(coverMsg)
            }
            is SessionFinished -> {
                val scope = agentState.activeScope
                when(val session = agentState.finishSession(coverMsg)) {
                    null -> println("No active session for sessionId ${coverMsg.sessionId}")
                    else -> {
                        if (session.any()) {
                            val classesData = agentState.classesData()
                            scope.update(session, classesData)
                            sendScopeMessages()
                        } else println("Session ${session.id} is empty, it won't be added to the active scope")
                        calculateAndSendActiveScopeCoverage()
                        println("Session ${session.id} finished.")
                    }
                }
            }
        }
        return ""
    }

    internal fun calculateCoverageData(
        finishedSessions: Sequence<FinishedSession>,
        isBuildCvg: Boolean = false
    ): CoverageInfoSet {
        val probes = finishedSessions.flatten()
        val classesData = agentState.classesData()
        // Analyze all existing classes
        val coverageBuilder = CoverageBuilder()
        val dataStore = ExecutionDataStore().with(probes)
        val initialClassBytes = classesData.classesBytes
        val analyzer = Analyzer(dataStore, coverageBuilder)

        val scopeProbes = probes.toList()
        val assocTestsMap = getAssociatedTestMap(scopeProbes, initialClassBytes)
        val associatedTests = assocTestsMap.getAssociatedTests()

        initialClassBytes.forEach { (name, bytes) ->
            analyzer.analyzeClass(bytes, name)
        }
        val bundleCoverage = coverageBuilder.getBundle("")
        val totalCoveragePercent = bundleCoverage.coverage(classesData.totals.instructionCounter.totalCount)

        val classesCount = classesData.totals.classCounter.totalCount
        val methodsCount = classesData.totals.methodCounter.totalCount
        val uncoveredMethodsCount = methodsCount - bundleCoverage.methodCounter.coveredCount
        val coverageBlock = CoverageBlock(
            coverage = totalCoveragePercent,
            classesCount = classesCount,
            methodsCount = methodsCount,
            uncoveredMethodsCount = uncoveredMethodsCount,
            arrow = if (isBuildCvg) classesData.arrowType(totalCoveragePercent) else null
        )
        println(coverageBlock)

        val newMethods = classesData.newMethods
        val (newCoverageBlock, newMethodsCoverages)
                = calculateNewCoverageBlock(newMethods, bundleCoverage)
        println(newCoverageBlock)

        val packageCoverage = packageCoverage(bundleCoverage, assocTestsMap)
        val testRelatedBundles = testUsageBundles(initialClassBytes, scopeProbes)
        val testUsages = testUsages(testRelatedBundles)

        return CoverageInfoSet(
            associatedTests,
            coverageBlock,
            newCoverageBlock,
            newMethodsCoverages,
            packageCoverage,
            testUsages
        )
    }

    internal suspend fun sendScopeMessages() {
        sendActiveScope()
        sendScopes()
    }

    internal suspend fun sendActiveSessions() {
        val activeSessions = agentState.activeSessions.run { 
            ActiveSessions(
                count = count(),
                testTypes = values.groupBy { it.testType }.keys 
            )
        }
        sender.send(
            agentInfo,
            "/active-sessions",
            ActiveSessions.serializer() stringify activeSessions
        )
    }

    internal suspend fun sendActiveScope() {
        val activeScopeSummary = agentState.activeScope.summary
        sender.send(
            agentInfo,
            "/active-scope",
            ScopeSummary.serializer() stringify activeScopeSummary
        )
        sendScopeSummary(activeScopeSummary)
    }

    internal suspend fun sendScopeSummary(scopeSummary: ScopeSummary) {
        sender.send(
            agentInfo,
            "/scope/${scopeSummary.id}",
            ScopeSummary.serializer() stringify scopeSummary
        )
    }

    internal suspend fun sendScopes() {
        sender.send(
            agentInfo,
            "/scopes",
            ScopeSummary.serializer().list stringify agentState.scopeSummaries.toList()
        )
    }

    internal suspend fun toggleScope(scopeId: String) {
        agentState.scopes[scopeId]?.let { scope ->
            scope.toggle()
            sendScopes()
            calculateAndSendBuildCoverage()
        }
    }

    internal suspend fun dropScope(scopeId: String) {
        agentState.scopes.remove(scopeId)?.let {
            cleanTopics(id)
            sendScopes()
            calculateAndSendBuildCoverage()
        }
    }

    internal suspend fun changeActiveScope(scopeChange: ActiveScopeChangePayload) {
        val prevScope = agentState.changeActiveScope(scopeChange.scopeName)
        if (scopeChange.savePrevScope) {
            if (prevScope.any()) {
                val finishedScope = prevScope.finish()
                sendScopeSummary(finishedScope.summary)
                println("$finishedScope have been saved.")
                agentState.scopes[finishedScope.id] = finishedScope
                calculateAndSendBuildCoverage()
            } else {
                println("$prevScope is empty, it won't be added to the build.")
                cleanTopics(prevScope.id)
            }
        }
        val activeScope = agentState.activeScope
        println("Current active scope $activeScope")
        calculateAndSendActiveScopeCoverage()
        sendScopeMessages()
    }

    internal suspend fun sendCalcResults(cis: CoverageInfoSet, path: String = "") {
        // TODO extend destination with plugin id
        if (cis.associatedTests.isNotEmpty()) {
            println("Assoc tests - ids count: ${cis.associatedTests.count()}")
            sender.send(
                agentInfo,
                "$path/associated-tests",
                AssociatedTests.serializer().list stringify cis.associatedTests
            )
        }
        sendCoverageBlock(cis.coverageBlock, path)
        sender.send(
            agentInfo,
            "$path/coverage-new",
            NewCoverageBlock.serializer() stringify cis.newCoverageBlock
        )
        sender.send(
            agentInfo,
            "$path/new-methods",
            SimpleJavaMethodCoverage.serializer().list stringify cis.newMethodsCoverages
        )
        val packageCoverage = cis.packageCoverage
        sendPackageCoverage(packageCoverage, path)
        sender.send(
            agentInfo,
            "$path/tests-usages",
            TestUsagesInfo.serializer().list stringify cis.testUsages
        )
    }

    internal suspend fun sendPackageCoverage(
        packageCoverage: List<JavaPackageCoverage>,
        path: String = ""
    ) {
        sender.send(
            agentInfo,
            "$path/coverage-by-packages",
            JavaPackageCoverage.serializer().list stringify packageCoverage
        )
    }

    internal suspend fun sendCoverageBlock(
        coverageBlock: CoverageBlock,
        path: String = ""
    ) {
        sender.send(
            agentInfo,
            "$path/coverage",
            CoverageBlock.serializer() stringify coverageBlock
        )
    }

    internal suspend fun calculateAndSendBuildCoverage() {
        val sessions = agentState.scopes.values
            .filter { it.enabled }
            .flatMap { it.probes.values.flatten().asSequence() }
        val coverageInfoSet = calculateCoverageData(sessions, true)
        agentState.classesData().lastBuildCoverage = coverageInfoSet.coverageBlock.coverage
        sendCalcResults(coverageInfoSet, "/build")
    }

    internal suspend fun calculateAndSendActiveScopeCoverage() {
        val activeScope = agentState.activeScope
        val coverageInfoSet = calculateCoverageData(activeScope)
        sendActiveSessions()
        sendCalcResults(coverageInfoSet, "/scope/${activeScope.id}")
    }

    internal suspend fun cleanTopics(id: String) {
        sender.send(agentInfo, "/scope/$id/associated-tests", "")
        sender.send(agentInfo, "/scope/$id/coverage-new", "")
        sender.send(agentInfo, "/scope/$id/new-methods", "")
        sender.send(agentInfo, "/scope/$id/tests-usages", "")
        sender.send(agentInfo, "/scope/$id/coverage-by-packages", "")
        sender.send(agentInfo, "/scope/$id/coverage", "")
    }

}
