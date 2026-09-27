import { expect, test } from '@playwright/test'
import { signup } from './helpers'

// Playwright's worker-blocking init script throws in the intentionally opaque
// sandboxed email preview iframe. Match normal browser behavior for this audit.
test.use({ serviceWorkers: 'allow' })

test('operator pages remain readable and form controls fit the viewport', async ({ page }, testInfo) => {
  test.setTimeout(360_000)
  const admin = await signup(page)
  await page.goto('/customers')
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: testInfo.outputPath('customers-empty.png'), fullPage: true, animations: 'disabled' })
  // Populate through the real form so layout review also covers readable rows
  // and the complete create -> list -> detail interaction, in the owned local tenant.
  for (const [name, phone, address] of [
    ['Budi Santoso', '081234567801', 'Jl. Cihampelas No. 12, Bandung'],
    ['Siti Aminah', '081234567802', 'Jl. Sukajadi No. 35, Bandung'],
    ['Kantor Karuhun', '0225550123', 'Jl. Asia Afrika No. 108, Bandung'],
  ]) {
    await page.getByRole('button', { name: 'Tambah pelanggan', exact: true }).click()
    await page.getByLabel(/^Nama\s*\*?$/).fill(name)
    await page.getByLabel('Telepon', { exact: true }).fill(phone)
    await page.getByLabel(/^Alamat\s*\*?$/).fill(address)
    const response = page.waitForResponse(response => response.url().endsWith('/api/customers') && response.request().method() === 'POST')
    await page.getByRole('button', { name: 'Simpan', exact: true }).click()
    expect((await response).ok()).toBeTruthy()
    await expect(page.getByRole('button', { name, exact: true })).toBeVisible()
  }
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const routes = ['/', '/customers', '/inventory', '/invoices', '/catalog', '/helpdesk', '/my-work-orders', '/my-materials', '/work-orders', '/warehouse', '/warehouse/catalog', '/warehouse/stock', '/warehouse/receipts', '/warehouse/requests', '/warehouse/transfers', '/warehouse/returns', '/warehouse/counts', '/warehouse/reports', '/monitoring', '/bras', '/acs', '/vpn', '/roles', '/users', '/areas', '/notifications', '/subscription', '/express-psb', '/import-customers', '/import-pppoe', '/hotspot', '/network-provisioning', '/provisioning', '/incidents', '/my-visits', '/audit', '/payment-gateway', '/tax-settings', '/reports', '/account/security', '/warehouse/approvals', '/warehouse/replenishment', '/warehouse/provenance', '/warehouse/settings']
  for (const route of routes) {
    await page.goto(route)
    await expect(page.getByRole('button', { name: 'Keluar', exact: true })).toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(new URL(page.url()).pathname, `${route} reached its own page`).toBe(route)
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
      const grid = page.getByRole('region', { name: 'Tabel, geser untuk melihat kolom lain' })
      await grid.evaluate(element => { element.scrollLeft = element.scrollWidth })
      const action = page.getByRole('button', { name: 'Aksi baris' }).first()
      await expect(action).toBeInViewport()
      await grid.evaluate(element => { element.scrollLeft = 0 })
    }
    await page.screenshot({ path: testInfo.outputPath(`${route === '/' ? 'dashboard' : route.slice(1).replaceAll('/', '-')}.png`), fullPage: true, animations: 'disabled' })
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
  await page.screenshot({ path: testInfo.outputPath('customer-form.png'), fullPage: true, animations: 'disabled' })
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
  await expect(name).toBeFocused()
  await expect(name).toHaveValue('Perubahan belum disimpan')
  await name.press('Escape')
  await confirmation.getByRole('button', { name: 'Tutup tanpa simpan' }).click()
  await expect(page.locator('.azure-blade')).not.toBeVisible()
  await page.getByRole('button', { name: 'Budi Santoso', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Budi Santoso', exact: true })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await expect(page.getByText('Belum ditentukan', { exact: true })).toBeVisible()
  if (page.viewportSize()!.width < 820) await page.locator('.azure-blade').getByRole('button', { name: 'Aksi lainnya' }).click()
  await expect(page.getByRole(page.viewportSize()!.width < 820 ? 'menuitem' : 'button', { name: 'Lihat di peta', exact: true })).toBeDisabled()
  if (page.viewportSize()!.width < 820) await page.keyboard.press('Escape')
  await page.evaluate(async () => { await Promise.allSettled(document.getAnimations().filter(animation => animation.effect?.getComputedTiming().iterations !== Infinity).map(animation => animation.finished)) })
  await page.screenshot({ path: testInfo.outputPath('customer-detail.png'), fullPage: true, animations: 'disabled' })
  expect(errors).toEqual([])
})
