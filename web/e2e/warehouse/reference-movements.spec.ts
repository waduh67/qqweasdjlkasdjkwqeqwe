import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { pageOf } from '../../src/api/warehouse/codec'
import { location, sku } from '../../src/api/warehouse/models'
import { movementPosted, movementSummary } from '../../src/api/warehouse/referenceMovements'
import { referencePosition } from '../../src/api/warehouse/reference'
import { login } from './helpers'
import { captureReference, referenceTenant } from './reference-setup'

async function captureThemes(page: Page, info: TestInfo, name: string) {
  const theme = await page.locator('html').getAttribute('data-theme')
  await page.evaluate(() => {
    const body = document.querySelector('.resource-form-dialog .fui-DialogContent')
    if (body) body.scrollTop = 0
    window.scrollTo(0, 0)
  })
  await captureReference(page, info, theme + '-' + name)
  await page.evaluate(() => {
    const body = document.querySelector('.resource-form-dialog .fui-DialogContent')
    if (body) body.scrollTop = body.scrollHeight
    else window.scrollTo(0, document.documentElement.scrollHeight)
  })
  await captureReference(page, info, theme + '-' + name + '-end')
  await page.evaluate(() => {
    const body = document.querySelector('.resource-form-dialog .fui-DialogContent')
    if (body) body.scrollTop = 0
    window.scrollTo(0, 0)
  })
}
async function choose(page: Page, label: string, name: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click()
  await page.getByRole('option', { name, exact: true }).click()
}

for (const theme of ['light', 'dark']) test(theme + ' Admin receives and transfers exact stock with durable history, serial pagination, retry and Manager scope', async ({ page }, info) => {
  test.setTimeout(240_000)
  await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
  const fixture = await referenceTenant(page, 'movements')
  const destination = location(await fixture.command('/api/v2/warehouse/locations', { code: 'WH-B', name: 'Gudang tujuan', kind: 'WAREHOUSE', areaId: fixture.areaId, issueEligible: true }))
  const admin = await fixture.createMember('Admin'), manager = await fixture.createMember('Manager')
  await fixture.command('/api/v1/warehouse/settings/scopes/' + admin.user.id + '/' + destination.id, { expectedRevision: 0, active: true }, fixture.headers, 'PUT')
  const cable = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', inspectionRequired: false }))
  const onu = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'ONU', name: 'ONU pelanggan', tracking: 'SERIAL', baseUnit: 'EA', inspectionRequired: false }))
  await fixture.activate()
  await login(page, admin.account)
  await page.goto('/warehouse/receipts')
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
  await page.getByRole('button', { name: 'Penerimaan baru', exact: true }).click()
  await choose(page, 'Gudang tujuan', (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code)
  await choose(page, 'Barang 1', 'Kabel drop · DROP')
  await page.getByRole('textbox', { name: 'Panjang reel aktual (m)', exact: true }).fill('82,501')
  await page.getByRole('textbox', { name: 'Catatan penerimaan', exact: true }).fill('Kiriman langsung tersedia')
  await captureThemes(page, info, 'receipt-form')
  await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
  await expect(page.getByText('82,501 m', { exact: true })).toBeVisible()
  await captureThemes(page, info, 'receipt-review')
  const received = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v2/warehouse/receipts' && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  const receiptResponse = await received
  expect(receiptResponse.status()).toBe(201)
  const receipt = movementPosted(await receiptResponse.json())
  await expect(page.getByRole('region', { name: 'Barang tersimpan', exact: true }).getByRole('gridcell', { name: '82,501 m', exact: true })).toBeVisible()
  await expect(page.getByText('Kiriman langsung tersedia', { exact: true })).toBeVisible()
  await captureThemes(page, info, 'receipt-detail')
  expect(pageOf(movementSummary)(await fixture.get('/api/v2/warehouse/movements?kind=RECEIPT', admin.headers)).items[0]?.id).toBe(receipt.id)

  await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, lines: [{ skuId: onu.id, quantityBase: '31', serials: Array.from({ length: 31 }, (_, i) => ({ serial: 'MOVE-' + String(i + 1).padStart(3, '0') })) }] }, admin.headers)
  await page.goto('/warehouse/transfers')
  await page.getByRole('button', { name: 'Transfer baru', exact: true }).click()
  await choose(page, 'Gudang asal', (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code)
  await choose(page, 'Gudang tujuan', 'Gudang tujuan · WH-B')
  await choose(page, 'Barang transfer 1', 'Kabel drop · DROP')
  const stock = pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/stock/' + cable.id + '/positions?availableOnly=true&locationId=' + fixture.warehouse.id, admin.headers)).items[0]
  if (!stock) throw new Error('Receipt did not credit exact stock')
  await choose(page, 'Stok asal 1', ['Kabel drop', 'Tanpa serial', fixture.warehouse.name ?? fixture.warehouse.code, '82,501 m', stock.stockIdentityId.slice(-8)].join(' · '))
  await page.getByRole('textbox', { name: 'Jumlah dipindahkan (m)', exact: true }).fill('12,501')
  await captureThemes(page, info, 'transfer-form')
  await page.getByRole('button', { name: 'Tinjau transfer', exact: true }).click()
  await captureThemes(page, info, 'transfer-review')
  const attempts: { key: string | undefined; body: string | null }[] = []
  await page.route('**/api/v2/warehouse/transfers', async route => {
    attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
    if (attempts.length === 1) { await route.fetch(); await route.abort('failed') }
    else await route.continue()
  })
  await page.getByRole('button', { name: 'Pindahkan stok', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'Coba transaksi yang sama', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Barang tersimpan', exact: true }).getByRole('gridcell', { name: '12,501 m', exact: true })).toBeVisible()
  expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0])
  const transfers = pageOf(movementSummary)(await fixture.get('/api/v2/warehouse/movements?kind=TRANSFER', admin.headers))
  expect(transfers.totalElements).toBe(1)
  await captureThemes(page, info, 'transfer-detail')
  await page.unroute('**/api/v2/warehouse/transfers')
  await page.getByRole('button', { name: 'Kembali ke daftar', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Transfer baru', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Lihat ' + transfers.items[0]?.code, exact: true })).toBeVisible()
  await captureThemes(page, info, 'transfer-list')

  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, manager.account)
  await page.goto('/warehouse/receipts')
  await expect(page.getByRole('button', { name: 'Lihat RCV-' + receipt.id, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Penerimaan baru', exact: true })).toHaveCount(0)
  await captureThemes(page, info, 'manager-receipt-list')
  const receipts = pageOf(movementSummary)(await fixture.get('/api/v2/warehouse/movements?kind=RECEIPT', manager.headers))
  const serialReceipt = receipts.items.find(row => row.id !== receipt.id)
  if (!serialReceipt) throw new Error('Serial receipt missing from scoped history')
  await page.goto('/warehouse/receipts?id=' + serialReceipt.id)
  const lines = page.getByRole('region', { name: 'Barang tersimpan', exact: true })
  await expect(lines.getByText('31 entri · Halaman 1', { exact: true })).toBeVisible()
  await expect(lines.getByRole('row')).toHaveCount(26)
  await expect(lines.getByText('Total biaya input', { exact: true })).toHaveCount(0)
  await lines.getByRole('button', { name: 'Berikutnya', exact: true }).click()
  await expect(lines.getByText('31 entri · Halaman 2', { exact: true })).toBeVisible()
  await expect(lines.getByRole('row')).toHaveCount(7)
  await captureThemes(page, info, 'manager-serial-page')
  await page.goto('/warehouse/transfers')
  await expect(page.getByText('Belum ada transfer', { exact: true })).toBeVisible()
  await fixture.command('/api/v1/warehouse/settings/scopes/' + manager.user.id + '/' + destination.id, { expectedRevision: 0, active: true }, fixture.headers, 'PUT')
  await page.reload()
  await expect(page.getByRole('button', { name: 'Lihat ' + transfers.items[0]?.code, exact: true })).toBeVisible()
  await page.goto('/warehouse/transfers?id=' + transfers.items[0]?.id)
  await expect(lines.getByRole('gridcell', { name: '12,501 m', exact: true })).toBeVisible()
  await fixture.command('/api/v1/warehouse/settings/scopes/' + manager.user.id + '/' + fixture.warehouse.id, { expectedRevision: 1, active: false }, fixture.headers, 'PUT')
  await page.reload()
  await expect(page.getByText('Data belum berhasil dimuat', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Barang dalam dokumen', exact: true })).toHaveCount(0)
  await captureThemes(page, info, 'revoked-transfer')
})
