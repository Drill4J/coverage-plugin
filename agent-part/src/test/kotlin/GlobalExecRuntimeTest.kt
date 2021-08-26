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

import com.epam.drill.jacoco.*
import kotlin.test.*

class GlobalExecRuntimeTest {
    private val className = "foo/bar/Foo"

    @Test
    fun `put - existing index`() {
        val runtime = GlobalExecRuntime("test") {}
        runtime.put(0) { ExecDatum(1L, className, AgentProbes(5)) }
        runtime.put(0) { ExecDatum(1L, className, AgentProbes(10)) }
        assertNotNull(runtime.get(0))
        assertEquals(10, runtime.get(0)?.values?.size)
    }

    @Test
    fun `put - not existing value`() {
        val runtime = GlobalExecRuntime("test") {}
        assertFails { runtime.put(MAX_CLASS_COUNT + 1) { ExecDatum(1L, className, AgentProbes(5)) } }
    }

    @Test
    fun `collect - should add probes`() {
        val runtime = GlobalExecRuntime("test") {}
        runtime.put(0) { ExecDatum(1L, className, AgentProbes(5), it) }
        runtime.get(0)?.set(1)
        val collect = runtime.collect()
        val first = collect.first()
        assertTrue { collect.any() }
        assertEquals(1, first.probes.values.count { it })
        assertEquals("test", first.testName)
    }

    @Test
    fun `collect - empty probes`() {
        val runtime = GlobalExecRuntime("test") {}
        runtime.put(0) { ExecDatum(1L, className, AgentProbes(5)) }
        assertNotNull(runtime.get(0))
        assertTrue { runtime.collect().none() }
    }

    @Test
    @Ignore
    //TODO ignore because of EPMDJ-8428
    fun `collect - should not collect the same probes`() {
        val runtime = GlobalExecRuntime("test") {}
        runtime.put(0) { ExecDatum(1L, className, AgentProbes(5)) }
        runtime.get(0)?.set(0)
        assertTrue { runtime.collect().any() }
        assertTrue { runtime.collect().none() }
    }
}
