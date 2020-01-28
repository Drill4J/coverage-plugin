package com.epam.drill.plugins.test2code.storage

import com.epam.drill.plugins.test2code.*
import com.epam.kodux.*

class ScopeManager(private val storage: StoreClient) {

    suspend fun scopes(): Sequence<FinishedScope> = storage.getAll<FinishedScope>().asSequence()

    suspend fun scopes(buildVersion: String, enabled: Boolean? = true): Sequence<FinishedScope> {
        return scopes().filter { it.buildVersion == buildVersion && enabled?.equals(it.enabled) ?: true }
    }

    suspend fun saveScope(scope: FinishedScope) {
        storage.store(scope)
    }

    suspend fun saveClassesData(classesData: ClassesData) {
        storage.store(classesData)
    }

    suspend fun getVersionMap(): Map<String, String> {
        val classesDatas = storage.getAll<ClassesData>()
        return classesDatas.mapNotNull { classesData ->
            if (classesData.prevBuildVersion.isNotBlank()) {
                classesData.prevBuildVersion to classesData.buildVersion
            } else null
        }.toMap()
    }

    suspend fun delete(scopeId: String): FinishedScope? =
        getScope(scopeId)?.apply {
            storage.deleteById<FinishedScope>(scopeId)
        }

    suspend fun getScope(scopeId: String): FinishedScope? = storage.findById(scopeId)
}
