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
package com.epam.drill.plugins.test2code.global_filter

import com.epam.drill.plugins.test2code.api.*
import kotlin.test.*

class AttrTest {

    /**
     * @see TestOverview
     */
    @Test
    fun `should return static fields of testOverview`() {
        val details = "details$PATH_DELIMITER"
        assertEquals(setOf("testId", "duration", "result", "${details}engine", "${details}path", "${details}testName"),
            staticAttr())
    }

}

