package com.dfsystems.gridwork.api.realtime

import com.dfsystems.gridwork.domain.ResyncReason

/**
 * The wire protocol. Raw JSON over a websocket, not STOMP.
 *
 * STOMP brings a broker abstraction, frames, subscriptions, and a client
 * library, none of which this needs: there is one channel per sheet and four
 * message types. Plain JSON is simpler to explain, simpler to test with a
 * plain websocket client, and has no version skew between server and browser.
 *
 * Every message carries a `type` so a client can switch on one field.
 */

/** Client to server. */
sealed interface Inbound {
    /**
     * Must be the first frame. The token is sent in the body rather than in a
     * query parameter, because query strings end up in access logs and proxy
     * logs, and a bearer token in a log is a credential in a log.
     */
    data class Authenticate(val token: String, val sheetId: String, val lastSeen: Long?) : Inbound
}

/** Server to client. */
sealed interface Outbound {
    val type: String

    /** Authenticated and streaming. Nothing was missed. */
    data class Ready(val sheetId: String, val sequence: Long) : Outbound {
        override val type = "ready"
    }

    /** Changes the client missed while it was away, sent before it goes live. */
    data class Replayed(val changes: List<CellChanged>, val sequence: Long) : Outbound {
        override val type = "replayed"
    }

    /**
     * The gap is too large or the cursor is untrustworthy. Refetch the sheet.
     * Saying so is better than pretending, because a client silently missing
     * changes looks fine and is wrong.
     */
    data class Resync(val reason: ResyncReason, val sequence: Long) : Outbound {
        override val type = "resync"
    }

    /** One cell changed. The payload is everything a grid needs to apply it. */
    data class CellChanged(
        val sheetId: String,
        val rowId: String,
        val columnId: String,
        val value: String?,
        val version: Long,
        val sequence: Long,
        /** So a client can ignore the echo of its own write. */
        val changedBy: String,
    ) : Outbound {
        override val type = "cellChanged"
    }

    data class Failed(val reason: String) : Outbound {
        override val type = "error"
    }
}
