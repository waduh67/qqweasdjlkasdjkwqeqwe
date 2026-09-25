import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { login } from '../warehouse/helpers'
import { addLocation, addSku } from '../warehouse/catalog'
import { setupDiscrepancyApprover } from '../warehouse/approvals'
import { confirmOperation, selectNamed } from '../warehouse/fulfillment'
import { switchUser } from '../warehouse/numeric-journey'
import { legacyPhase, readLegacyFixture, saveLegacyFixture, type LegacyFixture } from './fixture'

async function customerHistory(page: Page, fixture: LegacyFixture, screenshot?: string) {
  await page.goto('/customers')
  const read = page.waitForResponse(res => new URL(res.url()).pathname === `/api/customers/${fixture.customer.id}`)
  await page.getByRole('grid', { name: 'Tabel data', exact: true }).getByRole('button', { name: fixture.customer.name, exact: true }).click()
  const customer = await (await read).json()
  expect(customer).toMatchObject({ id: fixture.customer.id, name: fixture.customer.name,
    onus: [expect.objectContaining({ id: fixture.onu.id, serialNumber: fixture.onu.serialNumber })] })
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true }))
    .toContainText('1 perangkat lama belum memiliki asal gudang yang terverifikasi')
  await expect(page.getByRole('button', { name: 'Daftarkan ONU', exact: true })).toHaveCount(0)
  if (screenshot) {
    await page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: screenshot, fullPage: true })
  }
  const panel = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Detail pelanggan', exact: true }) })
  await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
}

async function prepareReviewLocation(page: Page, fixture: LegacyFixture) {
  // Historical customers have no area. Assign one while the existing customer
  // list is still visible, then restrict the operator to that area for warehouse work.
  const area = { code: `L-${randomUUID().slice(0, 6).toUpperCase()}`, name: 'Area pelanggan lama' }
  const optionLabel = `${area.name} · ${area.code}`
  const checkboxLabel = `${area.code} — ${area.name}`
  await page.goto('/areas')
  await page.getByRole('textbox', { name: 'Kode', exact: true }).fill(area.code)
  await page.getByRole('textbox', { name: 'Nama', exact: true }).fill(area.name)
  await confirmOperation(page, '/api/areas', 'Tambah')
  await page.goto('/customers')
  const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: fixture.customer.name, exact: true }) })
  await row.getByRole('button', { name: 'Aksi sel', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Edit', exact: true }).click()
  await selectNamed(page, 'Area pelanggan', optionLabel)
  const updated = await confirmOperation(page, `/api/customers/${fixture.customer.id}`, 'Simpan', 'PUT')
  expect(updated).toMatchObject({ id: fixture.customer.id, name: fixture.customer.name })
  await page.goto('/users')
  const self = page.getByRole('row').filter({ has: page.getByRole('gridcell', { name: fixture.admin.email, exact: true }) })
  await self.getByRole('button', { name: 'Aksi baris' }).click()
  await page.getByRole('menuitem', { name: 'Akses', exact: true }).click()
  await page.getByRole('checkbox', { name: checkboxLabel, exact: true }).check()
  const access = page.waitForResponse(res => /\/api\/users\/[^/]+\/access$/.test(new URL(res.url()).pathname) && res.request().method() === 'PUT')
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  expect((await access).ok()).toBeTruthy()
  await switchUser(page, fixture.admin)
  const location = await addLocation(page, { code: 'LEGACY_REVIEW', name: 'Pemeriksaan data lama', area: optionLabel })
  const checker = await setupDiscrepancyApprover(page, checkboxLabel, [location], [location], 'OPENING_BALANCE')
  return { location, checker }
}

test('historical customer identity survives independent zero-opening cutover and application restart', async ({ page }, testInfo) => {
  test.setTimeout(420_000)
  expect(['after', 'restart']).toContain(legacyPhase)
  const fixture = readLegacyFixture(testInfo.project.name)
  await login(page, fixture.admin)
  if (legacyPhase === 'after') {
    const { location, checker } = await prepareReviewLocation(page, fixture)
    await customerHistory(page, fixture)
    const previewRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/provenance')
    await page.goto('/warehouse/provenance')
    const preview = await (await previewRead).json()
    expect(preview).toMatchObject({ cutover: { state: 'LEGACY', epoch: 0 }, sourceCount: 1, sourceCounts: { onu: 1 },
      conflictGroupCount: 0, unitUnverifiedBalanceCount: 0, pendingLegacyMovementCount: 0 })
    await page.getByRole('button', { name: 'Mulai pemeriksaan gudang', exact: true }).click()
    const begun = await confirmOperation(page, '/api/v1/warehouse/provenance/batches', 'Mulai pemeriksaan')
    expect(begun).toMatchObject({ cutover: { state: 'VALIDATING', epoch: 1 }, sourceCount: 1 })
    const batch = begun.batch.id, root = `/api/v1/warehouse/provenance/batches/${batch}`
    const caseRead = page.waitForResponse(res => /\/api\/v1\/warehouse\/provenance\/cases\/[^/]+$/.test(new URL(res.url()).pathname))
    await page.getByRole('button', { name: `Periksa ${fixture.onu.serialNumber}`, exact: true }).click()
    const source = await (await caseRead).json()
    expect(source).toMatchObject({ sourceTable: 'onu', sourceId: fixture.onu.id, sourceSnapshot: { id: fixture.onu.id, serialNumber: fixture.onu.serialNumber } })
    expect(source.claims).toEqual(expect.arrayContaining([expect.objectContaining({ identityType: 'SERIAL', state: 'LEGACY_RESERVED', admittedAssetId: null })]))
    await page.getByRole('button', { name: 'Tambah bukti', exact: true }).click()
    await page.getByRole('textbox', { name: 'Nama bukti', exact: true }).fill('Catatan instalasi sebelum migrasi')
    await page.getByLabel('File bukti (PDF, PNG, JPEG; maksimal 15 MiB)', { exact: true }).setInputFiles('e2e/warehouse/fixtures/inspection.png')
    await page.getByRole('button', { name: 'Periksa unggahan', exact: true }).click()
    await confirmOperation(page, `${root}/cases/${source.id}/evidence`, 'Unggah bukti')
    await page.getByRole('checkbox', { name: 'Catatan instalasi sebelum migrasi', exact: true }).check()
    await page.getByRole('combobox', { name: 'Hasil pemeriksaan', exact: true }).selectOption('PROVENANCE_ONLY')
    await page.getByRole('textbox', { name: 'Alasan dan rujukan bukti', exact: true }).fill('Identitas perangkat pelanggan lama tetap tersimpan; bukti tidak menetapkan stok tersedia atau pemilik baru')
    await page.getByRole('button', { name: 'Tinjau keputusan', exact: true }).click()
    await confirmOperation(page, `${root}/cases/${source.id}/resolutions`, 'Simpan keputusan')
    await page.getByRole('button', { name: 'Saldo awal & aktivasi', exact: true }).click()
    await page.getByRole('button', { name: 'Tinjau hasil pemeriksaan', exact: true }).click()
    await selectNamed(page, 'Lokasi pemeriksaan saldo awal', location.label)
    await page.getByRole('textbox', { name: 'Referensi migrasi', exact: true }).fill(`UI-V172-${fixture.onu.id}`)
    await page.getByRole('textbox', { name: 'Alasan pengajuan saldo awal', exact: true }).fill('Satu instalasi historis dipertahankan tanpa menciptakan stok gudang')
    await page.getByRole('checkbox', { name: 'Pemeriksaan menyatakan saldo tersedia nol; tidak ada stok fiktif yang dibuat', exact: true }).check()
    await page.getByRole('button', { name: 'Tinjau usulan saldo awal', exact: true }).click()
    const opening = await confirmOperation(page, `${root}/opening`, 'Simpan usulan')
    await page.getByRole('link', { name: 'Buka persetujuan saldo awal', exact: true }).click()
    await page.getByRole('button', { name: 'Periksa persyaratan persetujuan', exact: true }).click()
    await page.getByRole('button', { name: 'Ajukan persetujuan', exact: true }).click()
    const approval = await confirmOperation(page, '/api/v1/warehouse/approvals/request', 'Kirim permintaan persetujuan')
    await expect(page.getByRole('button', { name: 'Setujui permintaan', exact: true })).toHaveCount(0)
    await switchUser(page, checker)
    await page.goto(`/warehouse/approvals?approvalId=${approval.requestId}`)
    await expect(page.getByRole('link', { name: 'Stok & Perangkat', exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: 'Setujui permintaan', exact: true }).click()
    await page.getByRole('textbox', { name: 'Alasan keputusan / referensi pemeriksaan', exact: true }).fill('Pemeriksa independen mengonfirmasi identitas lama dan saldo awal nol')
    await page.getByRole('button', { name: 'Tinjau keputusan', exact: true }).click()
    const decision = await confirmOperation(page, '/api/v1/warehouse/approvals/decide', 'Simpan keputusan')
    expect(decision.status).toBe('APPROVED')
    await expect(page.getByRole('region', { name: 'Hasil persetujuan dibukukan', exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('independent-legacy-opening-approval.png'), fullPage: true })
    await switchUser(page, fixture.admin)
    await page.goto('/warehouse/provenance?view=opening')
    await page.getByRole('textbox', { name: 'Catatan finalisasi', exact: true }).fill('Aktifkan gudang dengan riwayat instalasi lama utuh dan tanpa saldo fiktif')
    await page.getByRole('button', { name: 'Tinjau aktivasi gudang', exact: true }).click()
    const finalized = await confirmOperation(page, `${root}/finalization`, 'Aktifkan gudang')
    expect(finalized).toMatchObject({ openingDocumentId: opening.id, baselineCount: 0, baselineTotals: {},
      retainedIdentityCount: 1, cancellationCount: 0, sourceCounts: { onu: 1 }, cutover: { state: 'ENFORCED', epoch: 2 } })
    fixture.finalized = { batchId: batch, openingDocumentId: opening.id, reviewHash: finalized.reviewHash, epoch: 2 }
    // A tenant activated from V172 can begin normal catalog setup without
    // converting its unverified old ONU into available warehouse stock.
    const catalogSku = await addSku(page, { code: 'POST_UPGRADE_CABLE', name: 'Kabel baru setelah aktivasi gudang', tracking: 'LOT', unit: 'MM' })
    fixture.catalogSku = { id: catalogSku.id, code: catalogSku.code, unit: 'MM' }
    saveLegacyFixture(testInfo.project.name, fixture)
  }
  expect(fixture.catalogSku).toBeTruthy()
  const catalogRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/skus')
  await page.goto('/warehouse/catalog?tab=skus')
  const catalog = await (await catalogRead).json()
  expect(catalog.items).toEqual(expect.arrayContaining([expect.objectContaining({ id: fixture.catalogSku!.id,
    code: fixture.catalogSku!.code, baseUnit: 'MM', revision: 0, state: 'ACTIVE' })]))
  expect(fixture.finalized).toBeTruthy()
  const finalRead = page.waitForResponse(res => new URL(res.url()).pathname === `/api/v1/warehouse/provenance/batches/${fixture.finalized!.batchId}/finalization`)
  await page.goto('/warehouse/provenance?view=opening')
  const final = await (await finalRead).json()
  expect(final.finalization).toMatchObject({ openingDocumentId: fixture.finalized!.openingDocumentId,
    reviewHash: fixture.finalized!.reviewHash, baselineCount: 0, retainedIdentityCount: 1, cutover: { state: 'ENFORCED', epoch: 2 } })
  await expect(page.getByRole('region', { name: 'Bukti finalisasi gudang', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath(`legacy-cutover-${legacyPhase}.png`), fullPage: true })
  const stockRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/stock')
  await page.goto('/warehouse/stock?bucket=AVAILABLE')
  expect((await (await stockRead).json()).items).toEqual([])
  await customerHistory(page, fixture, testInfo.outputPath(`preserved-customer-${legacyPhase}.png`))
})
