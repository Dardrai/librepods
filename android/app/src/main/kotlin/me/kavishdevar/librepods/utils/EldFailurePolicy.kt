/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

internal data class EldEncoderHealth(val peer: List<Int>, val generation: Long, val failed: Boolean)

/** Only owns a fallback decision; the existing Bluetooth stack remains the audio owner. */
internal class EldFailurePolicy(private val read: () -> EldEncoderHealth?) {
    data class Owner(val peer: List<Int>, val generation: Long, val epoch: Long, val target: Any)
    data class Candidate(val peer: List<Int>, val epoch: Long, val target: Any)
    data class Tick(val keepPolling: Boolean, val fallback: Owner? = null)
    private var selection: Pair<List<Int>, Any>? = null
    private val codecs = mutableMapOf<List<Int>, Int>()
    private val connected = mutableSetOf<List<Int>>()
    private var epoch = 0L
    private var lastAttemptedGeneration = 0L
    private var retired: Candidate? = null
    private var retiredGeneration = 0L
    var owner: Owner? = null
        private set

    @Synchronized fun selected(peer: List<Int>?, target: Any?) {
        val next = peer?.takeIf(::validPeer)?.let { if (target == null) null else it.toList() to target }
        if (next != selection) { selection = next; epoch++; owner = null }
        refresh()
    }
    @Synchronized fun codec(peer: List<Int>, type: Int) {
        if (selection?.first == peer && codecs[peer] != type) { epoch++; retired = null }
        if (validPeer(peer)) codecs[peer.toList()] = type
        refresh()
    }
    @Synchronized fun changed() = refresh()
    @Synchronized fun connected(peer: List<Int>) { if (validPeer(peer)) connected.add(peer.toList()); refresh() }
    @Synchronized fun candidate(): Candidate? = selection?.takeIf {
        it.first in connected && codecs[it.first] == EldCodecIdentity.MODULE_CODEC_ID
    }?.let { Candidate(it.first, epoch, it.second) }?.takeUnless { it == retired }
    @Synchronized fun retire(expected: Candidate) { if (candidate() == expected) {
        retired = expected; retiredGeneration = runCatching(read).getOrNull()?.generation ?: 0; owner = null
    } }
    @Synchronized fun resumed(peer: List<Int>) { if (selection?.first == peer) retired = null; refresh() }
    @Synchronized fun disconnected(peer: List<Int>) {
        codecs.remove(peer)
        connected.remove(peer)
        if (selection?.first == peer) { selection = null; epoch++; owner = null }
    }
    @Synchronized fun clear() { selection = null; codecs.clear(); connected.clear(); epoch++; owner = null }

    private fun refresh() {
        val selected = selection
        val health = runCatching(read).getOrNull()
        if (health != null && health.generation > retiredGeneration && health.peer == selected?.first) retired = null
        owner = if (selected != null && selected.first in connected && codecs[selected.first] == EldCodecIdentity.MODULE_CODEC_ID &&
            health != null && health.peer == selected.first && validPeer(health.peer) &&
            health.generation > lastAttemptedGeneration)
            Owner(selected.first, health.generation, epoch, selected.second) else null
    }
    @Synchronized fun current(expected: Owner): Boolean {
        val health = runCatching(read).getOrNull()
        if (health == null) return false
        return selection == (expected.peer to expected.target) && epoch == expected.epoch &&
            codecs[expected.peer] == EldCodecIdentity.MODULE_CODEC_ID && health.peer == expected.peer &&
            health.generation == expected.generation && health.failed
    }
    @Synchronized fun tick(expected: Owner): Tick {
        if (owner != expected) return Tick(false)
        val health = runCatching(read).getOrNull()
        if (health == null || health.peer != expected.peer || health.generation != expected.generation) {
            refresh()
            return Tick(false)
        }
        if (!health.failed) return Tick(true)
        if (!current(expected)) { owner = null; return Tick(false) }
        lastAttemptedGeneration = expected.generation
        retired = candidate()
        retiredGeneration = expected.generation
        owner = null
        return Tick(false, expected)
    }
    private fun validPeer(peer: List<Int>) = peer.size == 6 && peer.all { it in 0..255 } && peer.any { it != 0 }
}
