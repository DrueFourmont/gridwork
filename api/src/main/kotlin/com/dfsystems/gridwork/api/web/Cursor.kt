package com.dfsystems.gridwork.api.web

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Opaque pagination cursors, per ADR 0005.
 *
 * Opaque on purpose. A client that decodes a cursor and constructs its own is a
 * client that breaks the day the sort order changes. Base64 is not security,
 * it is a fence: it makes the contents obviously not part of the contract.
 */
object Cursor {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encodeRow(position: Long): String = encode(position.toString())

    fun decodeRow(cursor: String?): Long? {
        if (cursor.isNullOrBlank()) return null
        return decode(cursor).toLongOrNull()
            ?: throw UnprocessableException("The cursor is not valid. Omit it to start from the beginning.")
    }

    fun encodeSheet(createdAt: Instant, id: UUID): String = encode("${createdAt.toEpochMilli()}:$id")

    fun decodeSheet(cursor: String?): Pair<Instant, UUID>? {
        if (cursor.isNullOrBlank()) return null
        val parts = decode(cursor).split(":", limit = 2)
        if (parts.size != 2) throw invalidSheetCursor()
        val millis = parts[0].toLongOrNull() ?: throw invalidSheetCursor()
        val id = try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            throw invalidSheetCursor()
        }
        return Instant.ofEpochMilli(millis) to id
    }

    private fun invalidSheetCursor() =
        UnprocessableException("The cursor is not valid. Omit it to start from the beginning.")

    private fun encode(raw: String): String = encoder.encodeToString(raw.toByteArray())

    private fun decode(cursor: String): String = try {
        String(decoder.decode(cursor))
    } catch (_: IllegalArgumentException) {
        throw UnprocessableException("The cursor is not valid. Omit it to start from the beginning.")
    }
}
