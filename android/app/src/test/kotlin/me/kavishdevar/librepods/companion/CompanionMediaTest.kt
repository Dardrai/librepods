package me.kavishdevar.librepods.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionMediaTest {
    private val quiet = CompanionMediaSample(known = true, available = true)
    private val playing = CompanionMediaSample(known = true, playing = true, available = true, player = "one:session")

    @Test fun reconnectBaselinesAnAlreadyPlayingPlayerWithoutReplayingItsStart() {
        val state = CompanionMedia()
        assertEquals(0L, state.observe(playing).event)
        assertEquals(0L, state.observe(playing).event)
        state.observe(quiet)
        assertEquals(1L, state.observe(playing).event)
    }

    @Test fun notificationsUnknownAndUnavailablePlayersCannotCreateAnEvent() {
        val state = CompanionMedia()
        state.observe(quiet)
        assertEquals(0L, state.observe(quiet.copy(connected = true)).event)
        state.observe(CompanionMediaSample())
        assertEquals(0L, state.observe(playing).event)
        state.observe(quiet)
        assertEquals(0L, state.observe(playing.copy(available = false)).event)
        assertEquals(0L, state.observe(playing).event)
        state.observe(quiet)
        assertEquals(1L, state.observe(playing).event)
    }

    @Test fun ourOwnRouteChangeKeepsTheProofButPauseAndPlayerChangeRetireIt() {
        val state = CompanionMedia()
        state.observe(quiet)
        val start = state.observe(playing)
        assertEquals(start.generation, state.observe(playing.copy(connected = true, available = false)).generation)
        val pause = state.observe(quiet)
        assertNotEquals(start.generation, pause.generation)
        val resume = state.observe(playing)
        assertEquals(start.event + 1, resume.event)
        assertNotEquals(start.generation, resume.generation)
        assertEquals(resume.event + 1, state.observe(playing.copy(player = "two:session")).event)
    }

    @Test fun anObservedStartMayWaitBrieflyForRenderButCannotReplayAStartupOrAnExpiredStart() {
        val baseline = CompanionMedia()
        baseline.observe(playing.copy(eligible = false), 0)
        assertEquals(0L, baseline.observe(playing, 500).event)
        val state = CompanionMedia()
        state.observe(quiet, 0)
        state.observe(playing.copy(eligible = false), 100)
        assertEquals(1L, state.observe(playing, 500).event)
        state.observe(quiet, 1000)
        state.observe(playing.copy(eligible = false), 1100)
        assertEquals(1L, state.observe(playing, 3200).event)
    }
}
