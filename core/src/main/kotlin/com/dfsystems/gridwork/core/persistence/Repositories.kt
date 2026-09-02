package com.dfsystems.gridwork.core.persistence

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {
    @Query("select u from UserEntity u where lower(u.email) = lower(:email)")
    fun findByEmailIgnoringCase(@Param("email") email: String): UserEntity?

    @Query("select count(u) > 0 from UserEntity u where lower(u.email) = lower(:email)")
    fun existsByEmailIgnoringCase(@Param("email") email: String): Boolean
}

@Repository
interface SheetRepository : JpaRepository<SheetEntity, UUID> {

    /**
     * One page of the sheets a user can see, newest first, keyset paginated.
     *
     * The cursor is (createdAt, id) rather than an offset. An offset would
     * re-scan every skipped row and would silently skip or repeat a sheet if
     * one were created while the caller was paging. See ADR 0005.
     *
     * Two queries rather than one with a nullable cursor parameter. The
     * combined version reads better but does not run: Postgres refuses a bare
     * null parameter in `:cursor is null` with "could not determine data type
     * of parameter", because nothing in the statement tells it what type that
     * placeholder is. Splitting it also produces simpler SQL on the hot path.
     */
    @Query(
        """
        select s from SheetEntity s
        where s.id in (select m.sheetId from SheetMemberEntity m where m.userId = :userId)
        order by s.createdAt desc, s.id desc
        """,
    )
    fun firstPageForUser(@Param("userId") userId: UUID, limit: Limit): List<SheetEntity>

    @Query(
        """
        select s from SheetEntity s
        where s.id in (select m.sheetId from SheetMemberEntity m where m.userId = :userId)
          and (s.createdAt < :cursorCreatedAt
               or (s.createdAt = :cursorCreatedAt and s.id < :cursorId))
        order by s.createdAt desc, s.id desc
        """,
    )
    fun nextPageForUser(
        @Param("userId") userId: UUID,
        @Param("cursorCreatedAt") cursorCreatedAt: Instant,
        @Param("cursorId") cursorId: UUID,
        limit: Limit,
    ): List<SheetEntity>
}

@Repository
interface SheetMemberRepository : JpaRepository<SheetMemberEntity, SheetMemberKey> {
    fun findBySheetIdAndUserId(sheetId: UUID, userId: UUID): SheetMemberEntity?
    fun findBySheetId(sheetId: UUID): List<SheetMemberEntity>
}

@Repository
interface ColumnRepository : JpaRepository<ColumnEntity, UUID> {
    fun findBySheetIdOrderByPositionAsc(sheetId: UUID): List<ColumnEntity>

    @Query("select coalesce(max(c.position), -1) from ColumnEntity c where c.sheetId = :sheetId")
    fun maxPosition(@Param("sheetId") sheetId: UUID): Int

    @Query("select count(c) > 0 from ColumnEntity c where c.sheetId = :sheetId and lower(c.name) = lower(:name)")
    fun existsByNameIgnoringCase(@Param("sheetId") sheetId: UUID, @Param("name") name: String): Boolean
}

@Repository
interface RowRepository : JpaRepository<RowEntity, UUID> {

    // Split for the same reason as the sheet queries above.
    @Query(
        """
        select r from RowEntity r
        where r.sheetId = :sheetId
        order by r.position asc
        """,
    )
    fun firstPageForSheet(@Param("sheetId") sheetId: UUID, limit: Limit): List<RowEntity>

    @Query(
        """
        select r from RowEntity r
        where r.sheetId = :sheetId and r.position > :cursorPosition
        order by r.position asc
        """,
    )
    fun nextPageForSheet(
        @Param("sheetId") sheetId: UUID,
        @Param("cursorPosition") cursorPosition: Long,
        limit: Limit,
    ): List<RowEntity>

    @Query("select coalesce(max(r.position), -1) from RowEntity r where r.sheetId = :sheetId")
    fun maxPosition(@Param("sheetId") sheetId: UUID): Long

    fun countBySheetId(sheetId: UUID): Long
}
