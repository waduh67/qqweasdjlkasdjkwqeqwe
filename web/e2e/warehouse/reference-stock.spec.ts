import { expect, test } from '@playwright/test'
import { sku } from '../../src/api/warehouse/models'
import { referenceHistory } from '../../src/api/warehouse/referenceStock'
import { pageOf } from '../../src/api/warehouse/codec'
import { login } from './helpers'
import { captureReference, referenceTenant } from './reference-setup'

async function captureStock(page: Parameters<typeof captureReference>[0], info: Parameters<typeof captureReference>[1], name: string) {
  await page.evaluate(() => window.scrollTo(0, 0))
  await captureReference(page, info, name)
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await captureReference(page, info, name + '-end')
  await page.evaluate(() => window.scrollTo(0, 0))
}

test('reference stock separates warehouse quantities and pages serial positions and actor history within default role scope', async ({ page }, info) => {
  const fixture = await referenceTenant(page, 'stock')
  const admin = await fixture.createMember('Admin'), manager = await fixture.createMember('Manager')
  const cable = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', minimumQuantityBase: '82500', inspectionRequired: false }))
  const onu = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'ONU', name: 'ONU pelanggan', tracking: 'SERIAL', baseUnit: 'EA', inspectionRequired: false }))
  await fixture.activate()
  await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, notes: 'Kiriman kabel presisi', lines: [{ skuId: cable.id, quantityBase: '82501' }] }, admin.headers)
  await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, notes: 'Serial datang', lines: [{ skuId: onu.id, quantityBase: '31', serials: Array.from({ length: 31 }, (_, i) => ({ serial: 'PAGE-' + String(i + 1).padStart(3, '0') })) }] }, admin.headers)
  const legacy: string[] = []
  page.on('request', request => { const path = new URL(request.url()).pathname; if (path.startsWith('/api/v1/warehouse/')) legacy.push(path) })
  await login(page, admin.account)
  await page.goto('/warehouse/stock?skuId=' + cable.id)
  await expect(page.getByRole('heading', { name: 'Stok & Riwayat', exact: true })).toBeVisible()
  const balance = page.getByRole('region', { name: 'Saldo per gudang', exact: true })
  await expect(balance.getByRole('gridcell', { name: '82,501 m', exact: true })).toBeVisible()
  await expect(balance.getByText('Cukup', { exact: true })).toBeVisible()
  const positions = page.getByRole('region', { name: 'Posisi stok', exact: true })
  await expect(positions.getByRole('gridcell', { name: '82,501 m', exact: true })).toBeVisible()
  for (const theme of ['light', 'dark']) {
    const change = page.getByRole('button', { name: theme === 'light' ? 'Ganti ke tema terang' : 'Ganti ke tema gelap', exact: true })
    if (await change.isVisible()) await change.click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(balance.getByRole('gridcell', { name: '82,501 m', exact: true })).toBeVisible()
    await captureStock(page, info, theme + '-cable-stock')
  }
  await page.getByRole('tab', { name: 'Riwayat', exact: true }).click()
  const history = page.getByRole('region', { name: 'Riwayat stok', exact: true })
  await expect(history.getByRole('gridcell', { name: admin.account.name, exact: true })).toBeVisible()
  await expect(history.getByRole('gridcell', { name: 'Masuk 82,501 m', exact: true })).toBeVisible()
  await captureStock(page, info, 'cable-history')
  const events = pageOf(referenceHistory)(await fixture.get('/api/v2/warehouse/stock/' + cable.id + '/history', manager.headers))
  expect(events.items[0]?.notes).toBe('Kiriman kabel presisi')

  await page.goto('/warehouse/stock?skuId=' + onu.id)
  await expect(balance.getByRole('gridcell', { name: '31 unit', exact: true })).toBeVisible()
  await expect(positions.getByText('31 entri · Halaman 1', { exact: true })).toBeVisible()
  await expect(positions.getByRole('row')).toHaveCount(26)
  await positions.getByRole('button', { name: 'Berikutnya', exact: true }).click()
  await expect(positions.getByText('31 entri · Halaman 2', { exact: true })).toBeVisible()
  await expect(positions.getByRole('row')).toHaveCount(7)
  await captureStock(page, info, 'serial-second-page')
  await positions.getByRole('textbox', { name: 'Cari posisi', exact: true }).fill('PAGE-031')
  await expect(positions.getByRole('gridcell', { name: 'PAGE-031', exact: true })).toBeVisible()
  await expect(positions.getByRole('row')).toHaveCount(2)
  await positions.getByRole('combobox', { name: 'Pemegang stok', exact: true }).selectOption('TECHNICIAN')
  await expect(positions.getByText('Belum ada posisi stok', { exact: true })).toBeVisible()
  await captureStock(page, info, 'technician-empty-filter')
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, manager.account)
  await page.goto('/warehouse/stock?skuId=' + cable.id)
  await expect(balance.getByRole('gridcell', { name: '82,501 m', exact: true })).toBeVisible()
  await captureStock(page, info, 'manager-stock')
  await fixture.command('/api/v1/warehouse/settings/scopes/' + manager.user.id + '/' + fixture.warehouse.id, { expectedRevision: 1, active: false }, fixture.headers, 'PUT')
  await page.reload()
  await expect(balance.getByRole('gridcell', { name: '82,501 m', exact: true })).toHaveCount(0)
  await expect(positions.getByText('Belum ada posisi stok', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'Riwayat', exact: true }).click()
  await expect(history.getByText('Belum ada riwayat stok', { exact: true })).toBeVisible()
  expect(legacy.filter(path => !path.startsWith('/api/v1/warehouse/settings/scopes/'))).toEqual([])
})
