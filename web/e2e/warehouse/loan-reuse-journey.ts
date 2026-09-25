import { expect, type Page } from '@playwright/test'
import { acceptAssetHandover, createAssetWorkOrder, intakeAsset, inspectAsset, openAssetHistory } from './asset-journey'
import { confirmOperation, selectNamed } from './fulfillment'
import { switchUser, uploadCustomerSignature, type NumericJourney } from './numeric-journey'

/** One serial-only plan, including an operator-selected returned asset when requested. */
export async function issueSingleAsset(page: Page, fixture: NumericJourney, work: { id: string }, selectedSerial?: string) {
  const root = `/api/work-orders/${work.id}/materials`
  await page.goto(`/warehouse/requests?workOrderId=${work.id}`)
  await page.getByRole('button', { name: 'Susun rencana material', exact: true }).click()
  await selectNamed(page, 'Material 1', `${fixture.onu.name} · ${fixture.onu.code}`)
  await page.getByRole('textbox', { name: 'Kebutuhan material 1 (unit)', exact: true }).fill('1')
  await page.getByRole('button', { name: 'Tinjau rencana', exact: true }).click()
  await confirmOperation(page, `${root}/plan`, 'Simpan rencana', 'PUT')
  await page.getByRole('button', { name: 'Ajukan permintaan', exact: true }).click()
  const demand = await confirmOperation(page, `${root}/submit-request`, 'Konfirmasi pengajuan')
  if (selectedSerial) {
    await page.getByRole('button', { name: 'Reservasi sebagian / pilih stok', exact: true }).click()
    await page.getByRole('checkbox', { name: 'Cadangkan ONU pelanggan', exact: true }).check()
    await page.getByRole('textbox', { name: 'Reservasi ONU pelanggan (unit)', exact: true }).fill('1')
    await page.getByRole('textbox', { name: 'Cari serial atau potongan stok', exact: true }).fill(selectedSerial)
    await selectNamed(page, 'Serial atau potongan stok', `${fixture.onu.name} · ${selectedSerial} · ${fixture.bin.name} · 1 unit`)
    await page.getByRole('textbox', { name: 'Alasan pemilihan stok', exact: true }).fill('Pakai ulang unit asli yang sudah diperiksa dan direset')
    await page.getByRole('button', { name: 'Tinjau reservasi', exact: true }).click()
    await confirmOperation(page, `/api/v1/warehouse/material-requests/${demand.demandDocumentId}/reserve`, 'Cadangkan pilihan')
  } else {
    await page.getByRole('button', { name: 'Cadangkan otomatis', exact: true }).click()
    await confirmOperation(page, `/api/v1/warehouse/material-requests/${demand.demandDocumentId}/reserve`, 'Konfirmasi reservasi')
  }
  await page.getByRole('button', { name: 'Siapkan barang', exact: true }).click()
  const chosen = page.getByRole('checkbox', { name: /^Pilih / })
  const serial = (await page.getByRole('group').filter({ has: chosen }).locator('legend').innerText()).split(' · ').at(-1)!
  if (selectedSerial) expect(serial.toUpperCase()).toBe(selectedSerial.toUpperCase())
  await chosen.check()
  const scanner = page.getByRole('textbox', { name: /^Pindai / })
  await scanner.fill(serial); await scanner.press('Enter')
  await page.getByRole('button', { name: 'Tinjau pilihan', exact: true }).click()
  const picked = await confirmOperation(page, `${root}/pick`, 'Siapkan pilihan')
  await page.getByRole('button', { name: picked.code, exact: true }).click()
  await page.getByRole('textbox', { name: 'Catatan pengiriman / pembatalan', exact: true }).fill('Unit fisik yang dipilih diserahkan ke teknisi')
  await page.getByRole('checkbox', { name: `Konfirmasi penerima: ${fixture.technician.name}`, exact: true }).check()
  await page.getByRole('button', { name: 'Kirim barang', exact: true }).click()
  const issue = await confirmOperation(page, `${root}/dispatch`, 'Konfirmasi kirim')
  expect(issue.lines).toHaveLength(1)
  return { issue, serial, root }
}

export async function acknowledgeSingleAsset(page: Page, fixture: NumericJourney, work: { id: string }, serial: string, customer = fixture.customer!) {
  await switchUser(page, fixture.technician)
  await page.goto(`/my-materials?workOrderId=${work.id}`)
  await expect(page.getByRole('button', { name: 'Catat pemakaian', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  const scanner = page.getByRole('textbox', { name: 'Serial perangkat', exact: true })
  await scanner.fill(serial); await scanner.press('Enter')
  await page.getByRole('textbox', { name: 'Jumlah diterima (unit)', exact: true }).fill('1')
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan', exact: true }).fill('Satu unit dengan serial yang sama diterima')
  await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
  await confirmOperation(page, `/api/work-orders/${work.id}/materials/acknowledge`, 'Terima material')
  await page.goto(`/my-work-orders/${work.id}`)
  await confirmOperation(page, `/api/work-orders/${work.id}/start`, 'Mulai')
  await uploadCustomerSignature(page, work.id, customer.name)
}

export async function installSingleAsset(page: Page, fixture: NumericJourney, work: { id: string; code: string; type: string; customerId: string }, issue: { code: string }, serial: string, replace = false) {
  await openAssetHistory(page, work)
  await page.getByRole('button', { name: replace ? 'Ganti perangkat' : 'Pasang perangkat dari gudang', exact: true }).click()
  await selectNamed(page, 'WO pemasangan', `${work.code} · ${work.type} · IN_PROGRESS`)
  const recorded = fixture.serials.find(value => value.toUpperCase() === serial.toUpperCase())!
  await selectNamed(page, 'Perangkat yang sudah diterima', `${recorded} · ${fixture.onu.name} · ${issue.code}`)
  const scanner = page.getByRole('textbox', { name: 'Serial perangkat', exact: true })
  await scanner.fill(serial); await scanner.press('Enter')
  await page.getByRole('combobox', { name: 'Kepemilikan perangkat', exact: true }).selectOption('LOAN')
  await page.getByRole('button', { name: 'Tinjau pemasangan', exact: true }).click()
  const installed = await confirmOperation(page, `/api/customers/${work.customerId}/assets/${replace ? 'replace' : 'install'}`, replace ? 'Ganti perangkat' : 'Pasang perangkat')
  await acceptAssetHandover(page, work)
  return installed
}

export async function swapLoanAndInspectOldAsset(page: Page, fixture: NumericJourney, oldAssetId: string) {
  const work = await createAssetWorkOrder(page, fixture, 'MIGRATION')
  const shipment = await issueSingleAsset(page, fixture, work)
  await acknowledgeSingleAsset(page, fixture, work, shipment.serial)
  const swapped = await installSingleAsset(page, fixture, work, shipment.issue, shipment.serial, true)
  expect(swapped.retired).toMatchObject({ assetId: oldAssetId, customerId: fixture.customer!.id })
  expect(swapped.replacement.assetId).not.toBe(oldAssetId)
  const history = await openAssetHistory(page, work)
  expect(history.items).toHaveLength(2)
  expect(history.items.filter((row: { asset: { endedAt: string | null } }) => row.asset.endedAt === null)).toHaveLength(1)
  await switchUser(page, fixture.admin)
  const intake = await intakeAsset(page, fixture, swapped.operationId)
  expect(intake).toMatchObject({ stockIdentityId: oldAssetId, legalOwner: 'ISP', state: 'RECEIVED_IN_INSPECTION' })
  const inspected = await inspectAsset(page, fixture, intake.id, 'SERVICEABLE', 'ISP')
  expect(inspected).toMatchObject({ stockIdentityId: oldAssetId, state: 'ACCEPTED', locationId: fixture.bin.id })
  return { work, shipment, swapped, history, inspected }
}

export async function reuseLoanForAnotherCustomer(page: Page, fixture: NumericJourney, assetId: string) {
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Tambah pelanggan', exact: true }).click()
  const customerName = 'Pelanggan kedua unit pakai ulang'
  await page.getByRole('textbox', { name: 'Nama', exact: true }).fill(customerName)
  await page.getByRole('textbox', { name: 'Alamat', exact: true }).fill('Alamat pelanggan kedua setelah reset perangkat')
  await selectNamed(page, 'Area pelanggan', fixture.area.optionLabel)
  await page.getByRole('textbox', { name: 'Longitude', exact: true }).fill('106.83')
  await page.getByRole('textbox', { name: 'Latitude', exact: true }).fill('-6.19')
  const customer = await confirmOperation(page, '/api/customers', 'Simpan')
  const work = await createAssetWorkOrder(page, fixture, 'PSB', customer)
  const shipment = await issueSingleAsset(page, fixture, work, fixture.recordedSerial)
  expect(shipment.issue.lines[0].dimension.stockIdentityId).toBe(assetId)
  await acknowledgeSingleAsset(page, fixture, work, shipment.serial, customer)
  const installed = await installSingleAsset(page, fixture, work, shipment.issue, shipment.serial)
  expect(installed).toMatchObject({ assetId, customerId: customer.id })
  const history = await openAssetHistory(page, work)
  expect(history.items).toHaveLength(1)
  expect(history.items[0].asset).toMatchObject({ assetId, customerId: customer.id, legalOwner: 'ISP', handoverState: 'ACCEPTED', endedAt: null })
  return { customer, work, shipment, installed, history }
}
