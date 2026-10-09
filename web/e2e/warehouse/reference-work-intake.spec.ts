import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { record, text, uuid } from '../../src/api/warehouse/codec'
import { referenceWorkDetail, referenceWorkOrder, workType } from '../../src/api/warehouse/reference'
import { referenceWorkIntake } from '../../src/api/warehouse/referenceWorkIntake'
import { login } from './helpers'
import { captureReferenceDocument, referenceTenant } from './reference-setup'

async function capture(page: Page, info: TestInfo, name: string) {
  const original = page.viewportSize()
  if (!original) throw new Error('Browser viewport missing')
  const widths = info.project.name === 'warehouse-desktop' ? [390, 1440, 1920] : [375]
  for (const width of widths) {
    await page.setViewportSize({ width, height: original.height })
    await captureReferenceDocument(page, info, width + '-' + name)
  }
  await page.setViewportSize(original)
}

async function choose(page: Page, label: string, name: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click()
  await page.getByRole('option', { name, exact: true }).click()
}

for (const theme of ['light', 'dark']) {
test(theme + ' PSB and helpdesk retain their source through operator dispatch and technician completion', async ({ page }, info) => {
  test.setTimeout(420_000)
  await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
  const fixture = await referenceTenant(page, 'intake')
  const admin = await fixture.createMember('Admin')
  const technician = await fixture.createMember('Teknisi FO')
  const plan = record(await fixture.command('/api/catalog/plans', {
    name: 'Paket rumah', price: 150000, downMbps: 20, upMbps: 10, serviceTypes: ['PPPOE'],
  }))
  const installation = workType(await fixture.command('/api/v2/work-orders/types', {
    expectedRevision: 0, name: 'Kunjungan pemasangan', workType: 'PSB', materialRequired: false,
    photoSlots: ['Bukti pemasangan'], active: true,
  }))
  const repair = workType(await fixture.command('/api/v2/work-orders/types', {
    expectedRevision: 0, name: 'Kunjungan gangguan', workType: 'REPAIR', materialRequired: false,
    photoSlots: ['Bukti perbaikan'], active: true,
  }))
  await login(page, fixture.owner)
  await page.goto('/express-psb')
  await page.getByLabel('Nama *', { exact: true }).fill('Budi Pelanggan')
  await page.getByLabel('Alamat *', { exact: true }).fill('Jalan Pelanggan No. 12')
  await page.getByLabel('Longitude', { exact: true }).fill('106.8')
  await page.getByLabel('Latitude', { exact: true }).fill('-6.2')
  await page.getByRole('combobox', { name: 'Area', exact: true }).selectOption(fixture.areaId)
  await page.getByLabel('Paket *', { exact: true }).selectOption(uuid(plan.id))
  await page.getByLabel('Judul WO (opsional)', { exact: true }).fill('Pasang layanan Budi')
  await page.getByLabel('Catatan pemasangan (opsional)', { exact: true }).fill('Hubungi pelanggan lalu ukur sinyal dan dokumentasikan pemasangan.')
  await expect(page.getByText('Teknisi (opsional, bisa lebih dari satu)', { exact: true })).toHaveCount(0)
  const psbResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/onboarding/psb' && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Buat PSB', exact: true }).click()
  const created = await psbResponse
  expect(created.status()).toBe(201)
  expect(record(created.request().postDataJSON()).assignees).toEqual([])
  const psb = record(await created.json())
  const psbId = uuid(psb.workOrderId), customerId = uuid(psb.customerId)
  await expect(page.getByRole('link', { name: 'Tugaskan teknisi', exact: true })).toHaveAttribute('href', '/work-orders/' + psbId)
  await page.getByRole('link', { name: 'Tugaskan teknisi', exact: true }).click()
  await expect(page.getByText('Belum ditugaskan', { exact: true })).toBeVisible()
  expect(referenceWorkIntake(await fixture.get('/api/v2/work-orders/intake/' + psbId)).sourceId).toBe(uuid(psb.subscriptionId))
  expect((await page.request.get('/api/v2/work-orders/' + psbId, { headers: technician.headers })).status()).toBe(404)

  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, admin.account)
  await page.goto('/work-orders')
  await expect(page.getByRole('tab', { name: 'Belum ditugaskan', exact: true })).toHaveAttribute('aria-selected', 'true')
  await page.getByLabel('Cari antrean', { exact: true }).fill(text(psb.workOrderCode))
  await expect(page.getByRole('link', { name: text(psb.workOrderCode) + ' · Pasang layanan Budi', exact: true })).toBeVisible()
  await capture(page, info, 'operator-intake-queue')
  await page.getByRole('link', { name: text(psb.workOrderCode) + ' · Pasang layanan Budi', exact: true }).click()
  await expect(page.getByText('Pelanggan: Budi Pelanggan', { exact: false })).toBeVisible()
  await capture(page, info, 'pending-work')
  await page.getByRole('button', { name: 'Tugaskan teknisi', exact: true }).click()
  await choose(page, 'Jenis pekerjaan', installation.name)
  await expect(page.getByRole('combobox', { name: 'Area pekerjaan', exact: true })).toHaveValue('Area QA · QA')
  await choose(page, 'Teknisi penanggung jawab', technician.user.name + ' · ' + technician.user.email)
  await page.getByLabel('Jadwal pekerjaan', { exact: true }).fill('2026-10-12T09:30')
  await capture(page, info, 'dispatch-form')
  await page.getByRole('button', { name: 'Tinjau penugasan', exact: true }).click()
  await capture(page, info, 'dispatch-review')
  const dispatchPath = '/api/v2/work-orders/intake/' + psbId + '/dispatch'
  const attempts: { readonly key: string | undefined; readonly body: string | null }[] = []
  await page.route('**' + dispatchPath, async route => {
    attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
    if (attempts.length === 1) { expect((await route.fetch()).status()).toBe(200); await route.abort('connectionreset') }
    else await route.continue()
  })
  await page.getByRole('button', { name: 'Tugaskan teknisi', exact: true }).click()
  await expect(page.getByText('Penyimpanan belum terkonfirmasi', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeDisabled()
  const dispatchedResponse = page.waitForResponse(response => new URL(response.url()).pathname === dispatchPath && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Coba transaksi yang sama', exact: true }).click()
  const dispatched = await dispatchedResponse
  expect(dispatched.ok()).toBeTruthy()
  expect(referenceWorkOrder(await dispatched.json()).id).toBe(psbId)
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.key).toBeTruthy()
  expect(attempts[1]).toEqual(attempts[0])
  await page.unroute('**' + dispatchPath)
  await expect(page.getByText('Teknisi: ' + technician.user.name, { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, technician.account)
  await page.goto('/my-work-orders')
  await page.getByRole('link', { name: text(psb.workOrderCode) + ' · Pasang layanan Budi', exact: true }).click()
  await expect(page.getByRole('heading', { name: '1. Instruksi pekerjaan', exact: true })).toBeVisible()
  await page.getByLabel('Unggah foto Bukti pemasangan', { exact: true }).setInputFiles(fileURLToPath(new URL('./fixtures/inspection.png', import.meta.url)))
  await page.getByRole('button', { name: 'Unggah foto', exact: true }).click()
  await expect(page.getByLabel('Ganti foto Bukti pemasangan', { exact: true })).toBeVisible()
  await page.getByLabel('Catatan hasil pekerjaan', { exact: true }).fill('Pemasangan selesai, sinyal baik.')
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await page.getByRole('button', { name: 'Kirim hasil dan selesai', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Hasil pekerjaan tersimpan', exact: true })).toBeVisible()
  await capture(page, info, 'installation-completed')
  expect(referenceWorkDetail(await fixture.get('/api/v2/work-orders/' + psbId)).workOrder.state).toBe('COMPLETED')

  await fixture.command('/api/portal-admin/customers/' + customerId + '/credential', { login: 'budi', password: 'Portal-qa12345' })
  const portalResponse = await page.request.post('/api/portal/auth/login', { data: { identifier: 'budi', password: 'Portal-qa12345', tenant: fixture.slug } })
  expect(portalResponse.ok()).toBeTruthy()
  const portalTokens = record(record(await portalResponse.json()).tokens)
  const portalHeaders = { Authorization: 'Bearer ' + text(portalTokens.accessToken) }
  const ticket = record(record(await fixture.command('/api/portal/me/tickets', {
    category: 'KONEKSI_PUTUS', subject: 'LOS merah Budi', description: 'Periksa sambungan pelanggan.',
  }, portalHeaders)).ticket)
  const ticketId = uuid(ticket.id)
  const escalation = record(record(await fixture.command('/api/helpdesk/tickets/' + ticketId + '/escalate', { priority: 'HIGH' })).ticket)
  const repairId = uuid(escalation.workOrderId)
  const intake = referenceWorkIntake(await fixture.get('/api/v2/work-orders/intake/' + repairId, admin.headers))
  expect(intake.source).toBe('HELPDESK')
  expect(intake.sourceId).toBe(ticketId)
  expect(intake.areaId).toBe(fixture.areaId)
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, admin.account)
  await page.goto('/work-orders/' + repairId)
  await expect(page.getByText('Belum ditugaskan', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Tugaskan teknisi', exact: true }).click()
  await choose(page, 'Jenis pekerjaan', repair.name)
  await choose(page, 'Teknisi penanggung jawab', technician.user.name + ' · ' + technician.user.email)
  await page.getByRole('button', { name: 'Tinjau penugasan', exact: true }).click()
  await page.getByRole('button', { name: 'Tugaskan teknisi', exact: true }).click()
  await expect(page.getByText('Teknisi: ' + technician.user.name, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, technician.account)
  await page.goto('/my-work-orders/' + repairId)
  await page.getByLabel('Unggah foto Bukti perbaikan', { exact: true }).setInputFiles(fileURLToPath(new URL('./fixtures/inspection.png', import.meta.url)))
  await page.getByRole('button', { name: 'Unggah foto', exact: true }).click()
  await expect(page.getByLabel('Ganti foto Bukti perbaikan', { exact: true })).toBeVisible()
  await page.getByLabel('Catatan hasil pekerjaan', { exact: true }).fill('Konektor diperbaiki, layanan kembali normal.')
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await page.getByRole('button', { name: 'Kirim hasil dan selesai', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Hasil pekerjaan tersimpan', exact: true })).toBeVisible()
  const resolved = record(record(await fixture.get('/api/helpdesk/tickets/' + ticketId)).ticket)
  expect(uuid(resolved.workOrderId)).toBe(repairId)
  expect(referenceWorkDetail(await fixture.get('/api/v2/work-orders/' + repairId)).workOrder.state).toBe('COMPLETED')
})
}
