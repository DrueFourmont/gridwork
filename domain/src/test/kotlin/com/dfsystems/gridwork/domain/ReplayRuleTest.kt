package com.dfsystems.gridwork.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * What a client is owed when it reconnects, per CLAUDE.md's tests-first list.
 *
 * Live updates are best effort: they travel over Redis pub/sub after commit,
 * and pub/sub drops messages for anyone not currently listening. So a client
 * that was disconnected for ten seconds has a hole in its view, and the only
 * honest options are to fill the hole or to admit it and start again.
 *
 * The rule decides which. It is pure arithmetic over sequence numbers, so it
 * is tested here rather than against a socket.
 */
class ReplayRuleTest {

    @Test
    fun `a client that missed nothing just goes live`() {
        ReplayRule.decide(lastSeen = Sequence(100), latest = Sequence(100), maxReplay = 500)
            .shouldBeInstanceOf<ReplayDecision.Live>()
    }

    @Test
    fun `a client that missed a few changes is sent them`() {
        val decision = ReplayRule.decide(lastSeen = Sequence(90), latest = Sequence(100), maxReplay = 500)
        decision.shouldBeInstanceOf<ReplayDecision.Replay>()
        decision.from shouldBe Sequence(90)
        decision.missed shouldBe 10
    }

    @Test
    fun `a client that missed more than the replay limit is told to resync`() {
        // Past some point, replaying is slower than refetching the sheet, and
        // it also means holding a lot of history in memory to serve one
        // reconnect. Refusing is cheaper for both sides.
        val decision = ReplayRule.decide(lastSeen = Sequence(1), latest = Sequence(5000), maxReplay = 500)
        decision.shouldBeInstanceOf<ReplayDecision.Resync>()
        decision.reason shouldBe ResyncReason.TOO_FAR_BEHIND
    }

    @Test
    fun `a client at exactly the replay limit is still replayed`() {
        ReplayRule.decide(lastSeen = Sequence(100), latest = Sequence(600), maxReplay = 500)
            .shouldBeInstanceOf<ReplayDecision.Replay>()
    }

    @Test
    fun `a client one past the replay limit is resynced`() {
        ReplayRule.decide(lastSeen = Sequence(100), latest = Sequence(601), maxReplay = 500)
            .shouldBeInstanceOf<ReplayDecision.Resync>()
    }

    @Test
    fun `a first time client with no sequence at all is resynced, not replayed`() {
        // It has no data, so there is nothing to catch up on. Sending it the
        // whole history would be a strange way to load a sheet.
        val decision = ReplayRule.decide(lastSeen = null, latest = Sequence(100), maxReplay = 500)
        decision.shouldBeInstanceOf<ReplayDecision.Resync>()
        decision.reason shouldBe ResyncReason.NO_CURSOR
    }

    @Test
    fun `a client claiming a sequence from the future is resynced, not trusted`() {
        // It cannot have seen a change that has not happened. Either it is
        // talking to a different database, or its cursor is corrupt. Serving
        // it live updates from here would leave it permanently wrong.
        val decision = ReplayRule.decide(lastSeen = Sequence(9000), latest = Sequence(100), maxReplay = 500)
        decision.shouldBeInstanceOf<ReplayDecision.Resync>()
        decision.reason shouldBe ResyncReason.CURSOR_AHEAD_OF_SERVER
    }

    @Test
    fun `an empty sheet with a fresh client is live, not a resync`() {
        // Nothing has ever happened, so there is nothing to miss.
        ReplayRule.decide(lastSeen = Sequence(0), latest = Sequence(0), maxReplay = 500)
            .shouldBeInstanceOf<ReplayDecision.Live>()
    }

    @Test
    fun `sequence rejects a negative value`() {
        shouldThrow<IllegalArgumentException> { Sequence(-1) }
    }

    @Test
    fun `sequences compare in order`() {
        (Sequence(5) < Sequence(6)) shouldBe true
        (Sequence(6) < Sequence(5)) shouldBe false
    }
}
