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
import kotlin.random.*
import kotlin.test.*


class ExecRuntimeTest {
    private val threadLocal = ThreadLocal<Array<ExecDatum?>>()
    private val className = "foo/bar/Foo"
    private val testName = "test"

    @AfterTest
    fun removeThreadLocal() {
        threadLocal.remove()
    }

    @Test
    fun `getOrPut - add data with same test name`() {
        val runtime = ExecRuntime { }
        val first = runtime.getOrPut(testName) { arrayOfNulls(5) }
        val second = runtime.getOrPut(testName) { arrayOfNulls(9) }
        assertEquals(first, second)
        assertEquals(5, first.size)
        assertEquals(5, second.size)
        assertTrue { runtime.collect().none() }
    }

    @Test
    fun `putIndex - existing value`() {
        val runtime = ExecRuntime {}
        val value = runtime.getOrPut(testName) { arrayOfNulls(9) }
        runtime.put(7) { ExecDatum(1L, className, AgentProbes()) }
        assertNotNull(value[7])
        assertTrue { runtime.collect().none() }
    }

    @Test
    fun `putIndex - not existing value`() {
        val runtime = ExecRuntime {}
        val value = runtime.getOrPut(testName) { arrayOfNulls(5) }
        assertFails {
            runtime.put(7) { ExecDatum(1L, className, AgentProbes()) }
        }
        assertEquals(5, value.size)
    }

    @Test
    fun `collect - empty probes`() {
        val runtime = ExecRuntime {}
        val value = runtime.getOrPut(testName) { arrayOfNulls(9) }
        runtime.put(7) { ExecDatum(1L, className, AgentProbes()) }
        assertNotNull(value[7])
        assertTrue { runtime.collect().none() }
    }

    @Test
    fun `collect - should add probes`() {
        val runtime = ExecRuntime {}
        runtime.fillProbe()
        threadLocal.get()[0]?.probes?.set(Random.nextInt(5))
        val data = runtime.collect()
        assertFalse { data.none() }
        assertTrue { threadLocal.get()[0] != null }
        val first = data.first()
        assertEquals(1, first.probes.values.count { it })
        assertEquals(4, first.probes.values.count { !it })
        assertEquals(testName, first.testName)
        assertEquals(className, first.name)
    }

    @Test
    fun `collect - during the execution of a request`() {
        val runtime = ExecRuntime {}
        runtime.fillProbe()
        threadLocal.get()[0]?.probes?.set(1)
        val data = runtime.collect()
        assertFalse { data.none() }
        assertTrue { threadLocal.get()[0] != null }
        threadLocal.get()[0]?.probes?.set(2)
        //if execData is cleared, this assert fails
        assertTrue { runtime.collect().any() }
    }

    @Test
    fun `collect - should not collect the same probes`() {
        val runtime = ExecRuntime {}
        runtime.fillProbe()
        threadLocal.get()[0]?.probes?.set(1)
        threadLocal.get()[0]?.probes?.set(2)
        val first = runtime.collect()
        assertTrue { first.any() }
        runtime.fillProbe()
        threadLocal.get()[0]?.probes?.set(3)
        val second = runtime.collect()
        assertTrue { second.any() }
        assertEquals(2, first.first().probes.values.count { it })
        assertEquals(1, second.first().probes.values.count { it })
    }

    private fun ExecRuntime.fillProbe() {
        val value = getOrPut(testName) { arrayOfNulls(5) }
        threadLocal.set(value)
        put(0) { ExecDatum(1L, className, AgentProbes(5), it) }
    }
}
