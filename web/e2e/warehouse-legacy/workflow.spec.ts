import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { addLocation, addSku, addSupplier, setupOwnArea } from '../warehouse/catalog'
import { createRole, createUser, login } from '../warehouse/helpers'
import { confirmOperation, selectNamed } from '../warehouse/fulfillment'
import { switchUser } from '../warehouse/numeric-journey'
import { captureReference, captureReferenceDocument } from '../warehouse/reference-setup'
import { receipt as decodeReceipt } from '../../src/api/warehouse/receipts'
import { legacyPhase, readLegacyFixture, saveLegacyFixture, type LegacyFixture } from './fixture'

async function capture(page: Page, info: TestInfo, name: string) {
  for (const theme of ['light', 'dark']) {
    const change = page.getByRole('button', { name: theme === 'light' ? 'Ganti ke tema terang' : 'Ganti ke tema gelap', exact: true, includeHidden: true })
    if (await change.count()) await change.evaluate(button => (button as HTMLButtonElement).click())
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    const dialog = page.getByRole('dialog')
    if (await dialog.count()) {
      for (const end of [false, true]) {
        await dialog.last().locator('.fui-DialogContent').evaluate((body, atEnd) => { body.scrollTop = atEnd ? body.scrollHeight : 0 }, end)
        await captureReference(page, info, theme + '-' + name + (end ? '-end' : ''))
      }
    } else await captureReferenceDocument(page, info, name)
  }
}

async function prepareDocuments(page: Page, fixture: LegacyFixture) {
  const area = await setupOwnArea(page, fixture.admin)
  const warehouse = await addLocation(page, { code: 'FLOW', name: 'Gudang perpindahan', area: area.optionLabel })
  const bin = await addLocation(page, { code: 'FLOW_BIN', name: 'Rak perpindahan', kind: 'BIN', parent: warehouse.label, area: area.optionLabel })
  const destination = await addLocation(page, { code: 'FLOW_DEST', name: 'Gudang tujuan perpindahan', area: area.optionLabel })
  const transit = await addLocation(page, { code: 'FLOW_TRANSIT', name: 'Transit perpindahan', kind: 'TRANSIT', area: area.optionLabel })
  const inspection = await addLocation(page, { code: 'FLOW_QA', name: 'Pemeriksaan kiriman', kind: 'QUARANTINE', area: area.optionLabel })
  const source = await addLocation(page, { code: 'RECEIPT_SOURCE', name: 'Sumber kiriman', kind: 'TRANSIT', area: area.optionLabel })
  const cable = await addSku(page, { code: 'FLOW_CABLE', name: 'Kabel perpindahan', tracking: 'LOT', unit: 'MM', inspectionRequired: false })
  const onu = await addSku(page, { code: 'FLOW_ONU', name: 'ONU perpindahan', tracking: 'SERIAL', inspectionRequired: false })
  const supplier = await addSupplier(page, 'FLOW_SUPPLIER', 'Pemasok perpindahan')
  const serial = 'FLOW-' + randomUUID().slice(0, 8).toUpperCase(), receiptReference = 'SJ-PERPINDAHAN'
  await page.goto('/warehouse/receipts')
  await page.getByRole('button', { name: 'Buat penerimaan', exact: true }).click()
  await page.getByRole('textbox', { name: 'Referensi surat jalan', exact: true }).fill(receiptReference)
  await selectNamed(page, 'Pemasok', supplier.name + ' · ' + supplier.code)
  await selectNamed(page, 'Batas penerimaan', source.label)
  await selectNamed(page, 'Lokasi pemeriksaan', inspection.label)
  await selectNamed(page, 'Barang 1', cable.name + ' · ' + cable.code)
  await page.getByRole('textbox', { name: 'Panjang reel aktual (m)', exact: true }).fill('100')
  await page.getByRole('textbox', { name: 'Kode lot / reel', exact: true }).fill('REEL-PERPINDAHAN')
  await page.getByRole('button', { name: 'Tambah baris barang', exact: true }).click()
  await selectNamed(page, 'Barang 2', onu.name + ' · ' + onu.code)
  await page.getByRole('textbox', { name: 'Jumlah aktual (unit)', exact: true }).fill('1')
  await page.getByRole('textbox', { name: 'Serial dan MAC', exact: true }).fill(serial)
  await page.getByRole('button', { name: 'Tinjau draft', exact: true }).click()
  const receipt = decodeReceipt(await confirmOperation(page, '/api/v1/warehouse/receipts', 'Simpan draft'))
  await page.getByRole('button', { name: 'Tambah bukti', exact: true }).click()
  await page.getByLabel('File bukti (PNG, JPEG, PDF; maksimal 15 MiB)', { exact: true }).setInputFiles('e2e/warehouse/fixtures/inspection.png')
  await page.getByRole('button', { name: 'Tinjau unggahan', exact: true }).click()
  const evidence = await confirmOperation(page, '/api/v1/warehouse/receipts/' + receipt.id + '/attachments', 'Unggah bukti')
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  await confirmOperation(page, '/api/v1/warehouse/receipts/' + receipt.id + '/receive', 'Konfirmasi penerimaan')
  await page.getByRole('button', { name: 'Tempatkan ke bin', exact: true }).click()
  await selectNamed(page, 'Bin tujuan', bin.label)
  await page.getByRole('button', { name: 'Pilih semua yang memenuhi syarat', exact: true }).click()
  await page.getByRole('button', { name: 'Tinjau penempatan', exact: true }).click()
  await confirmOperation(page, '/api/v1/warehouse/receipts/' + receipt.id + '/putaway', 'Tempatkan barang')

  await createRole(page, 'Pembaca arsip perpindahan', ['inventory.item.view', 'inventory.receipt.view', 'inventory.transfer.view', 'warehouse.stock.view'])
  const reader = await createUser(page, 'Pembaca arsip perpindahan', { areas: [area.checkboxLabel], prefix: 'PembacaArsip' })
  await page.goto('/warehouse/catalog?tab=access')
  await selectNamed(page, 'Pengguna', reader.name + ' · ' + reader.email)
  for (const location of [warehouse, bin, destination, transit, inspection, source]) {
    await selectNamed(page, 'Lokasi', location.label)
    await page.getByRole('button', { name: 'Berikan akses langsung', exact: true }).click()
    const granted = page.waitForResponse(res => new URL(res.url()).pathname.endsWith('/' + location.id) && res.request().method() === 'PUT')
    await page.getByRole('button', { name: 'Berikan akses', exact: true }).click()
    expect((await granted).ok()).toBeTruthy()
    await expect(page.getByText('Pemberian akses langsung: aktif · Revisi 1', { exact: true })).toBeVisible()
  }

  await page.goto('/warehouse/transfers')
  await page.getByRole('button', { name: 'Buat transfer', exact: true }).click()
  const positionsRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/stock/positions' && new URL(res.url()).searchParams.get('locationId') === bin.id)
  await selectNamed(page, 'Lokasi asal transfer', bin.label)
  const positions = await (await positionsRead).json()
  const position = positions.items.find((row: { skuId: string }) => row.skuId === cable.id)
  expect(position).toMatchObject({ physical: { quantityBase: '100000' }, available: { quantityBase: '100000' } })
  await selectNamed(page, 'Lokasi tujuan transfer', destination.label)
  await selectNamed(page, 'Lokasi transit transfer', transit.label)
  await selectNamed(page, 'Penerima transfer', fixture.admin.name)
  await page.getByRole('textbox', { name: 'Alasan transfer', exact: true }).fill('Selesaikan kiriman sebelum mengaktifkan alur baru')
  await selectNamed(page, 'Barang transfer 1', cable.name + ' · ' + position.stockIdentityId.slice(0, 8) + ' · 100,000 m')
  await page.getByRole('textbox', { name: 'Jumlah transfer 1 (m)', exact: true }).fill('12,501')
  await page.getByRole('button', { name: 'Tinjau transfer', exact: true }).click()
  const transfer = await confirmOperation(page, '/api/v1/warehouse/transfers', 'Simpan transfer')
  return { receiptId: receipt.id, receiptReference, evidenceId: evidence.id as string, transferId: transfer.id as string,
    transferCode: transfer.code as string, cableId: cable.id, lotId: position.lotId as string, serial, destinationId: destination.id, epoch: 4, reader }
}

async function verifyArchive(page: Page, info: TestInfo, journey: NonNullable<LegacyFixture['workflowJourney']>) {
  await page.goto('/warehouse/archive?section=receipts')
  await expect(page.getByRole('link', { name: journey.receiptReference, exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-archive-receipts')
  await page.getByRole('link', { name: journey.receiptReference, exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail penerimaan lama', exact: true })).toContainText(journey.receiptReference)
  await expect(page.getByText('Barang ditempatkan', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Tambah bukti', exact: true })).toHaveCount(0)
  const evidence = page.getByRole('region', { name: 'Bukti penerimaan lama', exact: true })
  await evidence.getByRole('button', { name: 'Aksi baris', exact: true }).click()
  const download = page.waitForEvent('download')
  await page.getByRole('menuitem', { name: 'Unduh bukti', exact: true }).click()
  expect((await download).suggestedFilename()).toBe('bukti-' + journey.evidenceId + '.png')
  await capture(page, info, legacyPhase + '-archive-receipt')

  await page.goto('/warehouse/archive?section=transfers')
  await expect(page.getByRole('link', { name: journey.transferCode, exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-archive-transfers')
  await page.getByRole('link', { name: journey.transferCode, exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail transfer lama', exact: true })).toContainText('Diterima')
  await expect(page.getByRole('heading', { name: 'Revisi 2 · Diterima', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Terima transfer', exact: true })).toHaveCount(0)
  await capture(page, info, legacyPhase + '-archive-transfer')

  await page.goto('/warehouse/archive?section=stock&tab=lots')
  await expect(page.getByRole('link', { name: 'REEL-PERPINDAHAN', exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-archive-lots')
  await page.getByRole('link', { name: 'REEL-PERPINDAHAN', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail lot', exact: true })).toContainText('Jumlah reel dan seluruh bagiannya konsisten.')
  await expect(page.getByRole('link', { name: `RCV-${journey.receiptId}`, exact: true })).toHaveAttribute('href', '/warehouse/archive?section=receipts&id=' + journey.receiptId)
  await capture(page, info, legacyPhase + '-archive-lot')
  await page.getByRole('region', { name: 'Bagian reel', exact: true }).getByRole('link').first().click()
  await expect(page.getByRole('region', { name: 'Detail bagian reel', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/warehouse\/archive\?section=stock&lot=.+&segment=/)
  await capture(page, info, legacyPhase + '-archive-segment')

  await page.goto('/warehouse/archive?section=stock&tab=assets&serial=' + journey.serial)
  await expect(page.getByRole('link', { name: journey.serial, exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-archive-assets')
  await page.getByRole('link', { name: journey.serial, exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail perangkat', exact: true })).toContainText(journey.serial)
  await capture(page, info, legacyPhase + '-archive-asset')
  await page.getByRole('link', { name: 'Lihat posisi stok perangkat', exact: true }).click()
  await expect(page.getByRole('link', { name: 'ONU perpindahan · ' + journey.serial, exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-archive-positions')
  await page.getByRole('link', { name: 'ONU perpindahan · ' + journey.serial, exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail posisi', exact: true })).toContainText(journey.serial)
  await capture(page, info, legacyPhase + '-archive-position')

  const stockRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v2/warehouse/stock/' + journey.cableId)
  await page.goto('/warehouse/stock?skuId=' + journey.cableId)
  const stock = await (await stockRead).json()
  expect(stock.warehouses.reduce((sum: number, row: { quantityBase: string }) => sum + Number(row.quantityBase), 0)).toBe(100000)
  expect(stock.warehouses.find((row: { warehouseId: string }) => row.warehouseId === journey.destinationId)).toMatchObject({ quantityBase: '12501' })
  await expect(page.getByRole('region', { name: 'Saldo per gudang', exact: true })).toContainText('12,501 m')
  await capture(page, info, legacyPhase + '-preserved-reference-stock')
}

test('owner drains unfinished documents, activates once, and keeps old evidence and exact stock readable after restart', async ({ page }, info) => {
  test.setTimeout(420_000)
  const fixture = readLegacyFixture(info.project.name)
  await login(page, fixture.admin)
  if (legacyPhase === 'transition') {
    const journey = await prepareDocuments(page, fixture)
    await page.goto('/warehouse/transition')
    await expect(page.getByRole('heading', { name: 'Alur lama', exact: true })).toBeVisible()
    await capture(page, info, 'legacy-readiness')
    await page.getByRole('button', { name: 'Mulai perpindahan', exact: true }).click()
    await capture(page, info, 'drain-confirmation')
    const drainAttempts: string[] = []
    await page.route('**/api/v2/warehouse/workflow/drain', async route => {
      drainAttempts.push(route.request().postData() ?? '')
      expect(route.request().headers()['idempotency-key']).toBeUndefined()
      await route.fetch(); await route.abort('failed')
    })
    await page.getByRole('dialog', { name: 'Mulai perpindahan gudang', exact: true }).getByRole('button', { name: 'Mulai perpindahan', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Periksa hasil perpindahan', exact: true })).toBeVisible()
    await capture(page, info, 'uncertain-drain')
    await page.getByRole('button', { name: 'Periksa hasil perpindahan', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Menyelesaikan alur lama', exact: true })).toBeVisible()
    expect(drainAttempts).toHaveLength(1)
    await page.unroute('**/api/v2/warehouse/workflow/drain')
    await expect(page.getByRole('heading', { name: 'Dokumen gudang masih berjalan', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Aktifkan alur gudang baru', exact: true })).toHaveCount(0)
    await capture(page, info, 'draining-blocker')
    await page.getByRole('region', { name: 'Dokumen gudang masih berjalan', exact: true }).getByRole('link', { name: 'Transfer', exact: true }).click()
    const transferLink = page.getByRole('link', { name: journey.transferCode, exact: true })
    await expect(transferLink).toBeVisible()
    expect(await transferLink.evaluate(link => {
      const rect = link.getBoundingClientRect(), parent = link.closest('[role="gridcell"]')
      if (!parent) throw new Error('Transfer link is outside its table cell')
      const cell = parent.getBoundingClientRect()
      return rect.top >= cell.top && rect.bottom <= cell.bottom
    })).toBe(true)
    await capture(page, info, 'legacy-transfer-list')
    await transferLink.click()
    await page.getByRole('button', { name: 'Kirim ke transit', exact: true }).click()
    await confirmOperation(page, '/api/v1/warehouse/transfers/' + journey.transferId + '/dispatch', 'Konfirmasi kirim transfer')
    await page.getByRole('button', { name: 'Terima transfer', exact: true }).click()
    await page.getByRole('checkbox', { name: 'Terima Kabel perpindahan · REEL-PERPINDAHAN', exact: true }).check()
    await page.getByRole('textbox', { name: 'Jumlah diterima (m)', exact: true }).fill('12,501')
    await page.getByRole('textbox', { name: 'Referensi bukti transfer', exact: true }).fill('BA-PERPINDAHAN')
    await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
    await confirmOperation(page, '/api/v1/warehouse/transfers/' + journey.transferId + '/receive', 'Catat penerimaan')
    await page.goto('/warehouse/transition')
    await expect(page.getByText('Tidak ada penghambat pada pemeriksaan ini.', { exact: true })).toBeVisible()
    await capture(page, info, 'activation-readiness')
    await page.getByRole('button', { name: 'Aktifkan alur gudang baru', exact: true }).click()
    await page.getByRole('textbox', { name: 'Alasan aktivasi', exact: true }).fill('Dokumen selesai; stok dan bukti penerimaan terverifikasi dipertahankan')
    await capture(page, info, 'activation-reason')
    await page.getByRole('button', { name: 'Tinjau aktivasi', exact: true }).click()
    await capture(page, info, 'activation-review')
    const attempts: { key: string | undefined; body: string | null }[] = []
    await page.route('**/api/v2/warehouse/workflow/activate', async route => {
      attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
      if (attempts.length === 1) { await route.fetch(); await route.abort('failed') }
      else await route.continue()
    })
    await page.getByRole('button', { name: 'Aktifkan alur baru', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeDisabled()
    await capture(page, info, 'uncertain-activation')
    await page.getByRole('button', { name: 'Coba transaksi yang sama', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Alur baru aktif', exact: true })).toBeVisible()
    expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0]); expect(attempts[0]?.key).toBeTruthy()
    await page.unroute('**/api/v2/warehouse/workflow/activate')
    fixture.workflowJourney = journey
    saveLegacyFixture(info.project.name, fixture)
  }
  const journey = fixture.workflowJourney
  if (!journey) throw new Error('Historical workflow journey missing')
  const workflowRead = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v2/warehouse/workflow')
  await page.goto('/warehouse/transition')
  expect(await (await workflowRead).json()).toMatchObject({ owner: true, snapshot: { workflow: 'REFERENCE', epoch: journey.epoch } })
  await expect(page.getByRole('heading', { name: 'Alur baru aktif', exact: true })).toBeVisible()
  await capture(page, info, legacyPhase + '-reference-active')
  await verifyArchive(page, info, journey)
  await switchUser(page, journey.reader)
  await page.goto('/warehouse/transition')
  await expect(page.getByText('Akses gudang dibatasi', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Aktifkan alur gudang baru', exact: true })).toHaveCount(0)
  await capture(page, info, legacyPhase + '-nonowner-transition')
  await verifyArchive(page, info, journey)
})
