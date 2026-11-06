/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Bind the ALSA output to the board, rather than trusting a shared USB audio device type. */
internal object UsbAudioIdentity {
    fun matches(address: String, serial: String): Boolean = runCatching {
        val match = Regex("card=([0-9]{1,4});device=[0-9]{1,4};?").matchEntire(address) ?: return false
        val card = match.groupValues[1].toInt()
        var path: Path? = Paths.get("/sys/class/sound/card$card/device").toRealPath()
        repeat(8) {
            val current = path ?: return false
            if (!current.startsWith(Paths.get("/sys/devices"))) return false
            if (Files.isRegularFile(current.resolve("idVendor"))) {
                fun read(name: String) = String(Files.readAllBytes(current.resolve(name)), Charsets.US_ASCII).trim()
                return read("idVendor").equals("cafe", true) && read("idProduct").lowercase() in setOf("4005", "4015") &&
                    read("serial") == serial
            }
            path = current.parent
        }
        false
    }.getOrDefault(false)
}
