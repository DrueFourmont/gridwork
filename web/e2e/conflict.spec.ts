import { expect, test } from '@playwright/test'
import { API, createAccount, createSheetWithGrid, signIn, suppressLiveUpdates } from './support'

/**
 * The 409, end to end, with a real second writer.
 *
 * The browser edits a cell. A separate API call, standing in for another
 * person, has already moved that cell on. The conflict dialog is the payoff
 * for everything ADR 0001 describes.
 *
 * Live updates are suppressed in these tests, and that is the point rather
 * than a workaround. Since Phase 3 a connected browser usually sees the other
 * person's change arrive and simply edits the new version, so no conflict
 * happens at all. Conflicts are now the case where the live update did not get
 * through, and that is the case worth testing.
 */
test('a cell changed by someone else produces a merge prompt, not a silent overwrite', async ({
  page,
  request,
}) => {
  const account = await createAccount(request)
  const { sheetId, columnId, rowId } = await createSheetWithGrid(request, account, 'Conflict test')
  const headers = { Authorization: `Bearer ${account.token}` }

  await suppressLiveUpdates(page)
  await signIn(page, account)
  await page.goto('/')
  await page.getByTestId('open-sheet-Conflict test').click()

  const cell = page.getByTestId(`cell-${rowId}-${columnId}`)
  await expect(cell).toHaveAttribute('data-version', '1')

  // Somebody else writes the same cell while this browser is looking at
  // version 1. The browser has no way to know yet.
  const theirWrite = await request.patch(`${API}/sheets/${sheetId}/cells:batchUpdate`, {
    headers,
    data: { updates: [{ rowId, columnId, value: 'their change', expectedVersion: 1 }] },
  })
  expect(theirWrite.status()).toBe(200)

  await cell.dblclick()
  await page.getByTestId(`cell-input-${rowId}-${columnId}`).fill('my change')
  await page.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

  const dialog = page.getByTestId('conflict-dialog')
  await expect(dialog).toBeVisible()
  await expect(page.getByTestId('conflict-theirs')).toHaveText('their change')
  await expect(page.getByTestId('conflict-mine')).toHaveText('my change')

  // Keeping theirs must leave the other person's value in place.
  await page.getByTestId('conflict-keep-theirs').click()
  await expect(dialog).not.toBeVisible()
  await expect(cell).toHaveText('their change')

  await page.reload()
  await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText('their change')
})

test('keeping mine retries at the version the server reported and wins', async ({
  page,
  request,
}) => {
  const account = await createAccount(request)
  const { sheetId, columnId, rowId } = await createSheetWithGrid(request, account, 'Keep mine test')
  const headers = { Authorization: `Bearer ${account.token}` }

  await suppressLiveUpdates(page)
  await signIn(page, account)
  await page.goto('/')
  await page.getByTestId('open-sheet-Keep mine test').click()

  await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveAttribute('data-version', '1')

  await request.patch(`${API}/sheets/${sheetId}/cells:batchUpdate`, {
    headers,
    data: { updates: [{ rowId, columnId, value: 'theirs', expectedVersion: 1 }] },
  })

  await page.getByTestId(`cell-${rowId}-${columnId}`).dblclick()
  await page.getByTestId(`cell-input-${rowId}-${columnId}`).fill('mine')
  await page.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

  await expect(page.getByTestId('conflict-dialog')).toBeVisible()
  await page.getByTestId('conflict-keep-mine').click()

  // The retry expects version 2, which the conflict told the client about.
  // Without adopting that version this would conflict forever.
  await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText('mine')
  await page.reload()
  await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText('mine')
  await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveAttribute('data-version', '3')
})
