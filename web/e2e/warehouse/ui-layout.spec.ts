import { expect, test } from '@playwright/test'
import { signup } from './helpers'

// Playwright's worker-blocking init script throws in the intentionally opaque
// sandboxed email preview iframe. Match normal browser behavior for this audit.
test.use({ serviceWorkers: 'allow' })

test('operator pages remain readable and form controls fit the viewport', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  const admin = await signup(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const routes = ['/', '/customers', '/inventory', '/invoices', '/catalog', '/helpdesk', '/my-work-orders', '/my-materials', '/work-orders', '/warehouse', '/warehouse/catalog', '/warehouse/stock', '/warehouse/receipts', '/warehouse/requests', '/warehouse/transfers', '/warehouse/returns', '/warehouse/counts', '/warehouse/reports', '/monitoring', '/bras', '/acs', '/vpn', '/roles', '/users', '/areas', '/notifications', '/subscription']
  for (const route of routes) {
    await page.goto(route)
    await expect(page.getByRole('button', { name: 'Keluar', exact: true })).toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${route} page overflow`).toBeTruthy()
    if (route === '/subscription') {
      const renew = page.getByRole('button', { name: /^Perpanjang/ })
      await expect(renew).toBeVisible()
      const bounds = await renew.boundingBox()
      expect(bounds!.x).toBeGreaterThanOrEqual(0)
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
    }
    if (route === '/users' && page.viewportSize()!.width < 720) {
      const email = page.getByRole('gridcell', { name: admin.email, exact: true })
      await expect(email).toBeVisible()
      await expect(email.locator('.warehouse-mobile-label')).toBeVisible()
      const bounds = await email.boundingBox()
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
    }
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
  const name = page.getByLabel(/^Nama\s*\*?$/)
  await name.fill('Perubahan belum disimpan')
  await name.press('Escape')
  const confirmation = page.getByRole('dialog', { name: 'Tutup panel?' })
  await expect(confirmation).toBeVisible()
  const cancel = confirmation.getByRole('button', { name: 'Batal', exact: true })
  await cancel.focus()
  await expect(cancel).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(confirmation).not.toBeVisible()
  await name.focus()
  await expect(name).toBeFocused()
  await expect(name).toHaveValue('Perubahan belum disimpan')
  await name.press('Escape')
  await confirmation.getByRole('button', { name: 'Tutup tanpa simpan' }).click()
  await expect(page.locator('.azure-blade')).not.toBeVisible()
  expect(errors).toEqual([])
})
