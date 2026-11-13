package com.kei.pulse.ui

import com.kei.pulse.data.GlobalTuning.Companion.gpuFloorLevelFor
import com.kei.pulse.data.GlobalTuning.Companion.gpuFloorMhz
import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningInForceTest {
    private val cpu = policy(0, listOf(600000, 1000000, 1400000, 2000000))
    private val gpu = policy(CpuPolicyInfo.GPU_POLICY_ID, listOf(160000, 222000, 350000, 525000, 740000, 832000, 1000000))

    private fun policy(id: Int, frequencies: List<Int>) = CpuPolicyInfo(
        id = id,
        policyPath = "policy$id",
        scalingMaxPath = "policy$id/max",
        currentMaxFreq = frequencies.last(),
        selectableMaxFreq = frequencies.last(),
        observedMaxFreq = frequencies.last(),
        minFreq = frequencies.first(),
        supportedFrequencies = frequencies,
    )

    @Test
    fun `stock caps after restart do not match saved Custom caps`() {
        assertEquals(TuningInForce.NotApplied, capsInForce(listOf(cpu), mapOf(0 to 1400000), mapOf(0 to 2000000)))
        assertEquals(TuningInForce.Applied, capsInForce(listOf(cpu), mapOf(0 to 1400000), mapOf(0 to 1400000)))
    }

    @Test
    fun `kernel snapping to nearest OPP matches but adjacent OPP does not`() {
        assertEquals(TuningInForce.Applied, capsInForce(listOf(cpu), mapOf(0 to 1350000), mapOf(0 to 1400000)))
        assertEquals(TuningInForce.NotApplied, capsInForce(listOf(cpu), mapOf(0 to 1350000), mapOf(0 to 1000000)))
        assertEquals(TuningInForce.NotApplied, capsInForce(listOf(cpu), mapOf(0 to 1350000), mapOf(0 to 1399999)))
    }

    @Test
    fun `a vendor ceiling above the top OPP satisfies an uncapped target but not a lower cap`() {
        assertEquals(TuningInForce.Applied, capsInForce(listOf(cpu), mapOf(0 to 2000000), mapOf(0 to 2100000)))
        assertEquals(TuningInForce.NotApplied, capsInForce(listOf(cpu), mapOf(0 to 1400000), mapOf(0 to 2100000)))
    }

    @Test
    fun `both equally near OPPs match a midpoint request`() {
        for (live in listOf(1000000, 1400000)) {
            assertEquals(TuningInForce.Applied, capsInForce(listOf(cpu), mapOf(0 to 1200000), mapOf(0 to live)))
        }
    }

    @Test
    fun `all CPU and GPU caps must match`() {
        val expected = mapOf(cpu.id to 1000000, gpu.id to 525000)
        assertEquals(TuningInForce.Applied, capsInForce(listOf(cpu, gpu), expected, expected))
        assertEquals(TuningInForce.NotApplied, capsInForce(listOf(cpu, gpu), expected, expected + (gpu.id to 1000000)))
    }

    @Test
    fun `missing policies caps or readback cannot confirm application`() {
        assertEquals(TuningInForce.Unverified, capsInForce(emptyList(), emptyMap(), emptyMap()))
        assertEquals(TuningInForce.Unverified, capsInForce(listOf(cpu), emptyMap(), mapOf(0 to 1400000)))
        assertEquals(TuningInForce.Unverified, capsInForce(listOf(cpu), mapOf(0 to 1400000), emptyMap()))
    }

    @Test
    fun `percent maps to closest frequency and reversed power level`() {
        val expected = mapOf(0 to 160, 25 to 222, 50 to 525, 75 to 740)
        for ((percent, mhz) in expected) {
            val level = gpuFloorLevelFor(gpu, percent)
            assertEquals(mhz, gpuFloorMhz(gpu, level))
            assertEquals(TuningInForce.Applied, gpuFloorInForce(gpu, percent, level))
        }
    }

    @Test
    fun `floor reset makes the saved chip not applied`() {
        assertEquals(TuningInForce.NotApplied, gpuFloorInForce(gpu, 50, gpuFloorLevelFor(gpu, 0)))
        assertEquals(TuningInForce.Applied, gpuFloorInForce(gpu, 50, gpuFloorLevelFor(gpu, 50)))
    }

    @Test
    fun `floor mapping handles different GPU tables and ties`() {
        val otherGpu = policy(CpuPolicyInfo.GPU_POLICY_ID, listOf(220000, 350000, 500000, 615000, 750000, 900000))
        assertEquals(220, gpuFloorMhz(otherGpu, gpuFloorLevelFor(otherGpu, 25)))
        assertEquals(500, gpuFloorMhz(otherGpu, gpuFloorLevelFor(otherGpu, 50)))
        assertEquals(615, gpuFloorMhz(otherGpu, gpuFloorLevelFor(otherGpu, 75)))
        val tied = policy(CpuPolicyInfo.GPU_POLICY_ID, listOf(300000, 600000, 900000))
        assertEquals(300, gpuFloorMhz(tied, gpuFloorLevelFor(tied, 50)))
    }

    @Test
    fun `missing or invalid floor readback stays unverified`() {
        assertEquals(TuningInForce.Unverified, gpuFloorInForce(null, 50, 0))
        assertEquals(TuningInForce.Unverified, gpuFloorInForce(gpu.copy(supportedFrequencies = emptyList()), 50, 0))
        for (level in listOf(null, -1, gpu.supportedFrequencies.size)) {
            assertNull(gpuFloorMhz(gpu, level))
            assertEquals(TuningInForce.Unverified, gpuFloorInForce(gpu, 50, level))
        }
    }

    @Test
    fun `clamped floor matches the writer ceiling`() {
        assertEquals(TuningInForce.Applied, gpuFloorInForce(gpu, 75, 5, 5))
        assertEquals(TuningInForce.NotApplied, gpuFloorInForce(gpu, 75, 6, 5))
        assertEquals(TuningInForce.Unverified, gpuFloorInForce(gpu, 75, 5, null))
    }

    @Test
    fun `other clock owners hide global status for both matches and mismatches`() {
        for (status in TuningInForce.entries) {
            assertEquals(status, globalTuningStatus(status, ClockOwner.Free))
            for (owner in listOf(ClockOwner.GlobalAutoTdp, ClockOwner.BoundGame(false), ClockOwner.BoundGame(true))) {
                assertNull(globalTuningStatus(status, owner))
                assertEquals("75% - 740 MHz", gpuFloorChipLabel(gpu, 75, globalTuningStatus(status, owner)))
            }
        }
    }
}
