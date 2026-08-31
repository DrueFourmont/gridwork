package com.dfsystems.gridwork.api.service

import com.dfsystems.gridwork.api.persistence.SheetEntity
import com.dfsystems.gridwork.api.persistence.SheetMemberRepository
import com.dfsystems.gridwork.api.persistence.SheetRepository
import com.dfsystems.gridwork.api.web.ForbiddenException
import com.dfsystems.gridwork.api.web.NotFoundException
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.SheetRole
import com.dfsystems.gridwork.domain.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The one place that answers "may this user do this to this sheet".
 *
 * Every service goes through here. Scattering the check across controllers is
 * how one endpoint ends up missing it.
 */
@Service
class AccessService(
    private val sheets: SheetRepository,
    private val members: SheetMemberRepository,
) {

    @Transactional(readOnly = true)
    fun require(sheetId: SheetId, userId: UserId, permission: (SheetRole) -> Boolean): SheetEntity {
        val sheet = sheets.findById(sheetId.value).orElseThrow {
            NotFoundException("Sheet $sheetId was not found.")
        }
        val membership = members.findBySheetIdAndUserId(sheetId.value, userId.value)
            // A sheet the caller cannot see is reported as absent, not as
            // forbidden. Otherwise the 403 confirms the sheet exists, and the
            // API becomes a way to enumerate other people's sheet ids.
            ?: throw NotFoundException("Sheet $sheetId was not found.")
        if (!permission(membership.role)) {
            throw ForbiddenException("Your role on this sheet (${membership.role}) does not allow that.")
        }
        return sheet
    }

    @Transactional(readOnly = true)
    fun requireRead(sheetId: SheetId, userId: UserId): SheetEntity =
        require(sheetId, userId) { it.canRead() }

    @Transactional(readOnly = true)
    fun requireWriteCells(sheetId: SheetId, userId: UserId): SheetEntity =
        require(sheetId, userId) { it.canWriteCells() }

    @Transactional(readOnly = true)
    fun requireStructure(sheetId: SheetId, userId: UserId): SheetEntity =
        require(sheetId, userId) { it.canChangeStructure() }

    @Transactional(readOnly = true)
    fun requireShare(sheetId: SheetId, userId: UserId): SheetEntity =
        require(sheetId, userId) { it.canShare() }
}
