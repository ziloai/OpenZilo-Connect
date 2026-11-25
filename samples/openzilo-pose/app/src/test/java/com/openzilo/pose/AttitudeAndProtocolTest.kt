package com.openzilo.pose

import com.openzilo.connect.api.RingPostureKind
import com.openzilo.pose.render.OpenZiloHardwareMesh
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttitudeAndProtocolTest {

    @Test
    fun synthesizesPureClientPreviewPosturesAndProperMatrix() {
        val up = PoseViewModel.synthesizePreviewAttitude(PoseQuaternion.IDENTITY)
        assertEquals(RingPostureKind.HORIZONTAL_UPRIGHT, up.posture)
        assertEquals(0.0, up.tiltFromZenithDeg, 0.5)

        val flip = PoseViewModel.synthesizePreviewAttitude(PoseQuaternion(0.0, 1.0, 0.0, 0.0))
        assertEquals(RingPostureKind.HORIZONTAL_FLIPPED, flip.posture)
        assertEquals(180.0, flip.tiltFromZenithDeg, 0.5)
        assertEquals(180.0, abs(flip.rollDeg), 0.5)

        val m = flip.openGlMatrix
        val det = (m[1] * m[6] - m[2] * m[5]) * m[8] +
            (m[2] * m[4] - m[0] * m[6]) * m[9] +
            (m[0] * m[5] - m[1] * m[4]) * m[10]
        assertEquals(1f, det, 1e-5f)
    }

    @Test
    fun buildsValidOpenZiloHardwareMesh() {
        val mesh = OpenZiloHardwareMesh.build()
        val vertCount = mesh.vertexBuffer.capacity() / OpenZiloHardwareMesh.FLOATS_PER_VERTEX
        assertTrue(vertCount > 1500)
        assertTrue(mesh.indexCount > 6000)
        for (i in 0 until mesh.indexCount) {
            val idx = mesh.indexBuffer.get(i).toInt() and 0xFFFF
            assertTrue(idx in 0 until vertCount)
        }
    }

    @Test
    fun smoothlyReplays32SampleBatchesAt120HzWithoutStutter() {
        val buf = ImuTrajectoryBuffer()
        val omegaDegPerSec = 90.0
        var prevYaw = 0.0
        var maxStepDeg = 0.0
        var zeroFreezeFrames = 0

        // Simulate 4 BLE 0x0605 packets (each 32 samples @ 100Hz = 320ms) with 120Hz VSync frames in between
        for (batchIdx in 0 until 4) {
            val ts = LongArray(32)
            val quats = DoubleArray(32 * 4)
            var lastQ = PoseQuaternion.IDENTITY
            for (i in 0 until 32) {
                val sampleIdx = batchIdx * 32 + i
                val tSec = sampleIdx * 0.010
                ts[i] = (sampleIdx * 10).toLong()
                val rad = Math.toRadians(omegaDegPerSec * tSec)
                val q = PoseQuaternion(kotlin.math.cos(rad * 0.5), 0.0, 0.0, kotlin.math.sin(rad * 0.5))
                val o = i * 4
                quats[o] = q.w; quats[o + 1] = q.x; quats[o + 2] = q.y; quats[o + 3] = q.z
                lastQ = q
            }
            val att = PoseViewModel.synthesizePreviewAttitude(lastQ).copy(
                stationary = false,
                wzDps = omegaDegPerSec,
            )
            buf.pushBatch(ts, quats, att)

            // 38 frames at 120Hz (~316.7ms) + slight BLE jitter
            val framesInWindow = if (batchIdx % 2 == 0) 39 else 38
            for (f in 0 until framesInWindow) {
                val stepped = buf.step(1.0 / 120.0)
                val yaw = PoseViewModel.synthesizePreviewAttitude(stepped.q).yawDeg
                if (batchIdx > 0 || f > 0) {
                    val delta = yaw - prevYaw
                    assertTrue("Yaw must advance monotonically, got delta=$delta", delta > 0.25)
                    if (delta < 0.05) zeroFreezeFrames++
                    if (delta > maxStepDeg) maxStepDeg = delta
                }
                prevYaw = yaw
            }
        }
        assertEquals(0, zeroFreezeFrames)
        // Ideal 90 deg/s at 120Hz is 0.75 deg/frame; with PLL bounded <= 1.16x, every frame step < 1.0 deg!
        assertTrue("Expected < 1.0 deg per 120Hz frame, got $maxStepDeg", maxStepDeg < 1.0)
    }
}
