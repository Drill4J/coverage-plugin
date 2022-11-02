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
package com.epam.drill.plugins.test2code.storage

import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.dsm.*
import com.epam.dsm.find.*
import kotlinx.serialization.*

private val logger = logger {}
class ScopeManager(private val storage: StoreClient) {

    suspend fun byVersion(
        agentKey: AgentKey,
        withData: Boolean = false,
    ): Scope = storage.executeInAsyncTransaction {
        val transaction = this
        transaction.findBy<Scope> {
            Scope::agentKey eq agentKey
        }.get().run {
            takeIf { withData }?.run {
                trackTime("Loading scope") {
                    transaction.findBy<ScopeDataEntity> { ScopeDataEntity::agentKey eq agentKey }.get().takeIf { it.any() }
                }
            }?.associateBy { it.id }?.let { dataMap ->
                map { it.withProbes(dataMap[it.id], storage) }
            } ?: this
        }
    }[0]

    suspend fun deleteByVersion(agentKey: AgentKey) {
        storage.executeInAsyncTransaction {
            deleteBy<Scope> { Scope::agentKey eq agentKey }
            deleteBy<ScopeDataEntity> { ScopeDataEntity::agentKey eq agentKey }
        }
    }

    suspend fun deleteById(scopeId: String): Scope? = storage.executeInAsyncTransaction {
        findById<Scope>(id = scopeId)?.also {
            this.deleteById<Scope>(scopeId)
            this.deleteById<ScopeDataEntity>(scopeId)
        }
    }

    suspend fun byId(
        scopeId: String,
        withProbes: Boolean = false,
    ): Scope? = storage.run {
        takeIf { withProbes }?.executeInAsyncTransaction {
            findById<Scope>(scopeId)?.run {
                withProbes(findById(scopeId), storage)
            }
        } ?: findById(scopeId)
    }

}

@Serializable
@StreamSerialization
internal class ScopeDataEntity(
    @Id val id: String,
    val agentKey: AgentKey,
    val bytes: ScopeData
)

private suspend fun Scope.withProbes(
    data: ScopeDataEntity?,
    storeClient: StoreClient,
): Scope = data?.let {
    val scopeData: ScopeData = it.bytes
    val sessions = storeClient.loadSessions(id)
    logger.debug { "take scope $id with sessions size ${sessions.size}" }
    copy(data = scopeData.copy(sessions = sessions))
} ?: this
