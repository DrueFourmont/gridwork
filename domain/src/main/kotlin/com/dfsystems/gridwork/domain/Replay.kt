package com.dfsystems.gridwork.domain

/**
 * A position in a sheet's change log.
 *
 * Zero means "before anything happened", which is why it is allowed here even
 * though [Version] refuses it: a version of zero would be a bug, but a
 * sequence of zero is a legitimate starting point.
 */
@JvmInline
value class Sequence(val value: Long) : Comparable<Sequence> {

    init {
        require(value >= 0) { "sequence must not be negative, was $value" }
    }

    override fun compareTo(other: Sequence): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        val NONE = Sequence(0)
    }
}

enum class ResyncReason {
    /** The client has never seen this sheet, so there is nothing to catch up on. */
    NO_CURSOR,

    /** More changes were missed than it is worth replaying. Refetch instead. */
    TOO_FAR_BEHIND,

    /**
     * The client claims to have seen a change that has not happened. Its
     * cursor is corrupt, or it is talking to a different database. Either way
     * it cannot be trusted to fill a gap.
     */
    CURSOR_AHEAD_OF_SERVER,
}

sealed interface ReplayDecision {
    /** Nothing was missed. Start streaming. */
    data object Live : ReplayDecision

    /** Send the changes after [from], then start streaming. */
    data class Replay(val from: Sequence, val missed: Long) : ReplayDecision

    /** Do not replay. Tell the client to refetch the sheet and start again. */
    data class Resync(val reason: ResyncReason) : ReplayDecision
}

/**
 * Decides what a reconnecting client is owed.
 *
 * Real time updates in this system are deliberately best effort: they go out
 * over Redis pub/sub after the transaction commits, and pub/sub delivers only
 * to whoever is listening at that moment. A client that was away has a hole.
 *
 * That is a safe design only because the hole is always recoverable. Every
 * cell write is already appended to cell_history with a monotonic id, so the
 * audit trail doubles as the replay log and no separate log had to be built.
 *
 * The alternative, guaranteeing delivery over the socket, would mean per
 * client queues that survive a pod restart, which is most of a message broker.
 * The outbox in Phase 4 exists for the events that genuinely cannot be missed.
 */
object ReplayRule {

    fun decide(lastSeen: Sequence?, latest: Sequence, maxReplay: Long): ReplayDecision {
        if (lastSeen == null) return ReplayDecision.Resync(ResyncReason.NO_CURSOR)
        if (lastSeen > latest) return ReplayDecision.Resync(ResyncReason.CURSOR_AHEAD_OF_SERVER)

        val missed = latest.value - lastSeen.value
        if (missed == 0L) return ReplayDecision.Live
        if (missed > maxReplay) return ReplayDecision.Resync(ResyncReason.TOO_FAR_BEHIND)
        return ReplayDecision.Replay(from = lastSeen, missed = missed)
    }

    /** Replaying more than this costs more than refetching the sheet. */
    const val DEFAULT_MAX_REPLAY = 500L
}
