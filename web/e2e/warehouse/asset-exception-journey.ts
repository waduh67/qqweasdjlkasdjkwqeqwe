import { expect, type Page, type TestInfo } from '@playwright/test'
import { setupDiscrepancyApprover } from './approvals'
import { openAssetHistory } from './asset-journey'
import { addLocation } from './catalog'
import { confirmOperation, selectNamed } from './fulfillment'
import { createRole, createUser } from './helpers'
import { grantLocations, switchUser, type NumericJourney } from './numeric-journey'

/** Office creation and independent rejection through the real customer/approval UI.
 * The later physical journey must still see exactly the original installation. */
export async function rejectAssetException(page: Page, fixture: NumericJourney, kind: 'title' | 'loss', testInfo: TestInfo) {
  await switchUser(page, fixture.admin)
  const lost = kind === 'loss' ? await addLocation(page, { code: 'ASSET_LOST', name: 'Perangkat pelanggan hilang', area: fixture.area.optionLabel, kind: 'LOST' }) : null
  const scopes = [fixture.installed, ...(lost ? [lost] : [])]
  const checker = await setupDiscrepancyApprover(page, fixture.area.checkboxLabel, scopes, scopes, kind === 'title' ? 'TITLE_REACQUISITION' : 'LOSS')
  const role = 'Pengaju pengecualian aset kantor'
  await createRole(page, role, ['customer.customer.view', 'customer.onu.view', 'workorder.order.view', 'workorder.evidence.view',
    'inventory.approval.view', 'inventory.approval.request', 'inventory.custody.manage', 'inventory.location.view'])
  const requester = await createUser(page, role, { areas: [fixture.area.checkboxLabel], prefix: 'Pengaju' })
  await grantLocations(page, requester, scopes)
  await switchUser(page, requester)
  await page.goto('/customers')
  const historyRead = page.waitForResponse(res => new URL(res.url()).pathname === `/api/customers/${fixture.customer!.id}/assets/workbench/history`)
  await page.getByRole('button', { name: fixture.customer!.name, exact: true }).click()
  const historyResponse = await historyRead
  expect(historyResponse.status()).toBe(200)
  const before = (await historyResponse.json()).items[0].asset
  expect(before).toMatchObject({ customerId: fixture.customer!.id, workOrderId: fixture.workOrder.id, handoverState: 'ACCEPTED', endedAt: null })
  await expect(page.getByRole('button', { name: 'Pasang perangkat dari gudang', exact: true })).toHaveCount(0)
  const contextRead = page.waitForResponse(res => new URL(res.url()).pathname === `/api/customers/${fixture.customer!.id}/assets/${before.id}/exceptions/context`)
  const label = kind === 'title' ? 'Ajukan koreksi kepemilikan' : 'Ajukan kehilangan perangkat'
  await page.getByRole('button', { name: label, exact: true }).click()
  const contextResponse = await contextRead
  expect(contextResponse.status()).toBe(200)
  const context = await contextResponse.json()
  expect(context.ownership).toMatchObject({ assignmentId: before.id, assetId: before.assetId, assignmentRevision: before.revision, titleRevision: before.titleRevision })
  expect(context.workOrder.signature.id).toMatch(/^[a-f0-9-]{36}$/)
  if (lost) await selectNamed(page, 'Lokasi kehilangan', lost.label)
  await page.getByRole('textbox', { name: 'Alasan pengajuan', exact: true }).fill('Usulan dari bukti pelanggan untuk pemeriksaan independen')
  await page.getByRole('button', { name: 'Tinjau pengajuan', exact: true }).click()
  const documentPath = `/api/v1/warehouse/${kind === 'title' ? 'asset-title-corrections' : 'asset-losses'}`
  const proposal = await confirmOperation(page, documentPath, 'Catat pengajuan', 'POST', label)
  const documentId = kind === 'title' ? proposal.documentId : proposal.id
  expect(proposal.assignmentId).toBe(before.id)
  await expect(page.getByText('Pengajuan memerlukan persetujuan petugas independen. Stok, kepemilikan, dan kewajiban pengembalian belum berubah.', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath(`${kind}-office-proposal-created.png`), fullPage: true })
  await page.getByRole('link', { name: 'Lanjutkan ke persetujuan', exact: true }).click()
  await page.getByRole('button', { name: 'Periksa persyaratan persetujuan', exact: true }).click()
  await page.getByRole('button', { name: 'Ajukan persetujuan', exact: true }).click()
  const approval = await confirmOperation(page, '/api/v1/warehouse/approvals/request', 'Kirim permintaan persetujuan')
  expect(approval).toMatchObject({ status: 'PENDING', sourceDocumentId: documentId, sourceRevision: 0 })
  await expect(page.getByRole('button', { name: 'Setujui permintaan', exact: true })).toHaveCount(0)
  await switchUser(page, checker)
  await page.goto(`/warehouse/approvals?approvalId=${approval.requestId}`)
  await page.getByRole('button', { name: 'Kembalikan untuk perbaikan', exact: true }).click()
  await page.getByRole('textbox', { name: 'Alasan keputusan / referensi pemeriksaan', exact: true }).fill('Bukti perlu diperbarui; usulan lama dipertahankan untuk audit')
  await page.getByRole('button', { name: 'Tinjau keputusan', exact: true }).click()
  const rejected = await confirmOperation(page, '/api/v1/warehouse/approvals/decide', 'Simpan keputusan')
  expect(rejected.status).toBe('REWORK_REQUIRED')
  await switchUser(page, requester)
  const rejectedRead = page.waitForResponse(res => new URL(res.url()).pathname === `/api/v1/warehouse/approvals/${approval.requestId}/details`)
  await page.goto(`/warehouse/approvals?approvalId=${approval.requestId}`)
  expect(await (await rejectedRead).json()).toMatchObject({ actions: { canRework: false } })
  await page.screenshot({ path: testInfo.outputPath(`${kind}-sealed-proposal-rejected.png`), fullPage: true })
  await switchUser(page, fixture.technician)
  const after = await openAssetHistory(page, { id: fixture.workOrder.id, customerId: fixture.customer!.id })
  expect(after.items.find((row: { asset: { id: string } }) => row.asset.id === before.id).asset).toEqual(before)
}
