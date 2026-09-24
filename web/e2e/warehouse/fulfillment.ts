import { expect, type Page } from '@playwright/test'
import { createUser, signup } from './helpers'
import { addLocation, addSku, addSupplier, setupOwnArea } from './catalog'

export async function selectNamed(page: Page, name: string, label: string) {
  const control = page.getByRole('combobox', { name, exact: true })
  await expect(control).toBeEnabled()
  await expect(control.getByRole('option', { name: label, exact: true })).toBeAttached()
  await control.selectOption({ label })
}
export async function confirmOperation(page: Page, path: string, button: string, method = 'POST') {
  const pending = page.waitForResponse(res => new URL(res.url()).pathname === path && res.request().method() === method)
  await page.getByRole('button', { name: button, exact: true }).click()
  const response = await pending
  expect(response.status(), path).toBeGreaterThanOrEqual(200)
  expect(response.status(), path).toBeLessThan(300)
  return response.json()
}

/** All business fixtures are created through visible UI, with real server responses. */
export async function prepareMaterialWorkOrder(page: Page) {
  const admin = await signup(page)
  const area = await setupOwnArea(page, admin)
  const warehouse = await addLocation(page, { code: 'MAIN', name: 'Gudang utama', area: area.optionLabel })
  const bin = await addLocation(page, { code: 'BIN-A', name: 'Rak A', area: area.optionLabel, kind: 'BIN', parent: warehouse.label })
  const quarantine = await addLocation(page, { code: 'QA', name: 'Pemeriksaan barang', area: area.optionLabel, kind: 'QUARANTINE' })
  const source = await addLocation(page, { code: 'RECEIPT_SOURCE', name: 'Penerimaan pemasok', area: area.optionLabel, kind: 'TRANSIT' })
  const cable = await addSku(page, { code: 'CABLE', name: 'Kabel drop', tracking: 'LOT', unit: 'MM', inspectionRequired: false })
  const onu = await addSku(page, { code: 'ONU', name: 'ONU pelanggan', tracking: 'SERIAL', inspectionRequired: false })
  const supplier = await addSupplier(page, 'DISTRIB', 'Distributor material')
  await page.goto('/warehouse/receipts')
  await page.getByRole('button', { name: 'Buat penerimaan', exact: true }).click()
  await page.getByRole('textbox', { name: 'Referensi surat jalan', exact: true }).fill('SJ-MATERIAL-1000-10')
  await selectNamed(page, 'Pemasok', `${supplier.name} · ${supplier.code}`)
  await selectNamed(page, 'Batas penerimaan', source.label)
  await selectNamed(page, 'Lokasi pemeriksaan', quarantine.label)
  await selectNamed(page, 'Barang 1', `${cable.name} · ${cable.code}`)
  await page.getByRole('textbox', { name: 'Panjang reel aktual (m)', exact: true }).fill('1000')
  await page.getByRole('textbox', { name: 'Kode lot / reel', exact: true }).fill('REEL-WO')
  await page.getByRole('button', { name: 'Tambah baris barang', exact: true }).click()
  await selectNamed(page, 'Barang 2', `${onu.name} · ${onu.code}`)
  await page.getByRole('textbox', { name: 'Jumlah aktual (unit)', exact: true }).fill('10')
  const serials = Array.from({ length: 10 }, (_, index) => `WO-ONU-${String(index + 1).padStart(3, '0')}`)
  await page.getByRole('textbox', { name: 'Serial dan MAC', exact: true }).fill(serials.join('\n'))
  await page.getByRole('button', { name: 'Tinjau draft', exact: true }).click()
  const receipt = await confirmOperation(page, '/api/v1/warehouse/receipts', 'Simpan draft')
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  await confirmOperation(page, `/api/v1/warehouse/receipts/${receipt.id}/receive`, 'Konfirmasi penerimaan')
  await page.getByRole('button', { name: 'Tempatkan ke bin', exact: true }).click()
  await selectNamed(page, 'Bin tujuan', bin.label)
  await page.getByRole('button', { name: 'Pilih semua yang memenuhi syarat', exact: true }).click()
  await page.getByRole('button', { name: 'Tinjau penempatan', exact: true }).click()
  await confirmOperation(page, `/api/v1/warehouse/receipts/${receipt.id}/putaway`, 'Tempatkan barang')
  await expect(page.getByText('Selesai ditempatkan', { exact: true })).toBeVisible()
  const technician = await createUser(page, 'Teknisi', { areas: [area.checkboxLabel], prefix: 'Teknisi' })
  await page.goto('/work-orders')
  await page.getByRole('button', { name: 'Buat work order', exact: true }).click()
  await page.getByRole('textbox', { name: 'Judul', exact: true }).fill('Pemasangan material gudang')
  await selectNamed(page, 'Area pekerjaan', area.optionLabel)
  await page.getByRole('combobox', { name: 'Tipe', exact: true }).selectOption('PREVENTIVE')
  await page.getByRole('combobox', { name: 'Cari teknisi…', exact: true }).click()
  await page.getByRole('option', { name: technician.name, exact: true }).click()
  await page.getByRole('textbox', { name: 'Judul', exact: true }).click()
  const workOrder = await confirmOperation(page, '/api/work-orders', 'Simpan')
  expect(workOrder.areaId).toBe(warehouse.areaId)
  expect(workOrder.assignees).toHaveLength(1)
  await page.goto('/warehouse/requests')
  await page.getByRole('link', { name: `${workOrder.code} · Pemasangan material gudang`, exact: true }).click()
  await expect(page.getByText('Rencana material belum disusun.', { exact: true })).toBeVisible()
  return { admin, area, warehouse, bin, cable, onu, technician, workOrder, receipt }
}
