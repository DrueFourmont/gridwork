package com.dfsystems.gridwork.api.persistence

import com.dfsystems.gridwork.domain.ColumnType
import com.dfsystems.gridwork.domain.SheetRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * JPA entities for the structural tables: users, sheets, membership, columns,
 * and rows. These are read and written one or a few at a time, so an ORM is a
 * good fit.
 *
 * Cells deliberately have no entity. They are written in bulk with a versioned
 * conditional UPDATE that JPA cannot express, and read a page at a time. See
 * CellRepository and docs/PLAN-SUMMARY.md, which calls for plain SQL there.
 *
 * `@Version` is used only on sheets, columns, and rows. It is JPA's own
 * optimistic locking, and it happens to line up exactly with the version rule
 * in ADR 0001.
 */
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(name = "display_name", nullable = false)
    var displayName: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "sheets")
class SheetEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var name: String = "",

    @Version
    @Column(nullable = false)
    var version: Long = 1,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

class SheetMemberKey(
    var sheetId: UUID = UUID.randomUUID(),
    var userId: UUID = UUID.randomUUID(),
) : Serializable {
    override fun equals(other: Any?): Boolean =
        other is SheetMemberKey && sheetId == other.sheetId && userId == other.userId

    override fun hashCode(): Int = 31 * sheetId.hashCode() + userId.hashCode()
}

@Entity
@Table(name = "sheet_members")
@IdClass(SheetMemberKey::class)
class SheetMemberEntity(
    @Id
    @Column(name = "sheet_id", nullable = false)
    var sheetId: UUID = UUID.randomUUID(),

    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: SheetRole = SheetRole.VIEWER,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "columns")
class ColumnEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "sheet_id", nullable = false)
    var sheetId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: ColumnType = ColumnType.TEXT,

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Version
    @Column(nullable = false)
    var version: Long = 1,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "rows")
class RowEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "sheet_id", nullable = false)
    var sheetId: UUID = UUID.randomUUID(),

    @Column(name = "position", nullable = false)
    var position: Long = 0,

    @Version
    @Column(nullable = false)
    var version: Long = 1,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
