import { expect, type Page, type TestInfo } from '@playwright/test'

/** The customer drawer scrolls independently; a full-page image alone misses its history. */
export async function captureAssetHistory(page: Page, testInfo: TestInfo, prefix: string) {
  // Let transient upload confirmations expire normally before recording the
  // persistent history. Do not hide or dismiss UI elements for a screenshot.
  await expect(page.locator('.toast-host .toast')).toHaveCount(0)
  const cards = page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true }).getByRole('article')
  const count = await cards.count()
  expect(count).toBeGreaterThan(0)
  for (let index = 0; index < count; index++) {
    const card = cards.nth(index)
    await card.getByText(/^Dipasang /).scrollIntoViewIfNeeded()
    const fits = await card.evaluate(element => {
      const bounds = element.getBoundingClientRect()
      return bounds.left >= 0 && bounds.right <= window.innerWidth
    })
    expect(fits).toBeTruthy()
    await page.screenshot({ path: testInfo.outputPath(`${prefix}-episode-${index + 1}.png`), fullPage: true })
  }
}
