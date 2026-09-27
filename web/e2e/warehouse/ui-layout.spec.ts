import { expect, test } from '@playwright/test'
import { signup } from './helpers'

test('operator pages remain readable and form controls fit the viewport', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  await signup(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const routes = ['/', '/customers', '/inventory', '/invoices', '/catalog', '/helpdesk', '/my-work-orders', '/my-materials', '/work-orders', '/warehouse', '/warehouse/catalog', '/warehouse/stock', '/warehouse/receipts', '/warehouse/requests', '/warehouse/transfers', '/warehouse/returns', '/warehouse/counts', '/warehouse/reports', '/monitoring', '/bras', '/acs', '/vpn', '/roles', '/users', '/areas', '/notifications', '/subscription']
  for (const route of routes) {
    await page.goto(route)
    await expect(page.getByRole('button', { name: 'Keluar', exact: true })).toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${route} page overflow`).toBeTruthy()
    await page.screenshot({ path: testInfo.outputPath(`${route === '/' ? 'dashboard' : route.slice(1).replaceAll('/', '-')}.png`), fullPage: true })
  }
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Tambah pelanggan', exact: true }).click()
  await expect(page.getByLabel(/^Nama\s*\*?$/)).toBeVisible()
  for (const field of await page.locator('.azure-blade input:visible, .azure-blade select:visible, .azure-blade textarea:visible').all()) {
    await field.scrollIntoViewIfNeeded()
    const bounds = await field.boundingBox()
    expect(bounds, 'field has a layout box').not.toBeNull()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  }
  await page.screenshot({ path: testInfo.outputPath('customer-form.png'), fullPage: true })
  expect(errors).toEqual([])
})
