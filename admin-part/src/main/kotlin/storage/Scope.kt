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
package com.epam.drill.plugins.test2code.storage

import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.dsm.*
import kotlinx.serialization.*

private val logger = logger {}
fun Sequence<FinishedScope>.enabled() = filter { it.enabled }

class ScopeManager(private val storage: StoreClient) {

    suspend fun byVersion(
        agentKey: AgentKey,
        withData: Boolean = false,
    ): Sequence<FinishedScope> = storage.executeInAsyncTransaction {
        storage.findBy<FinishedScope> {
            FinishedScope::agentKey eq agentKey
        }.run {
            takeIf { withData }?.run {
                trackTime("Loading scope") {
                    storage.findBy<ScopeDataEntity> { ScopeDataEntity::agentKey eq agentKey }.takeIf { it.any() }
                }
            }?.associateBy { it.id }?.let { dataMap ->
                map { it.withProbes(dataMap[it.id], storage) }
            } ?: this
        }
    }.asSequence()

    suspend fun store(scope: FinishedScope) {
        storage.executeInAsyncTransaction {
            trackTime("Store FinishedScope") {
                store(scope.copy(data = ScopeData.empty), storage.schema)
                scope.takeIf { it.any() }?.let {
                    store(ScopeDataEntity(it.id, it.agentKey, it.data), storage.schema)
                }
            }
        }
    }

    suspend fun deleteById(scopeId: String): FinishedScope? = storage.executeInAsyncTransaction {
        //todo make in one transaction in DSM. EPMDJ-9090
        storage.findById<FinishedScope>(scopeId)?.also {
            storage.deleteById<FinishedScope>(scopeId)
            storage.deleteById<ScopeDataEntity>(scopeId)
        }
    }

    suspend fun deleteByVersion(agentKey: AgentKey) {
        storage.executeInAsyncTransaction {
            //todo make in one transaction in DSM. EPMDJ-9090
            storage.deleteBy<FinishedScope> { FinishedScope::agentKey eq agentKey }
            storage.deleteBy<ScopeDataEntity> { ScopeDataEntity::agentKey eq agentKey }
        }
    }

    suspend fun byId(
        scopeId: String,
        withProbes: Boolean = false,
    ): FinishedScope? = storage.run {
        takeIf { withProbes }?.executeInAsyncTransaction {
            findById<FinishedScope>(scopeId)?.run {
                withProbes(findById(scopeId), storage)
            }
        } ?: findById(scopeId)
    }

    internal suspend fun counter(agentKey: AgentKey): ActiveScopeInfo? = storage.findById(agentKey)

    internal suspend fun storeCounter(activeScopeInfo: ActiveScopeInfo) = storage.store(activeScopeInfo)
}

@Serializable
@StreamSerialization
internal class ScopeDataEntity(
    @Id val id: String,
    val agentKey: AgentKey,
    val bytes: ScopeData,
)

private suspend fun FinishedScope.withProbes(
    data: ScopeDataEntity?,
    storeClient: StoreClient,
): FinishedScope = data?.let {
    val scopeData: ScopeData = it.bytes
    val sessions = storeClient.loadSessions(id)
    logger.debug { "take scope $id $name with sessions size ${sessions.size}" }
    copy(data = scopeData.copy(sessions = sessions))
} ?: this
