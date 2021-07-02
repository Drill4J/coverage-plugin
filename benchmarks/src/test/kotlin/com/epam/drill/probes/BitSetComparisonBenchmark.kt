package com.epam.drill.probes

import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import kotlinx.benchmark.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.*
import kotlin.random.*


@Warmup(iterations = 7, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(2)
class BitSetComparisonBenchmark {
    lateinit var probe1: List<Boolean>
    lateinit var probe2: List<Boolean>
    lateinit var probeBitSet1: Probes
    lateinit var probeBitSet2: Probes
    val len = 1000

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
    fun bitSetMerge(blh:Blackhole) {
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

