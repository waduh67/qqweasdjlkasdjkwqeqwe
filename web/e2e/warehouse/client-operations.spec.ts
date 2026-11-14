import { expect, test, type Page } from '@playwright/test'
import { loan, purchase, warranty } from '../../src/api/warehouse/clientOperations'
import { pageOf, record, uuid } from '../../src/api/warehouse/codec'
import { sku } from '../../src/api/warehouse/models'
import { referencePosition } from '../../src/api/warehouse/reference'
import { login, selectNamed } from './helpers'
import { captureReference, referenceTenant } from './reference-setup'

async function save(page: Page, path: string) {
  await page.getByRole('button', { name: 'Tinjau tindakan', exact: true }).click()
  const response = page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

test('partial purchase receipt, approved loan, damaged return and replacement warranty preserve stock and BA', async ({ page }, info) => {
  test.setTimeout(240_000)
  const f = await referenceTenant(page, 'client-ops')
  const item = sku(await f.command('/api/v2/warehouse/skus', { code: 'ONU', name: 'ONU pinjaman', tracking: 'SERIAL', baseUnit: 'EA' }))
  const supplierId = uuid(record(await f.command('/api/v2/warehouse/suppliers', { code: 'VENDOR', name: 'Vendor QA' })).id)
  const admin = await f.createMember('Admin'), manager = await f.createMember('Manager')
  await f.activate()
  const po = purchase(await f.command('/api/v2/warehouse/purchases', { warehouseId: f.warehouse.id, supplierId, purchaseDate: '2026-11-14', reference: 'PO-QA', lines: [{ skuId: item.id, baseUnit: 'EA', quantityBase: '2' }] }, admin.headers))
  await login(page, admin.account)
  await page.goto('/warehouse/purchases?id=' + po.id)
  await page.getByRole('button', { name: 'Terima ONU pinjaman', exact: true }).click()
  await page.getByRole('textbox', { name: 'Jumlah diterima (unit)', exact: true }).fill('1')
  await page.getByRole('textbox', { name: 'Serial perangkat, satu per baris (serial, MAC opsional)', exact: true }).fill('QA-OLD')
  await save(page, '/api/v2/warehouse/purchases/' + po.id + '/receipts')
  expect(purchase(record(await f.get('/api/v2/warehouse/purchases/' + po.id)).document).state).toBe('PARTIALLY_RECEIVED')

  const submitted = loan(await f.command('/api/v2/warehouse/loans', { warehouseId: f.warehouse.id, borrowerName: 'PT Client QA', loanDate: '2026-11-14', lines: [{ skuId: item.id, baseUnit: 'EA', quantityBase: '1' }] }, admin.headers))
  const reviewed = loan(await f.command('/api/v2/warehouse/loans/' + submitted.id + '/review', { expectedRevision: 0, lines: [{ lineId: submitted.lines[0].id, approvedBase: '1' }] }, admin.headers))
  expect(reviewed.state).toBe('MANAGER_REVIEW')
  await f.command('/api/v2/warehouse/loans/' + submitted.id + '/decision', { expectedRevision: 1, approved: true }, manager.headers)
  await page.goto('/warehouse/loans?id=' + submitted.id)
  await page.getByRole('button', { name: 'Serahkan barang', exact: true }).click()
  await selectNamed(page, 'Stok ONU pinjaman', 'QA-OLD · 1 unit')
  await page.getByRole('textbox', { name: 'Bukti / catatan penyerahan', exact: true }).fill('BA diserahkan kepada PT Client QA')
  await save(page, '/api/v2/warehouse/loans/' + submitted.id + '/issue')
  const original = loan(await f.get('/api/v2/warehouse/loans/' + submitted.id + '/issue-snapshot'))
  await captureReference(page, info, 'loan-issued')
  await page.getByRole('button', { name: 'Terima kembali QA-OLD', exact: true }).click()
  await selectNamed(page, 'Kondisi kembali', 'Rusak / klaim garansi')
  await page.getByRole('textbox', { name: 'Serial yang dibaca dari perangkat', exact: true }).fill('QA-OLD')
  await page.getByRole('textbox', { name: 'Bukti / kondisi pengembalian', exact: true }).fill('Perangkat rusak setelah dipinjam')
  await save(page, '/api/v2/warehouse/loans/' + submitted.id + '/returns')
  const claim = pageOf(warranty)(await f.get('/api/v2/warehouse/warranties')).items[0]
  expect(claim?.serial).toBe('QA-OLD')
  if (!claim) throw new Error('Damaged return did not create a warranty')
  await page.goto('/warehouse/warranties?id=' + claim.id)
  await page.getByRole('button', { name: 'Kirim ke vendor', exact: true }).click()
  await selectNamed(page, 'Vendor / pemasok', 'Vendor QA')
  await page.getByRole('textbox', { name: 'Nomor klaim / surat jalan', exact: true }).fill('RMA-QA')
  await page.getByRole('textbox', { name: 'Bukti / catatan pengiriman', exact: true }).fill('Dikirim ke vendor')
  await save(page, '/api/v2/warehouse/warranties/' + claim.id + '/dispatch')
  await page.getByRole('button', { name: 'Terima hasil vendor', exact: true }).click()
  await selectNamed(page, 'Hasil vendor', 'Diganti perangkat baru')
  await page.getByRole('textbox', { name: 'Serial perangkat pengganti', exact: true }).fill('QA-NEW')
  await page.getByRole('textbox', { name: 'Bukti / hasil vendor', exact: true }).fill('Pengganti diterima')
  await save(page, '/api/v2/warehouse/warranties/' + claim.id + '/receive')
  await page.getByRole('button', { name: 'Periksa kelayakan', exact: true }).click()
  await selectNamed(page, 'Reset perangkat', 'Reset selesai dan telah diperiksa')
  await page.getByRole('textbox', { name: 'Bukti / hasil pemeriksaan', exact: true }).fill('Reset dan uji perangkat selesai')
  await save(page, '/api/v2/warehouse/warranties/' + claim.id + '/inspect')
  const positions = pageOf(referencePosition)(await f.get('/api/v2/warehouse/stock/' + item.id + '/positions?availableOnly=true')).items
  expect(positions).toHaveLength(1)
  expect(positions[0].serial).toBe('QA-NEW')
  expect(loan(await f.get('/api/v2/warehouse/loans/' + submitted.id + '/issue-snapshot'))).toEqual(original)
  await captureReference(page, info, 'warranty-completed')
})
