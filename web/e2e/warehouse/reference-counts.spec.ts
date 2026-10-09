import { expect, test, type Page } from '@playwright/test'
import { pageOf } from '../../src/api/warehouse/codec'
import { sku } from '../../src/api/warehouse/models'
import { referencePosition } from '../../src/api/warehouse/reference'
import { referenceCount } from '../../src/api/warehouse/referenceCounts'
import { login } from './helpers'
import { captureReferenceDocument as capture, referenceTenant } from './reference-setup'

async function choose(page: Page, label: string, name: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click()
  await page.getByRole('option', { name, exact: true }).click()
}
async function load(page: Page, material: string, location: string) {
  await page.getByRole('button', { name: 'Opname baru', exact: true }).click()
  await choose(page, 'Barang opname', material)
  await choose(page, 'Lokasi opname', location)
  await page.getByRole('button', { name: 'Muat saldo opname', exact: true }).click()
  await page.getByRole('button', { name: 'Muat saldo', exact: true }).click()
  await expect(page.getByRole('textbox', { name: /Jumlah fisik/ })).toBeVisible()
}
async function saved(page: Page, label = 'Simpan opname') {
  const pending = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v2/warehouse/counts' && response.request().method() === 'POST')
  await page.getByRole('button', { name: label, exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(201)
  return referenceCount(await response.json())
}

for (const theme of ['light', 'dark']) {
  test(theme + ' physical cable count uses exact variance and a lost response cannot adjust stock twice', async ({ page }, info) => {
    test.setTimeout(300_000)
    await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
    const fixture = await referenceTenant(page, 'counts')
    const admin = await fixture.createMember('Admin'), manager = await fixture.createMember('Manager')
    const technician = await fixture.createMember('Teknisi FO')
    const material = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', inspectionRequired: false }))
    await fixture.activate()
    await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, lines: [{ skuId: material.id, quantityBase: '82501' }] }, admin.headers)
    for (const authority of [manager.headers, technician.headers]) expect((await page.request.get('/api/v2/warehouse/counts/locations', { headers: authority })).status()).toBe(403)
    await login(page, admin.account)
    await page.goto('/warehouse/counts')
    await expect(page.getByText('Belum ada opname', { exact: true })).toBeVisible()
    await capture(page, info, 'empty-counts')
    const location = (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code
    await load(page, 'Kabel drop · DROP', location)
    await expect(page.getByRole('textbox', { name: 'Jumlah fisik (m)', exact: true })).toHaveValue('')
    await page.getByRole('textbox', { name: 'Jumlah fisik (m)', exact: true }).fill('70,000')
    await page.getByRole('textbox', { name: 'Alasan opname', exact: true }).fill('Hitungan kabel akhir hari')
    await capture(page, info, 'physical-cable-form')
    await page.getByRole('button', { name: 'Tinjau hasil opname', exact: true }).click()
    await expect(page.getByText('-12,501 m', { exact: true })).toBeVisible()
    await capture(page, info, 'cable-variance-review')
    const attempts: { readonly key: string | undefined; readonly body: string | null }[] = []
    await page.route('**/api/v2/warehouse/counts', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
      if (attempts.length === 1) { expect((await route.fetch()).status()).toBe(201); await route.abort('failed') }
      else await route.continue()
    })
    await page.getByRole('button', { name: 'Simpan opname', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeDisabled()
    await capture(page, info, 'uncertain-count-save')
    const audit = await saved(page, 'Coba transaksi yang sama')
    await expect(page.getByRole('region', { name: 'Audit opname', exact: true })).toBeVisible()
    await page.unroute('**/api/v2/warehouse/counts')
    expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0])
    expect(audit.snapshot.bookBase).toBe('82501'); expect(audit.physicalBase).toBe('70000'); expect(audit.differenceBase).toBe('-12501')
    expect(audit.movementIds).toHaveLength(1)
    await expect(page.getByRole('button', { name: 'Opname baru', exact: true })).toHaveCount(0)
    await capture(page, info, 'saved-cable-audit')
    const stock = pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/stock/' + material.id + '/positions?availableOnly=true', admin.headers))
    expect(stock.items.reduce((sum, item) => sum + BigInt(item.quantityBase), 0n)).toBe(70000n)
    expect(pageOf(referenceCount)(await fixture.get('/api/v2/warehouse/counts', admin.headers)).totalElements).toBe(1)
    await page.getByRole('button', { name: 'Kembali ke daftar', exact: true }).click()
    await load(page, 'Kabel drop · DROP', location)
    await page.getByRole('textbox', { name: 'Jumlah fisik (m)', exact: true }).fill('0')
    await page.getByRole('textbox', { name: 'Alasan opname', exact: true }).fill('Tidak ditemukan kabel fisik')
    await page.getByRole('button', { name: 'Tinjau hasil opname', exact: true }).click()
    const zero = await saved(page)
    await expect(page.getByRole('region', { name: 'Audit opname', exact: true })).toBeVisible()
    expect(zero.snapshot.bookBase).toBe('70000'); expect(zero.physicalBase).toBe('0'); expect(zero.differenceBase).toBe('-70000')
    await capture(page, info, 'zero-stock-audit')
  })

  test(theme + ' equal count serial swap records both changes and a stale snapshot requires a reload', async ({ page }, info) => {
    test.setTimeout(300_000)
    await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
    const fixture = await referenceTenant(page, 'serialcounts')
    const admin = await fixture.createMember('Admin')
    const material = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'ONT', name: 'ONT pelanggan', tracking: 'SERIAL', baseUnit: 'EA', inspectionRequired: false }))
    await fixture.activate()
    await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, lines: [{ skuId: material.id, quantityBase: '1', serials: [{ serial: 'ONT-OLD' }] }] }, admin.headers)
    await login(page, admin.account)
    await page.goto('/warehouse/counts')
    const location = (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code
    await load(page, 'ONT pelanggan · ONT', location)
    await page.getByRole('textbox', { name: 'Jumlah fisik (unit)', exact: true }).fill('1')
    await page.getByRole('textbox', { name: 'Serial fisik', exact: true }).fill('ONT-NEW, 02:00:11:22:33:44')
    await page.getByRole('textbox', { name: 'Alasan opname', exact: true }).fill('Serial aktual berbeda dari saldo buku')
    await capture(page, info, 'serial-swap-form')
    await page.getByRole('button', { name: 'Tinjau hasil opname', exact: true }).click()
    const audit = await saved(page)
    expect(audit.differenceBase).toBe('0'); expect(audit.movementIds).toHaveLength(2)
    await expect(page.getByRole('gridcell', { name: 'ONT-NEW', exact: true })).toBeVisible()
    await capture(page, info, 'serial-swap-audit')
    await page.getByRole('button', { name: 'Kembali ke daftar', exact: true }).click()
    await load(page, 'ONT pelanggan · ONT', location)
    await page.getByRole('textbox', { name: 'Jumlah fisik (unit)', exact: true }).fill('1')
    await page.getByRole('textbox', { name: 'Serial fisik', exact: true }).fill('ONT-NEW')
    await page.getByRole('textbox', { name: 'Alasan opname', exact: true }).fill('Snapshot sebelum penerimaan tambahan')
    await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, lines: [{ skuId: material.id, quantityBase: '1', serials: [{ serial: 'ONT-ARRIVED' }] }] }, admin.headers)
    await page.getByRole('button', { name: 'Tinjau hasil opname', exact: true }).click()
    const failed = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v2/warehouse/counts' && response.request().method() === 'POST')
    await page.getByRole('button', { name: 'Simpan opname', exact: true }).click()
    expect((await failed).status()).toBe(409)
    await expect(page.getByRole('button', { name: 'Muat ulang dokumen', exact: true })).toBeVisible()
    await capture(page, info, 'stale-snapshot-review')
    await page.getByRole('button', { name: 'Muat ulang dokumen', exact: true }).click()
    await expect(page.getByRole('combobox', { name: 'Barang opname', exact: true })).toBeVisible()
    expect(pageOf(referenceCount)(await fixture.get('/api/v2/warehouse/counts', admin.headers)).totalElements).toBe(1)
    const positions = pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/stock/' + material.id + '/positions?availableOnly=true', admin.headers))
    expect(positions.items.map(item => item.serial).sort()).toEqual(['ONT-ARRIVED', 'ONT-NEW'])
    await capture(page, info, 'reload-count-selection')
  })
}
