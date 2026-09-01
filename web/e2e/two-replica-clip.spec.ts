import { expect, test } from '@playwright/test'
import { createAccount, createSheetWithGrid, signIn } from './support'

/**
 * Records the Phase 3 deliverable: two browsers, two different API replicas,
 * one sheet.
 *
 * The two pages are pointed at different ports, 5173 and 5174 in the Vite
 * config's terms, which proxy to replica one and replica two. Nothing is
 * shared between those replicas except Postgres and Redis, so an edit crossing
 * between them has demonstrably gone through Redis pub/sub.
 *
 * Run with: npm run clip
 */
test.use({ viewport: { width: 1280, height: 720 } })

// Videos have to be requested per context. Playwright records the contexts it
// creates for you through the `page` fixture, and these two are made by hand,
// so recordVideo is passed explicitly or nothing is captured.
const VIDEO_DIR = '../docs/clips'

test('two replicas, one sheet, live', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const account = await createAccount(request)
  const { columnId, rowId } = await createSheetWithGrid(request, account, 'Two replicas')

  const left = await browser.newContext({
    baseURL: 'http://localhost:5173',
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: VIDEO_DIR, size: { width: 1280, height: 720 } },
  })
  const right = await browser.newContext({
    baseURL: 'http://localhost:5174',
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: VIDEO_DIR, size: { width: 1280, height: 720 } },
  })
  // 5174 is a second Vite server proxying to the second API replica, so these
  // two browsers are genuinely talking to different processes.
  const leftPage = await left.newPage()
  const rightPage = await right.newPage()

  try {
    await signIn(leftPage, account)
    await signIn(rightPage, account)
    await leftPage.goto('/')
    await rightPage.goto('/')
    await leftPage.getByTestId('open-sheet-Two replicas').click()
    await rightPage.getByTestId('open-sheet-Two replicas').click()

    await expect(leftPage.getByTestId('live-status')).toHaveAttribute('data-status', 'live')
    await expect(rightPage.getByTestId('live-status')).toHaveAttribute('data-status', 'live')
    await leftPage.waitForTimeout(1200)

    await leftPage.getByTestId(`cell-${rowId}-${columnId}`).dblclick()
    await leftPage
      .getByTestId(`cell-input-${rowId}-${columnId}`)
      .pressSequentially('typed on replica one', { delay: 60 })
    await leftPage.getByTestId(`cell-input-${rowId}-${columnId}`).press('Enter')

    await expect(rightPage.getByTestId(`cell-${rowId}-${columnId}`)).toHaveText(
      'typed on replica one',
      { timeout: 10_000 },
    )
    await rightPage.waitForTimeout(1500)
  } finally {
    // Videos are only flushed to disk when the context closes.
    await left.close()
    await right.close()
    const leftVideo = await leftPage.video()?.path()
    const rightVideo = await rightPage.video()?.path()
    console.log('CLIP replica one:', leftVideo)
    console.log('CLIP replica two:', rightVideo)
  }
})
