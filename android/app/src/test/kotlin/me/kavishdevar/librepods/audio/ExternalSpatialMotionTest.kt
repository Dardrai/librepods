package me.kavishdevar.librepods.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSpatialMotionTest {
    @Test fun keepsSensorTimeAndBluetoothReceiptSeparate() {
        val rotation = listOf(1f, 2f, -3f)
        val angularVelocity = listOf(4f, -5f, 6f)
        val pose = SpatialPose(1_000, 42, rotation, angularVelocity, 10)
        val frame = spatialMotionFrame(pose, 2_000)
        assertEquals(48, frame.size)
        val input = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, input.int)
        assertEquals(42, input.int)
        assertEquals(1_000L, input.long)
        assertEquals(2_000L, input.long)
        assertEquals(rotation, List(3) { input.float })
        assertEquals(angularVelocity, List(3) { input.float })
    }
}
