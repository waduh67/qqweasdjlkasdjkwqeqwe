import { expect, type Page } from '@playwright/test'
import { addLocation } from './catalog'
import { createRole, createUser, login } from './helpers'
import { confirmOperation, prepareMaterialWorkOrder, selectNamed } from './fulfillment'

export async function switchUser(page: Page, user: { email: string; password: string }) {
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await login(page, user)
}

export async function grantLocations(page: Page, user: { name: string; email: string }, locations: { id: string; label: string }[]) {
  await page.goto('/warehouse/catalog?tab=access')
  await selectNamed(page, 'Pengguna', `${user.name} · ${user.email}`)
  for (const location of locations) {
    await selectNamed(page, 'Lokasi', location.label)
    await page.getByRole('button', { name: 'Berikan akses langsung', exact: true }).click()
    const pending = page.waitForResponse(res => new URL(res.url()).pathname.includes('/settings/scopes/') && new URL(res.url()).pathname.endsWith(`/${location.id}`) && res.request().method() === 'PUT')
    await page.getByRole('button', { name: 'Berikan akses', exact: true }).click()
    const response = await pending
    expect(response.status()).toBe(200)
    expect(await response.json()).toMatchObject({ locationId: location.id, active: true })
    await expect(page.getByText('Pemberian akses langsung: aktif · Revisi 1', { exact: true })).toBeVisible()
  }
}

/** Empty tenant to actual dispatch: no API fixture writes or stock seeds. */
export async function prepareNumericJourney(page: Page) {
  const fixture = await prepareMaterialWorkOrder(page, { customerName: 'Pelanggan perjalanan numerik' })
  const { area, warehouse, bin, quarantine, technician, workOrder, cable, onu } = fixture
  const transit = await addLocation(page, { code: 'WO_TRANSIT', name: 'Pengiriman pekerjaan', area: area.optionLabel, kind: 'TRANSIT' })
  const field = await addLocation(page, { code: 'FIELD_STOCK', name: 'Barang teknisi', area: area.optionLabel, kind: 'TECHNICIAN', custodian: `${technician.name} · ${technician.email}` })
  const consumed = await addLocation(page, { code: 'CONSUMED', name: 'Kabel terpasang', area: area.optionLabel, kind: 'TRANSIT' })
  const installed = await addLocation(page, { code: 'CUSTOMER_INSTALLED', name: 'Perangkat terpasang', area: area.optionLabel, kind: 'CUSTOMER_SITE' })
  await grantLocations(page, technician, [warehouse, transit, field, quarantine])
  await createRole(page, 'Pemeriksa pekerjaan', ['workorder.order.view', 'workorder.order.approve', 'workorder.evidence.view'])
  const reviewer = await createUser(page, 'Pemeriksa pekerjaan', { areas: [area.checkboxLabel], prefix: 'QA' })
  await grantLocations(page, reviewer, [warehouse, transit, field, quarantine, consumed, installed])
  const root = `/api/work-orders/${workOrder.id}/materials`
  await page.goto(`/warehouse/requests?workOrderId=${workOrder.id}`)
  await page.getByRole('button', { name: 'Susun rencana material', exact: true }).click()
  await selectNamed(page, 'Material 1', `${cable.name} · ${cable.code}`)
  await page.getByRole('textbox', { name: 'Kebutuhan material 1 (m)', exact: true }).fill('100')
  await page.getByRole('button', { name: 'Tambah material', exact: true }).click()
  await selectNamed(page, 'Material 2', `${onu.name} · ${onu.code}`)
  await page.getByRole('textbox', { name: 'Kebutuhan material 2 (unit)', exact: true }).fill('1')
  await page.getByRole('button', { name: 'Tinjau rencana', exact: true }).click()
  await confirmOperation(page, `${root}/plan`, 'Simpan rencana', 'PUT')
  await page.getByRole('button', { name: 'Ajukan permintaan', exact: true }).click()
  const demand = await confirmOperation(page, `${root}/submit-request`, 'Konfirmasi pengajuan')
  await page.getByRole('button', { name: 'Cadangkan otomatis', exact: true }).click()
  await confirmOperation(page, `/api/v1/warehouse/material-requests/${demand.demandDocumentId}/reserve`, 'Konfirmasi reservasi')
  await page.getByRole('button', { name: 'Siapkan barang', exact: true }).click()
  await page.getByRole('checkbox', { name: 'Pilih REEL-WO', exact: true }).check()
  const serialChoice = page.getByRole('checkbox', { name: /^Pilih WO-ONU-/ })
  const serial = (await serialChoice.getAttribute('aria-label'))?.replace(/^Pilih /, '')
    ?? (await page.getByRole('group').filter({ has: serialChoice }).locator('legend').innerText()).split(' · ').at(-1)!
  await serialChoice.check()
  const scanner = page.getByRole('textbox', { name: /^Pindai WO-ONU-/ })
  await scanner.fill(serial); await scanner.press('Enter')
  await page.getByRole('button', { name: 'Tinjau pilihan', exact: true }).click()
  const picked = await confirmOperation(page, `${root}/pick`, 'Siapkan pilihan')
  await page.getByRole('button', { name: picked.code, exact: true }).click()
  await page.getByRole('textbox', { name: 'Catatan pengiriman / pembatalan', exact: true }).fill('Diukur 100 m dan satu ONU untuk pelanggan ini')
  await page.getByRole('checkbox', { name: `Konfirmasi penerima: ${technician.name}`, exact: true }).check()
  await page.getByRole('button', { name: 'Kirim barang', exact: true }).click()
  const issue = await confirmOperation(page, `${root}/dispatch`, 'Konfirmasi kirim')
  expect(issue.lines).toHaveLength(2)
  return { ...fixture, reviewer, transit, field, consumed, installed, issue, serial, root }
}

export type NumericJourney = Awaited<ReturnType<typeof prepareNumericJourney>>

export async function acknowledgeNumericJourney(page: Page, fixture: NumericJourney) {
  await switchUser(page, fixture.technician)
  await page.goto(`/my-materials?workOrderId=${fixture.workOrder.id}`)
  await expect(page.getByRole('link', { name: 'Stok & Perangkat', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  await selectNamed(page, 'Barang yang diterima', 'Kabel drop · REEL-WO')
  await page.getByRole('textbox', { name: 'Jumlah diterima (m)', exact: true }).fill('100')
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan', exact: true }).fill('Ukuran fisik kabel 100 m cocok')
  await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
  await confirmOperation(page, `${fixture.root}/acknowledge`, 'Terima material')
  await page.getByRole('button', { name: 'Terima barang', exact: true }).click()
  await page.getByRole('textbox', { name: 'Serial perangkat', exact: true }).fill(fixture.serial)
  await page.getByRole('textbox', { name: 'Serial perangkat', exact: true }).press('Enter')
  await page.getByRole('textbox', { name: 'Jumlah diterima (unit)', exact: true }).fill('1')
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan', exact: true }).fill('Serial fisik ONU cocok')
  await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
  await confirmOperation(page, `${fixture.root}/acknowledge`, 'Terima material')
  await expect(page.getByRole('button', { name: 'Terima barang', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Detail tugas dan bukti', exact: true }).click()
  await confirmOperation(page, `/api/work-orders/${fixture.workOrder.id}/start`, 'Mulai')
}

export async function consumeAndInstallNumericJourney(page: Page, fixture: NumericJourney) {
  await page.goto(`/my-materials?workOrderId=${fixture.workOrder.id}`)
  await page.getByRole('button', { name: 'Catat pemakaian', exact: true }).click()
  await selectNamed(page, 'Barang diterima 1', `Kabel drop · REEL-WO · ${fixture.issue.code}`)
  await page.getByRole('textbox', { name: 'Jumlah dipakai 1 (m)', exact: true }).fill('82,500')
  await page.getByRole('textbox', { name: 'Referensi bukti pemakaian', exact: true }).fill('Hasil ukur terpasang 82,500 m')
  await page.getByRole('button', { name: 'Tinjau pemakaian', exact: true }).click()
  const usage = await confirmOperation(page, `${fixture.root}/report-use`, 'Catat pemakaian', 'POST', 'Konfirmasi pemakaian material')
  await page.getByRole('link', { name: 'Pasang perangkat pada aset pelanggan', exact: true }).click()
  await page.getByRole('button', { name: 'Pasang perangkat dari gudang', exact: true }).click()
  await selectNamed(page, 'WO pemasangan', `${fixture.workOrder.code} · PSB · IN_PROGRESS`)
  await selectNamed(page, 'Perangkat yang sudah diterima', `${fixture.serial} · ONU pelanggan · ${fixture.issue.code}`)
  await page.getByRole('textbox', { name: 'Serial perangkat', exact: true }).fill(fixture.serial)
  await page.getByRole('textbox', { name: 'Serial perangkat', exact: true }).press('Enter')
  await page.getByRole('combobox', { name: 'Kepemilikan perangkat', exact: true }).selectOption('LOAN')
  await page.getByRole('button', { name: 'Tinjau pemasangan', exact: true }).click()
  const installation = await confirmOperation(page, `/api/customers/${fixture.customer!.id}/assets/install`, 'Pasang perangkat')
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Milik ISP')
  return { usage, installation }
}

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=', 'base64')

export async function uploadNumericProof(page: Page, fixture: NumericJourney) {
  await page.goto(`/my-work-orders/${fixture.workOrder.id}`)
  const root = `/api/work-orders/${fixture.workOrder.id}`
  await page.getByRole('textbox', { name: 'Nama penanda tangan', exact: true }).fill(fixture.customer!.name)
  await page.getByLabel('Berkas tanda tangan', { exact: true }).setInputFiles({ name: 'tanda-tangan.png', mimeType: 'image/png', buffer: png })
  await confirmOperation(page, `${root}/signature`, 'Simpan tanda tangan', 'PUT')
  for (const kind of ['FAT', 'ODP', 'DROPCORE', 'ONT', 'ONU', 'OPTICAL_BEFORE', 'OPTICAL_AFTER', 'TECHNICIAN_SIGNATURE', 'LOCATION']) {
    await page.getByRole('combobox', { name: 'Jenis', exact: true }).selectOption(kind)
    await page.getByLabel('Berkas foto bukti', { exact: true }).setInputFiles({ name: `${kind}.png`, mimeType: 'image/png', buffer: png })
    await confirmOperation(page, `${root}/evidence`, 'Unggah foto')
  }
  await expect(page.getByRole('img', { name: `Tanda tangan ${fixture.customer!.name}`, exact: true })).toBeVisible()
}

export async function acceptNumericHandover(page: Page, fixture: NumericJourney) {
  await page.getByRole('link', { name: 'Pasang perangkat dan periksa aset pelanggan', exact: true }).click()
  await page.getByRole('button', { name: 'Terima serah-terima pelanggan', exact: true }).click()
  await selectNamed(page, 'WO tindakan perangkat', `${fixture.workOrder.code} · PSB · IN_PROGRESS`)
  await page.getByRole('button', { name: 'Tinjau tindakan', exact: true }).click()
  await confirmOperation(page, `/api/customers/${fixture.customer!.id}/assets/handover`, 'Terima serah-terima pelanggan')
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Diterima pelanggan')
}

export async function returnNumericRemnant(page: Page, fixture: NumericJourney) {
  await page.goto(`/my-materials?workOrderId=${fixture.workOrder.id}`)
  await page.getByRole('button', { name: 'Kembalikan sisa', exact: true }).click()
  await page.getByRole('textbox', { name: 'Jumlah dikembalikan (m)', exact: true }).fill('17,500')
  await selectNamed(page, 'Karantina tujuan', `${fixture.quarantine.code} · ${fixture.quarantine.name}`)
  await page.getByRole('textbox', { name: 'Referensi bukti pengembalian', exact: true }).fill('Potongan sisa diukur 17,500 m')
  await page.getByRole('textbox', { name: 'Alasan pengembalian', exact: true }).fill('Pemasangan selesai; sisa utuh dikembalikan')
  await page.getByRole('button', { name: 'Tinjau pengembalian', exact: true }).click()
  const residual = await confirmOperation(page, `${fixture.root}/return`, 'Kirim pengembalian')
  await switchUser(page, fixture.admin)
  await page.goto('/warehouse/returns')
  await page.getByRole('button', { name: 'Lihat sisa menunggu penerimaan', exact: true }).click()
  await page.getByRole('button', { name: 'Akui penerimaan sisa', exact: true }).click()
  await page.getByRole('textbox', { name: 'Bukti penerimaan serah-terima', exact: true }).fill('Petugas menerima dan mengukur 17,500 m')
  await page.getByRole('button', { name: 'Tinjau serah-terima', exact: true }).click()
  await confirmOperation(page, `${fixture.root}/residuals/acknowledge`, 'Akui penerimaan')
  await page.getByRole('button', { name: 'Terima retur baru', exact: true }).click()
  const source = page.getByRole('combobox', { name: 'Sumber retur', exact: true })
  await expect(source.locator(`option[value="${residual.id}"]`)).toBeAttached()
  await source.selectOption(residual.id)
  await page.getByRole('textbox', { name: 'Referensi bukti penerimaan retur', exact: true }).fill('Selubung kabel utuh dan panjang sesuai')
  await page.getByRole('button', { name: 'Tinjau penerimaan retur', exact: true }).click()
  const intake = await confirmOperation(page, '/api/v1/warehouse/returns', 'Catat retur')
  await page.getByRole('button', { name: 'Periksa retur', exact: true }).click()
  await page.getByRole('textbox', { name: 'Hasil ukur fisik (m)', exact: true }).fill('17,500')
  await page.getByRole('combobox', { name: 'Kondisi hasil inspeksi', exact: true }).selectOption('SERVICEABLE')
  await selectNamed(page, 'Rak barang layak pakai', fixture.bin.label)
  await page.getByRole('textbox', { name: 'Referensi bukti tindakan retur', exact: true }).fill('Potongan layak pakai dikembalikan ke rak')
  await page.getByRole('button', { name: 'Tinjau tindakan retur', exact: true }).click()
  const inspection = await confirmOperation(page, `/api/v1/warehouse/returns/${intake.id}/inspect`, 'Catat tindakan retur')
  expect(inspection).toMatchObject({ state: 'ACCEPTED', quantityBase: '17500', stockIdentityId: intake.stockIdentityId })
  return { residual, intake, inspection }
}

export async function completeNumericJourney(page: Page, fixture: NumericJourney) {
  await switchUser(page, fixture.technician)
  await page.goto(`/my-work-orders/${fixture.workOrder.id}`)
  for (const label of ['FAT', 'ODP', 'Dropcore', 'ONT', 'ONU', 'Optik sebelum', 'Optik sesudah', 'Tanda tangan teknisi', 'Persetujuan pelanggan', 'Lokasi']) {
    const control = page.getByRole('combobox', { name: label, exact: true })
    const option = control.locator('option:not([value=""]):not([disabled])').first()
    await expect(option).toBeAttached()
    await control.selectOption((await option.getAttribute('value'))!)
  }
  await confirmOperation(page, `/api/work-orders/${fixture.workOrder.id}/complete`, 'Kirim untuk persetujuan')
  await switchUser(page, fixture.reviewer)
  await page.goto(`/work-orders/${fixture.workOrder.id}`)
  await expect(page.getByRole('button', { name: 'Catat pemakaian', exact: true })).toHaveCount(0)
  await confirmOperation(page, `/api/work-orders/${fixture.workOrder.id}/approve`, 'Setujui')
  await page.getByRole('button', { name: 'Lihat material persetujuan QA', exact: true }).click()
  const review = page.getByRole('region', { name: 'Material persetujuan QA', exact: true })
  await expect(review).toContainText('82,500 m')
  await expect(review).toContainText(fixture.serial)
  await expect(page.getByRole('button', { name: 'Setujui', exact: true })).toHaveCount(0)
}

export async function assertNumericStock(page: Page, fixture: NumericJourney) {
  await switchUser(page, fixture.admin)
  const pending = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/stock' && new URL(res.url()).searchParams.get('bucket') === 'AVAILABLE')
  await page.goto('/warehouse/stock?bucket=AVAILABLE')
  const response = await pending
  expect(response.status()).toBe(200)
  const stock = await response.json()
  expect(stock.items.find((item: { skuId: string }) => item.skuId === fixture.cable.id)).toMatchObject({ available: { quantityBase: '917500' } })
  expect(stock.items.find((item: { skuId: string }) => item.skuId === fixture.onu.id)).toMatchObject({ available: { quantityBase: '9' } })
  await expect(page.getByRole('row').filter({ hasText: 'Kabel drop' })).toContainText('917,500 m')
  await expect(page.getByRole('row').filter({ hasText: 'ONU pelanggan' })).toContainText('9 unit')
  return stock
}
