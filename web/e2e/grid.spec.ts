import { expect, test } from '@playwright/test'
import { API, createAccount, createSheetWithGrid, signIn } from './support'

test.describe('grid editing', () => {
  test('an edit is visible immediately and survives a reload', async ({ page, request }) => {
    const account = await createAccount(request)
    const { columnId, rowId } = await createSheetWithGrid(request, account, 'Edit test')
    await signIn(page, account)

    await page.goto('/')
    await page.getByTestId('open-sheet-Edit test').click()

    const cell = page.getByTestId(`cell-${rowId}-${columnId}`)
    await expect(cell).toBeVisible()
    await expect(cell).toHaveAttribute('data-version', '1')

    await cell.dblclick()
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).fill('written from the ui')
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

    // Optimistic: the value is on screen before the server has answered.
    await expect(cell).toHaveText('written from the ui')

    // And it really was saved, which a reload proves and an optimistic update
    // alone does not.
    await page.reload()
    await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText('written from the ui')
    await expect(page.getByTestId(`cell-${rowId}-${columnId}`)).toHaveAttribute('data-version', '2')
  })

  test('a rejected value rolls back and the grid does not lie about it', async ({
    page,
    request,
  }) => {
    // A NUMBER column refuses "not a number" with a 422. The cell must go back
    // to what it was, because a grid showing a value that was never saved is
    // worse than an error.
    const account = await createAccount(request)
    const headers = { Authorization: `Bearer ${account.token}` }
    const sheet = await request.post(`${API}/sheets`, { headers, data: { name: 'Rollback test' } })
    const { id: sheetId } = (await sheet.json()) as { id: string }
    const column = await request.post(`${API}/sheets/${sheetId}/columns`, {
      headers,
      data: { name: 'Amount', type: 'NUMBER' },
    })
    const { id: columnId } = (await column.json()) as { id: string }
    const row = await request.post(`${API}/sheets/${sheetId}/rows`, { headers })
    const { id: rowId } = (await row.json()) as { id: string }

    await signIn(page, account)
    await page.goto('/')
    await page.getByTestId('open-sheet-Rollback test').click()

    const cell = page.getByTestId(`cell-${rowId}-${columnId}`)
    await cell.dblclick()
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).fill('definitely not a number')
    await page.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

    await expect(page.getByTestId('write-error')).toBeVisible()
    await expect(cell).toHaveText('')
  })

  test('signing out returns to the login screen', async ({ page, request }) => {
    const account = await createAccount(request)
    await signIn(page, account)
    await page.goto('/')

    await page.getByTestId('sign-out').click()

    await expect(page.getByTestId('email')).toBeVisible()
  })
})
