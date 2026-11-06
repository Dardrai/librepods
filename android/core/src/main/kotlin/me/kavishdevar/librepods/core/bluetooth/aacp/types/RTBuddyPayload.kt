package me.kavishdevar.librepods.core.bluetooth.aacp.types

sealed interface RTBuddyPayload {
    val descriptor: RTBuddyDescriptor
}

data class RemoteHidBuddyPayload(val data: ByteArray) : RTBuddyPayload {
    override val descriptor = RTBuddyDescriptor.SENSOR
}

data class UnknownBuddyPayload(
    override val descriptor: RTBuddyDescriptor,
    val descriptorValue: UInt,
    val data: ByteArray,
) : RTBuddyPayload
