import { expect, type Page } from '@playwright/test'
import { confirmOperation, selectNamed } from './fulfillment'
import { createRole, createUser } from './helpers'
import { grantLocations, switchUser, type NumericJourney } from './numeric-journey'

export async function prepareCounter(page: Page, fixture: NumericJourney) {
  const role = 'Penghitung stok terbatas'
  await createRole(page, role, ['inventory.count.view', 'inventory.count.manage'])
  const counter = await createUser(page, role, { areas: [fixture.area.checkboxLabel], prefix: 'Penghitung' })
  await grantLocations(page, counter, [fixture.bin, fixture.transit])
  return counter
}

export async function startBlindCount(page: Page, location: { id: string; label: string }, counter: { name: string }) {
  await page.goto('/warehouse/counts')
  await page.getByRole('button', { name: 'Buat stock opname', exact: true }).click()
  await selectNamed(page, 'Lokasi stock opname', location.label)
  await page.getByRole('textbox', { name: 'Alasan stock opname', exact: true }).fill('Hitung fisik kabel tanpa melihat saldo buku')
  const control = page.getByRole('combobox', { name: 'Barang dihitung 1', exact: true })
  const option = control.getByRole('option', { name: /^Kabel drop · CABLE · REEL-WO/ }).first()
  await expect(option).toBeAttached()
  await control.selectOption((await option.getAttribute('value'))!)
  await selectNamed(page, 'Penghitung 1', counter.name)
  await page.getByRole('button', { name: 'Tinjau stock opname', exact: true }).click()
  const count = await confirmOperation(page, '/api/v1/warehouse/counts', 'Simpan stock opname')
  expect(count).toMatchObject({ partialLocation: true, locationId: location.id, state: 'DRAFT' })
  expect(count.entries).toHaveLength(1)
  expect(count.entries[0]).not.toHaveProperty('quantityBase')
  await page.getByRole('button', { name: 'Mulai penghitungan', exact: true }).click()
  await confirmOperation(page, `/api/v1/warehouse/counts/${count.id}/start`, 'Konfirmasi stock opname')
  return count
}

export async function recordBlindCount(page: Page, counter: { email: string; password: string }, count: { id: string }, quantity: string) {
  await switchUser(page, counter)
  const response = page.waitForResponse(res => new URL(res.url()).pathname === `/api/v1/warehouse/counts/${count.id}/details`)
  await page.goto(`/warehouse/counts?countId=${count.id}`)
  const details = await (await response).json()
  expect(JSON.stringify(details)).not.toMatch(/bookQuantity|expectedQuantity|availableQuantity/)
  expect(details.count.entries[0]).not.toHaveProperty('quantityBase')
  await expect(page.getByRole('link', { name: 'Stok & Perangkat', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Lihat perbandingan setelah pengajuan', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Catat hasil Kabel drop · CABLE · REEL-WO', exact: true }).click()
  await page.getByRole('textbox', { name: 'Hasil hitung fisik (m)', exact: true }).fill(quantity)
  await page.getByRole('textbox', { name: 'Keterangan penghitungan', exact: true }).fill('Pengukuran fisik dengan meteran di lokasi')
  await page.getByRole('textbox', { name: 'Referensi lembar hitung', exact: true }).fill(`LEMBAR-${quantity}`)
  await page.getByRole('button', { name: 'Tinjau hasil hitung', exact: true }).click()
  const observed = await confirmOperation(page, `/api/v1/warehouse/counts/${count.id}/observe`, 'Simpan hasil fisik')
  expect(observed.state).toBe('COUNTING')
  await expect(page.getByRole('button', { name: 'Ajukan hasil hitung', exact: true })).toHaveCount(0)
}

export async function submitBlindCount(page: Page, fixture: NumericJourney, count: { id: string }, stale = false) {
  await switchUser(page, fixture.admin)
  await page.goto(`/warehouse/counts?countId=${count.id}`)
  await page.getByRole('button', { name: 'Ajukan hasil hitung', exact: true }).click()
  const path = `/api/v1/warehouse/counts/${count.id}/submit`
  if (!stale) return confirmOperation(page, path, 'Konfirmasi stock opname')
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === path && res.request().method() === 'POST'),
    page.getByRole('button', { name: 'Konfirmasi stock opname', exact: true }).click(),
  ])
  expect(response.status()).toBe(409)
  expect(await response.json()).toMatchObject({ code: 'COUNT_STALE' })
  await page.getByRole('button', { name: 'Muat ulang dokumen', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Detail stock opname', exact: true })).toContainText('Perlu hitung ulang')
  await expect(page.getByRole('button', { name: 'Mulai hitung ulang', exact: true })).toBeVisible()
}
