import { expect, type Page } from '@playwright/test'
import { createRole, createUser } from './helpers'
import { confirmOperation, selectNamed } from './fulfillment'

/** All policy, user and warehouse grants are created through real UI. */
export async function setupDiscrepancyApprover(page: Page, areaLabel: string, scopes: { id: string; label: string }[], policyLocations: { id: string; label: string }[]) {
  const role = 'Pemeriksa selisih independen'
  await createRole(page, role, ['inventory.approval.view', 'inventory.approval.decide'])
  const checker = await createUser(page, role, { areas: [areaLabel], prefix: 'Pemeriksa' })
  await page.goto('/warehouse/catalog?tab=access')
  await selectNamed(page, 'Pengguna', `${checker.name} · ${checker.email}`)
  let checkerId = ''
  for (const location of scopes) {
    await selectNamed(page, 'Lokasi', location.label)
    await page.getByRole('button', { name: 'Berikan akses langsung', exact: true }).click()
    const response = page.waitForResponse(res => new URL(res.url()).pathname.endsWith(`/${location.id}`) && new URL(res.url()).pathname.includes('/settings/scopes/') && res.request().method() === 'PUT')
    await page.getByRole('button', { name: 'Berikan akses', exact: true }).click()
    const granted = await response
    expect(granted.status()).toBe(200)
    const body = await granted.json()
    expect(body).toMatchObject({ locationId: location.id, revision: 1, active: true })
    checkerId = body.userId
    await expect(page.getByText('Pemberian akses langsung: aktif · Revisi 1', { exact: true })).toBeVisible()
  }
  await page.goto('/warehouse/settings')
  await page.getByRole('button', { name: 'Buat kebijakan persetujuan', exact: true }).click()
  for (const location of policyLocations) {
    await selectNamed(page, 'Lokasi kebijakan', location.label)
    await page.getByRole('button', { name: 'Tambahkan lokasi kebijakan', exact: true }).click()
  }
  await page.getByRole('combobox', { name: 'Jenis transaksi aturan 1', exact: true }).selectOption('ADJUSTMENT')
  await selectNamed(page, 'Pemeriksa aturan 1 tahap 1', checker.name)
  await page.getByRole('button', { name: 'Tambah pemeriksa aturan 1 tahap 1', exact: true }).click()
  await page.getByRole('button', { name: 'Tinjau kebijakan', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Konfirmasi perubahan kebijakan', exact: true })).toContainText(checker.name)
  const policy = await confirmOperation(page, '/api/v1/warehouse/settings/policy', 'Simpan kebijakan', 'PUT')
  expect(policy).toMatchObject({ revision: 1, warehouseIds: policyLocations.map(row => row.id), rules: [{ operation: 'ADJUSTMENT', tiers: [{ minimumMinor: '1', userIds: [checkerId], roleIds: [] }] }] })
  await expect(page.getByRole('region', { name: 'Kebijakan tersimpan', exact: true })).toContainText('Versi 1')
  return { ...checker, id: checkerId }
}
