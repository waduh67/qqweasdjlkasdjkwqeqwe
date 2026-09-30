import { expect, test, type Page } from '@playwright/test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { signup } from './helpers'
import { addLocation, addSku, addSupplier, setupOwnArea } from './catalog'

const execute = promisify(execFile)
const clockFixture = fileURLToPath(new URL('../../../scripts/warehouse/draft-clock-fixture.sh', import.meta.url))
async function select(page: Page, name: string, label: string) {
  const control = page.getByRole('combobox', { name, exact: true })
  await expect(control.getByRole('option', { name: label, exact: true })).toBeAttached()
  await control.selectOption({ label })
}

test('real deadline rejects an open receipt editor and retains expired history without stock', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const admin = await signup(page)
  const area = await setupOwnArea(page, admin)
  const quarantine = await addLocation(page, { code: 'QA', name: 'Pemeriksaan barang', area: area.optionLabel, kind: 'QUARANTINE' })
  const source = await addLocation(page, { code: 'RECEIPT_SOURCE', name: 'Penerimaan pemasok', area: area.optionLabel, kind: 'TRANSIT' })
  const sku = await addSku(page, { code: 'CONNECTOR', name: 'Konektor', tracking: 'BULK' })
  const supplier = await addSupplier(page, 'DISTRIB', 'Distributor material')
  await page.goto('/warehouse/receipts')
  await page.getByRole('button', { name: 'Buat penerimaan', exact: true }).click()
  await page.getByRole('textbox', { name: 'Referensi surat jalan', exact: true }).fill('DRAFT-EXPIRY-ORIGINAL')
  await select(page, 'Pemasok', `${supplier.name} · ${supplier.code}`)
  await select(page, 'Batas penerimaan', source.label)
  await select(page, 'Lokasi pemeriksaan', quarantine.label)
  await select(page, 'Barang 1', `${sku.name} · ${sku.code}`)
  await page.getByRole('textbox', { name: 'Jumlah aktual (unit)', exact: true }).fill('3')
  await page.getByRole('textbox', { name: 'Kode lot / reel', exact: true }).fill('EXPIRY-LOT')
  await page.getByRole('button', { name: 'Tinjau draft', exact: true }).click()
  await execute('bash', [clockFixture, 'policy', admin.email, '12'], { timeout: 30_000 })
  const created = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/receipts' && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan draft', exact: true }).click()
  const original = await created
  expect(original.status()).toBe(201)
  const draft = await original.json()
  expect(draft).toMatchObject({ revision: 0, state: 'DRAFT', externalReference: 'DRAFT-EXPIRY-ORIGINAL' })
  await page.getByRole('button', { name: 'Ubah draft', exact: true }).click()
  await page.getByRole('textbox', { name: 'Referensi surat jalan', exact: true }).fill('LATE-UNSAVED-CHANGE')
  await execute('bash', [clockFixture, 'await', draft.id], { timeout: 30_000 })
  await page.getByRole('button', { name: 'Tinjau draft', exact: true }).click()
  const rejected = page.waitForResponse(res => new URL(res.url()).pathname === `/api/v1/warehouse/receipts/${draft.id}` && res.request().method() === 'PUT')
  await page.getByRole('button', { name: 'Simpan draft', exact: true }).click()
  const rejection = await rejected
  expect(rejection.status()).toBe(409)
  expect(await rejection.json()).toMatchObject({ code: 'DRAFT_EXPIRED' })
  await expect(page.getByRole('alert')).toContainText('kedaluwarsa')
  const refreshed = page.waitForResponse(res => new URL(res.url()).pathname === `/api/v1/warehouse/receipts/${draft.id}` && res.request().method() === 'GET')
  await page.getByRole('button', { name: 'Muat ulang dokumen', exact: true }).click()
  const current = await (await refreshed).json()
  expect(current).toMatchObject({ id: draft.id, revision: 0, state: 'EXPIRED', externalReference: 'DRAFT-EXPIRY-ORIGINAL', draftExpiry: { reason: 'IDLE_DEADLINE' } })
  expect(draft.lines).toHaveLength(1)
  expect(current.lines).toEqual([expect.objectContaining({
    id: draft.lines[0].id, skuId: sku.id, quantityBase: '3', lotCode: 'EXPIRY-LOT',
    acceptedBase: '0', rejectedBase: '0', putawayBase: '0', pieces: [],
  })])
  const detail = page.getByRole('region', { name: 'Detail penerimaan', exact: true })
  await expect(detail.getByRole('heading', { name: 'DRAFT-EXPIRY-ORIGINAL', exact: true })).toBeVisible()
  await expect(detail.getByRole('status').filter({ hasText: 'Draf kedaluwarsa sejak' })).toContainText('Riwayat tetap tersimpan')
  await expect(page.getByRole('button', { name: 'Ubah draft', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Terima barang', exact: true })).toHaveCount(0)
  await page.getByText('Riwayat penerimaan', { exact: true }).click()
  await expect(page.getByRole('list', { name: 'Riwayat dokumen' }).getByRole('listitem')).toHaveCount(1)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: testInfo.outputPath('expired-receipt-history.png'), fullPage: true })
  await page.getByRole('link', { name: 'Kembali ke daftar penerimaan', exact: true }).click()
  await page.getByRole('combobox', { name: 'Status penerimaan', exact: true }).selectOption('EXPIRED')
  await expect(page.getByRole('link', { name: 'DRAFT-EXPIRY-ORIGINAL', exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Buat penerimaan', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Draft penerimaan baru', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
})
