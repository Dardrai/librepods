package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import org.junit.Assert.*
import org.junit.Test

class AACPManagerStateTest {
    private val id = ControlCommandIdentifiers.LISTENING_MODE

    @Test fun failedWriteDoesNotBecomeADeviceReport() {
        val manager = AACPManager()
        assertFalse(manager.sendControlCommand(id.value, 4))
        assertNull(manager.getControlCommandStatus(id))
        assertFalse(manager.sendPacket(byteArrayOf()))
    }

    @Test fun reportsReplaceOldValuesAndNotifyOnce() {
        val manager = AACPManager()
        var notifications = 0
        manager.registerControlCommandListener(id, object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) { notifications++ }
        })
        fun report(value: Byte) = manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, value, 0, 0, 0))
        report(2)
        report(4)
        assertEquals(2, notifications)
        assertEquals(1, manager.controlCommandStatusList.count { it.identifier == id })
        assertEquals(4, manager.getControlCommandStatus(id)!!.value[0].toInt())
        manager.disconnected()
        assertNull(manager.getControlCommandStatus(id))
        report(3)
        assertEquals(3, notifications)
    }

    @Test fun snapshotsCannotMutateReportedState() {
        val manager = AACPManager()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, 2, 0, 0, 0))
        val snapshot = manager.controlCommandStatusList
        snapshot.single().value[0] = 4
        assertEquals(2, manager.getControlCommandStatus(id)!!.value[0].toInt())
        manager.disconnected()
        assertEquals(1, snapshot.size)
        assertTrue(manager.controlCommandStatusList.isEmpty())
    }
}
