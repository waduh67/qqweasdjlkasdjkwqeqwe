import { expect, test, type Page, type Route } from '@playwright/test'
import { installOltInventoryRoutes, olt } from './oltInventoryFixtures'

async function openInventoryDetail(page: Page) {
  await page.goto('/inventory')
  await page.getByRole('tab', { name: 'OLT', exact: true }).click()
  await page.getByText(olt.name, { exact: true }).click()
  await page.getByRole('tab', { name: 'ONU di OLT', exact: true }).click()
}

async function expectNoOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await expect.poll(() => page.locator('.blade-detail .blade-body').evaluateAll((elements) =>
    elements.every((element) => element.scrollWidth <= element.clientWidth),
  )).toBe(true)
}

for (const width of [375, 768, 1280]) {
  test('device inventory remains separate from customers at ' + width + 'px', async ({ page }, testInfo) => {
    const consoleErrors: string[] = []
    page.on('pageerror', (error) => consoleErrors.push(error.message))
    const fixture = await installOltInventoryRoutes(page)
    await page.setViewportSize({ width, height: 1000 })
    await openInventoryDetail(page)
    const panel = page.getByRole('region', { name: 'ONU di OLT', exact: true })
    await expect(panel.getByText('TEST001122AA', { exact: true })).toBeVisible()
    await expect(panel.getByText('TEST001122BB', { exact: true })).toBeAttached()
    await expect(panel.getByRole('status')).toHaveText('2 dari 2 ONU pada hasil baca ini')
    await expectNoOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-overview.png'), animations: 'disabled' })
    await panel.getByText('TEST001122AA', { exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-rows.png'), animations: 'disabled' })
    await panel.getByText('1970/01/12 19:22:12', { exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-optional-fields.png'), animations: 'disabled' })
    await expect(panel.getByText('1970/01/12 19:22:12', { exact: true })).toBeVisible()
    const search = panel.getByRole('searchbox', { name: 'Cari serial, nama, atau ONT ID' })
    await search.fill('test001122bb')
    await expect(panel.getByRole('status')).toHaveText('1 dari 2 ONU pada hasil baca ini')
    await expect(panel.getByText('TEST001122AA', { exact: true })).toHaveCount(0)
    await search.fill('NOT-IN-SNAPSHOT')
    await expect(panel.getByText('Tidak ditemukan pada hasil baca ini', { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-search-empty.png'), animations: 'disabled' })
    await search.fill('')
    fixture.setMode('error')
    await panel.getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('Gagal membaca ONU di OLT')
    await expect(panel.getByText('TEST001122AA', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-read-error.png'), animations: 'disabled' })
    fixture.setMode('empty')
    await panel.getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect(panel.getByRole('status')).toHaveText('0 dari 0 ONU pada hasil baca ini')
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-device-empty.png'), animations: 'disabled' })
    expect(fixture.readCount()).toBe(3)
    await page.getByRole('tab', { name: 'ONU Pelanggan', exact: true }).click()
    await expect(page.getByText('Belum ada ONU terpasang', { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('inventory-' + width + '-customer-empty.png'), animations: 'disabled' })
    await page.getByRole('tab', { name: 'ONU Baru', exact: true }).click()
    await expect(page.getByRole('tab', { name: 'ONU Baru', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expectNoOverflow(page)
    expect(consoleErrors).toEqual([])
  })
}

test('map detail uses the same live inventory tab', async ({ page }, testInfo) => {
  await installOltInventoryRoutes(page)
  await page.setViewportSize({ width: 1280, height: 1000 })
  await page.goto('/olts/' + olt.id)
  await page.getByRole('button', { name: 'Lihat di peta' }).click()
  await expect(page).toHaveURL(new RegExp('/map$'))
  await page.getByRole('button', { name: 'Buka detail', exact: true }).click()
  await page.getByRole('tab', { name: 'ONU di OLT', exact: true }).click()
  await expect(page.getByText('TEST001122AA', { exact: true })).toBeVisible()
  await expectNoOverflow(page)
  await page.screenshot({ path: testInfo.outputPath('map-detail-inventory.png'), animations: 'disabled' })
})

test('standalone detail does not offer device reads without permission', async ({ page }, testInfo) => {
  const fixture = await installOltInventoryRoutes(page, false)
  await page.goto('/olts/' + olt.id)
  await expect(page.getByRole('tab', { name: 'ONU Pelanggan', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'ONU di OLT', exact: true })).toHaveCount(0)
  expect(fixture.readCount()).toBe(0)
  await page.screenshot({ path: testInfo.outputPath('standalone-no-device-permission.png'), animations: 'disabled' })
})

test('standalone inventory shows loading then a keyboard-accessible snapshot', async ({ page }, testInfo) => {
  await installOltInventoryRoutes(page)
  await page.setViewportSize({ width: 1280, height: 1000 })
  let pendingRead: Route | undefined
  await page.route('**/api/monitoring/olts/' + olt.id + '/onus', (route) => { pendingRead = route })
  await page.goto('/olts/' + olt.id)
  await page.getByRole('tab', { name: 'ONU di OLT', exact: true }).focus()
  await page.screenshot({ path: testInfo.outputPath('standalone-before-read.png'), animations: 'disabled' })
  await page.keyboard.press('Enter')
  await expect(page.getByText('Membaca ONU dari OLT…', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeDisabled()
  await page.screenshot({ path: testInfo.outputPath('standalone-loading-start.png') })
  await page.waitForTimeout(100)
  await page.screenshot({ path: testInfo.outputPath('standalone-loading-mid.png') })
  if (!pendingRead) throw new Error('Device request was not issued')
  await pendingRead.fallback()
  await expect(page.getByText('TEST001122AA', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('standalone-populated.png'), animations: 'disabled' })
  await page.getByRole('searchbox', { name: 'Cari serial, nama, atau ONT ID' }).focus()
  await page.screenshot({ path: testInfo.outputPath('standalone-search-focus.png'), animations: 'disabled' })
  await expectNoOverflow(page)
})
