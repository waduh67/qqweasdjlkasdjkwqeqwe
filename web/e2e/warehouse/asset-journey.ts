import { expect, type Page } from '@playwright/test'
import { addLocation, addSupplier } from './catalog'
import { confirmOperation, selectNamed } from './fulfillment'
import { switchUser, uploadCustomerSignature, type NumericJourney } from './numeric-journey'

export async function createAssetWorkOrder(page: Page, fixture: NumericJourney, type: 'DISMANTLE' | 'REPAIR' | 'MIGRATION' | 'PSB', customer = fixture.customer!) {
  await page.goto('/work-orders')
  await page.getByRole('button', { name: 'Buat work order', exact: true }).click()
  await page.getByRole('textbox', { name: 'Judul', exact: true }).fill(`${type} perangkat pelanggan`)
  await selectNamed(page, 'Area pekerjaan', fixture.area.optionLabel)
  await page.getByRole('combobox', { name: 'Tipe', exact: true }).selectOption(type)
  await page.getByPlaceholder('Cari nama, kode, telepon, atau alamat pelanggan…', { exact: true }).fill(customer.name)
  await page.getByRole('option').filter({ has: page.getByText(customer.name, { exact: true }) }).click()
  await page.getByRole('combobox', { name: 'Cari teknisi…', exact: true }).click()
  await page.getByRole('menuitemcheckbox', { name: fixture.technician.name, exact: true }).click()
  await expect(page.getByRole('menuitemcheckbox', { name: fixture.technician.name, exact: true })).toHaveAttribute('aria-checked', 'true')
  await page.getByRole('textbox', { name: 'Judul', exact: true }).click()
  const order = await confirmOperation(page, '/api/work-orders', 'Simpan')
  expect(order).toMatchObject({ customerId: customer.id, type, areaId: fixture.warehouse.areaId })
  return order
}

export async function startAssetWorkOrder(page: Page, fixture: NumericJourney, work: { id: string }, customer = fixture.customer!) {
  await switchUser(page, fixture.technician)
  await page.goto(`/my-work-orders/${work.id}`)
  await confirmOperation(page, `/api/work-orders/${work.id}/start`, 'Mulai')
  await uploadCustomerSignature(page, work.id, customer.name)
}

/** Observe the actual response used by the customer panel; no API fixture writes. */
export async function openAssetHistory(page: Page, work: { id: string; customerId: string }) {
  await page.goto(`/my-work-orders/${work.id}`)
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === `/api/customers/${work.customerId}/assets/workbench/history` && res.request().method() === 'GET'),
    page.getByRole('link', { name: 'Pasang perangkat dan periksa aset pelanggan', exact: true }).click(),
  ])
  expect(response.status()).toBe(200)
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toBeVisible()
  return response.json()
}

export async function acceptAssetHandover(page: Page, work: { id: string; code: string; customerId: string; type: string }) {
  await page.getByRole('button', { name: 'Terima serah-terima pelanggan', exact: true }).click()
  await selectNamed(page, 'WO tindakan perangkat', `${work.code} · ${work.type} · IN_PROGRESS`)
  await page.getByRole('button', { name: 'Tinjau tindakan', exact: true }).click()
  return confirmOperation(page, `/api/customers/${work.customerId}/assets/handover`, 'Terima serah-terima pelanggan', 'POST', 'Konfirmasi: Terima serah-terima pelanggan')
}

export async function dismantleAsset(page: Page, fixture: NumericJourney) {
  const work = await createAssetWorkOrder(page, fixture, 'DISMANTLE')
  await startAssetWorkOrder(page, fixture, work)
  const before = await openAssetHistory(page, work)
  await page.getByRole('button', { name: 'Lepas perangkat fisik', exact: true }).click()
  await selectNamed(page, 'WO tindakan perangkat', `${work.code} · DISMANTLE · IN_PROGRESS`)
  await page.getByRole('button', { name: 'Tinjau tindakan', exact: true }).click()
  const removed = await confirmOperation(page, `/api/customers/${fixture.customer!.id}/assets/remove`, 'Lepas perangkat fisik', 'POST', 'Konfirmasi: Lepas perangkat fisik')
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Sudah dilepas')
  return { work, removed, before }
}

export async function intakeAsset(page: Page, fixture: NumericJourney, sourceDocumentId: string) {
  await page.goto('/warehouse/returns')
  await page.getByRole('button', { name: 'Terima retur baru', exact: true }).click()
  const source = page.getByRole('combobox', { name: 'Sumber retur', exact: true })
  await expect(source.locator(`option[value="${sourceDocumentId}"]`)).toBeAttached()
  await source.selectOption(sourceDocumentId)
  await selectNamed(page, 'Karantina penerimaan retur', fixture.quarantine.label)
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan retur', exact: true }).fill('Perangkat yang sama diterima setelah dilepas dari pelanggan')
  await page.getByRole('button', { name: 'Tinjau penerimaan retur', exact: true }).click()
  return confirmOperation(page, '/api/v1/warehouse/returns', 'Catat retur')
}

export async function inspectAsset(page: Page, fixture: NumericJourney, returnId: string, condition: 'DAMAGED' | 'SERVICEABLE', owner: 'ISP' | 'CUSTOMER') {
  await page.getByRole('button', { name: 'Periksa retur', exact: true }).click()
  await page.getByRole('textbox', { name: 'Hasil ukur fisik (unit)', exact: true }).fill('1')
  await page.getByRole('combobox', { name: 'Kondisi hasil inspeksi', exact: true }).selectOption(condition)
  const release = condition === 'SERVICEABLE' && owner === 'ISP'
  await selectNamed(page, release ? 'Rak barang layak pakai' : 'Karantina tujuan', release ? fixture.bin.label : fixture.quarantine.label)
  await page.getByRole('textbox', { name: 'Serial fisik yang dipindai', exact: true }).fill(` ${fixture.serial.toLowerCase()} `)
  if (condition === 'SERVICEABLE') {
    await page.getByRole('checkbox', { name: 'Reset perangkat dan hapus konfigurasi lama sudah dilakukan', exact: true }).check()
    await page.getByRole('textbox', { name: 'Referensi bukti reset', exact: true }).fill('Reset dan hapus konfigurasi pelanggan lama diperiksa')
  }
  await page.getByRole('textbox', { name: 'Referensi bukti tindakan retur', exact: true }).fill('Unit dan serial fisik cocok dengan perangkat asal')
  await page.getByRole('button', { name: 'Tinjau tindakan retur', exact: true }).click()
  const inspected = await confirmOperation(page, `/api/v1/warehouse/returns/${returnId}/inspect`, 'Catat tindakan retur')
  expect(inspected).toMatchObject({ quantityBase: '1', legalOwner: owner, condition, locationId: release ? fixture.bin.id : fixture.quarantine.id })
  return inspected
}

export async function repairSoldAsset(page: Page, fixture: NumericJourney, sourceDocumentId: string, assetId: string) {
  await switchUser(page, fixture.admin)
  const vendor = await addSupplier(page, 'SERVICE', 'Penyedia servis perangkat')
  const repairLocation = await addLocation(page, { code: 'REPAIR_VENDOR', name: 'Penguasaan penyedia servis', area: fixture.area.optionLabel, kind: 'TRANSIT' })
  const received = await intakeAsset(page, fixture, sourceDocumentId)
  expect(received).toMatchObject({ stockIdentityId: assetId, legalOwner: 'CUSTOMER', state: 'RECEIVED_IN_INSPECTION' })
  await inspectAsset(page, fixture, received.id, 'DAMAGED', 'CUSTOMER')
  await page.getByRole('button', { name: 'Kirim ke servis', exact: true }).click()
  await selectNamed(page, 'Penyedia servis', `${vendor.name} · ${vendor.code}`)
  await selectNamed(page, 'Lokasi penguasaan servis', repairLocation.label)
  await page.getByRole('textbox', { name: 'Serial fisik yang dipindai', exact: true }).fill(fixture.serial.toUpperCase())
  await page.getByRole('textbox', { name: 'Referensi servis penyedia', exact: true }).fill('SERVICE-001')
  await page.getByRole('textbox', { name: 'Referensi bukti tindakan retur', exact: true }).fill('Perangkat asli diserahkan ke penyedia servis')
  await page.getByRole('button', { name: 'Tinjau tindakan retur', exact: true }).click()
  const dispatched = await confirmOperation(page, `/api/v1/warehouse/returns/${received.id}/repair-dispatch`, 'Catat tindakan retur')
  expect(dispatched).toMatchObject({ stockIdentityId: assetId, legalOwner: 'CUSTOMER', state: 'REPAIR' })
  await page.getByRole('button', { name: 'Terima dari servis', exact: true }).click()
  await page.getByRole('combobox', { name: 'Hasil servis', exact: true }).selectOption('REPAIRED')
  await selectNamed(page, 'Karantina tujuan', fixture.quarantine.label)
  await page.getByRole('textbox', { name: 'Serial fisik yang dipindai', exact: true }).fill(fixture.serial.toUpperCase())
  await page.getByRole('textbox', { name: 'Referensi servis penyedia', exact: true }).fill('SERVICE-001-DONE')
  await page.getByRole('textbox', { name: 'Referensi bukti tindakan retur', exact: true }).fill('Serial yang sama kembali setelah diperbaiki')
  await page.getByRole('button', { name: 'Tinjau tindakan retur', exact: true }).click()
  await confirmOperation(page, `/api/v1/warehouse/returns/${received.id}/repair-receive`, 'Catat tindakan retur')
  await expect(page.getByRole('button', { name: 'Siapkan serah-terima RMA', exact: true })).toHaveCount(0)
  const inspected = await inspectAsset(page, fixture, received.id, 'SERVICEABLE', 'CUSTOMER')
  await expect(page.getByRole('button', { name: 'Siapkan serah-terima RMA', exact: true })).toBeVisible()
  return { received, inspected }
}

export async function returnSoldRma(page: Page, fixture: NumericJourney, returnId: string, assetId: string) {
  const work = await createAssetWorkOrder(page, fixture, 'REPAIR')
  await startAssetWorkOrder(page, fixture, work)
  await switchUser(page, fixture.admin)
  await page.goto(`/warehouse/returns?returnId=${returnId}`)
  await page.getByRole('button', { name: 'Siapkan serah-terima RMA', exact: true }).click()
  await selectNamed(page, 'WO perbaikan pelanggan', `${work.code} · ${work.title} · ${fixture.customer!.name}`)
  await selectNamed(page, 'Teknisi penerima RMA', fixture.technician.name)
  await selectNamed(page, 'Transit RMA', fixture.transit.label)
  await selectNamed(page, 'Lokasi teknisi RMA', fixture.field.label)
  await page.getByRole('textbox', { name: 'Serial fisik RMA', exact: true }).fill(fixture.serial.toUpperCase())
  await page.getByRole('textbox', { name: 'Referensi bukti RMA', exact: true }).fill('Kembali ke pelanggan pemilik asli')
  await page.getByRole('button', { name: 'Tinjau pengiriman RMA', exact: true }).click()
  const outbound = await confirmOperation(page, `/api/v1/warehouse/returns/${returnId}/rma-handover`, 'Kirim RMA ke transit')
  expect(outbound).toMatchObject({ stockIdentityId: assetId, customerId: fixture.customer!.id, legalOwner: 'CUSTOMER', state: 'DISPATCHED' })
  expect(outbound.serial).not.toBe(outbound.serial.toUpperCase())
  await switchUser(page, fixture.technician)
  await page.goto(`/my-materials?workOrderId=${work.id}`)
  await page.getByRole('button', { name: 'Lihat perangkat servis', exact: true }).click()
  await page.getByRole('button', { name: 'Terima perangkat servis', exact: true }).click()
  const scanner = page.getByRole('textbox', { name: 'Serial perangkat', exact: true })
  await scanner.fill('DIFFERENT-DEVICE'); await scanner.press('Enter')
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan servis', exact: true }).fill('Perangkat servis diterima teknisi')
  await page.getByRole('button', { name: 'Tinjau penerimaan servis', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Cocokkan serial fisik')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await scanner.fill(fixture.serial.toUpperCase()); await scanner.press('Enter')
  await page.getByRole('button', { name: 'Tinjau penerimaan servis', exact: true }).click()
  const acknowledged = await confirmOperation(page, `/api/v1/warehouse/rma-handovers/${outbound.id}/acknowledge`, 'Terima perangkat')
  expect(acknowledged).toMatchObject({ stockIdentityId: assetId, serial: outbound.serial, state: 'RECEIVED' })
  await page.getByRole('button', { name: 'Pasang kembali perangkat', exact: true }).click()
  await scanner.fill(fixture.serial.toLowerCase()); await scanner.press('Enter')
  await page.getByRole('button', { name: 'Tinjau pemasangan kembali', exact: true }).click()
  const installed = await confirmOperation(page, `/api/customers/${fixture.customer!.id}/assets/install`, 'Pasang kembali')
  expect(installed).toMatchObject({ assetId, customerId: fixture.customer!.id })
  await page.getByRole('link', { name: 'Buka aset pelanggan', exact: true }).click()
  await acceptAssetHandover(page, work)
  const history = await openAssetHistory(page, work)
  expect(history.items).toHaveLength(2)
  expect(history.items.map((row: { asset: { assetId: string } }) => row.asset.assetId)).toEqual([assetId, assetId])
  const active = history.items.filter((row: { asset: { endedAt: string | null } }) => row.asset.endedAt === null)
  expect(active).toHaveLength(1)
  expect(active[0].asset).toMatchObject({ customerId: fixture.customer!.id, serial: outbound.serial.toUpperCase(), legalOwner: 'CUSTOMER', ownershipMode: 'SALE', purpose: 'RETURN_CUSTOMER_RMA', handoverState: 'ACCEPTED' })
  return { work, outbound, acknowledged, installed, history }
}
