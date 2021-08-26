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

import org.junit.jupiter.api.*
import kotlin.test.*
import kotlin.test.Test

class ProbeMetaContainerTest {
    private val probeDescriptor = ProbeDescriptor(1L, "foo/bar/Foo", 4)

    @Test
    fun `addDescriptor - without runtimes`() {
        val probeContainer = ProbeMetaContainer()
        assertDoesNotThrow { probeContainer.addDescriptor(0, probeDescriptor, null, emptyList()) }
    }

    @Test
    fun `addDescriptor - with global runtime`() {
        val probeContainer = ProbeMetaContainer()
        val global = GlobalExecRuntime("test") {}
        probeContainer.addDescriptor(0, probeDescriptor, global, emptyList())
        assertNotNull(global.get(0))
    }

    @Test
    fun `addDescriptor - with filled local runtime`() {
        val probeMetaContainer = ProbeMetaContainer()
        val local = ExecRuntime {}
        val execDatum = local.getOrPut("test") { arrayOfNulls(MAX_CLASS_COUNT) }
        probeMetaContainer.addDescriptor(0, probeDescriptor, null, listOf(local))
        assertEquals(MAX_CLASS_COUNT, execDatum.size)
    }
}
