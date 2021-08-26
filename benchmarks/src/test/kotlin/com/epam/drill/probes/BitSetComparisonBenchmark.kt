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
package com.epam.drill.probes

import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.*
import kotlin.random.*

@Warmup(iterations = 7, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(2)
class BitSetComparisonBenchmark {
    lateinit var probe1: List<Boolean>
    lateinit var probe2: List<Boolean>
    lateinit var probeBitSet1: Probes
    lateinit var probeBitSet2: Probes
    val len = 10000000

    @Setup
    fun setUp() {
        probe1 = randomBools(len).toList()
        probe2 = randomBools(len).toList()
        probeBitSet1 = probe1.toBitSet()
        probeBitSet2 = probe2.toBitSet()
    }

    @Benchmark
    fun boolListMerge() {
        probe1.merge(probe2)
    }

    @Benchmark
    fun bitSetMerge() {
        probeBitSet1.merge(probeBitSet2)
    }

    @Benchmark
    fun boolListIntersect() {
        probe1.intersect(probe2)
    }

    @Benchmark
    fun bitSetIntersect() {
        probeBitSet1.intersect(probeBitSet2)
    }

    fun randomBools(len: Int): BooleanArray {
        val arr = BooleanArray(len)
        for (i in 0 until len) {
            arr[i] = Random.nextBoolean()
        }
        return arr
    }
}

