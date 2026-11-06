package me.kavishdevar.librepods.core.bluetooth.aacp

internal fun sourceFeatureCapabilitiesPayload(hearing: Boolean): ByteArray =
    byteArrayOf(0xd7.toByte(), if (hearing) 2 else 0, 0, 0, 0, 0, 0, 0)

internal fun macSourceAnnouncementPayload(): ByteArray = byteArrayOf(2, 0, 1, 0, 0x10)
