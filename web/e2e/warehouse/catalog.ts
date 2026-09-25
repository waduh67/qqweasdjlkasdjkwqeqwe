import { expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { login } from './helpers'

export async function setupOwnArea(page: Page, admin: { email: string; password: string }) {
  const code = `A-${randomUUID().slice(0, 6).toUpperCase()}`
  const name = 'Area gudang uji'
  await page.goto('/areas')
  await page.getByRole('textbox', { name: 'Kode', exact: true }).fill(code)
  await page.getByRole('textbox', { name: 'Nama', exact: true }).fill(name)
  const response = page.waitForResponse(res => new URL(res.url()).pathname === '/api/areas' && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'Tambah', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await page.goto('/users')
  const self = page.getByRole('row').filter({ has: page.getByRole('gridcell', { name: admin.email, exact: true }) })
  await self.getByRole('button', { name: 'Aksi baris' }).click()
  await page.getByRole('menuitem', { name: 'Akses', exact: true }).click()
  const checkboxLabel = `${code} — ${name}`
  await page.getByRole('checkbox', { name: checkboxLabel, exact: true }).check()
  const access = page.waitForResponse(res => /\/api\/users\/[^/]+\/access$/.test(new URL(res.url()).pathname) && res.request().method() === 'PUT')
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  expect((await access).ok()).toBeTruthy()
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await login(page, admin)
  return { code, name, checkboxLabel, optionLabel: `${name} · ${code}` }
}

export async function addLocation(page: Page, input: { code: string; name: string; area: string; kind?: string; parent?: string; custodian?: string }) {
  await page.goto('/warehouse/catalog?tab=locations')
  await page.getByRole('button', { name: 'Tambah lokasi', exact: true }).click()
  await page.getByRole('textbox', { name: 'Kode lokasi', exact: true }).fill(input.code)
  await page.getByRole('textbox', { name: 'Nama lokasi', exact: true }).fill(input.name)
  if (input.kind) await page.getByRole('combobox', { name: 'Jenis lokasi', exact: true }).selectOption(input.kind)
  if (input.parent) {
    await expect(page.getByRole('option', { name: input.parent, exact: true })).toBeAttached()
    await page.getByRole('combobox', { name: 'Lokasi induk', exact: true }).selectOption({ label: input.parent })
  } else {
    await page.getByRole('combobox', { name: 'Area lokasi', exact: true }).selectOption({ label: input.area })
  }
  if (input.custodian) {
    const control = page.getByRole('combobox', { name: 'Penanggung jawab', exact: true })
    await expect(control.getByRole('option', { name: input.custodian, exact: true })).toBeAttached()
    await control.selectOption({ label: input.custodian })
  }
  await page.getByRole('button', { name: 'Tinjau perubahan', exact: true }).click()
  const response = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/locations' && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan lokasi', exact: true }).click()
  const saved = await response
  expect(saved.status()).toBe(201)
  const body = await saved.json()
  expect(body).toMatchObject({ code: input.code, name: input.name, revision: 0, state: 'ACTIVE' })
  await expect(page.getByRole('gridcell', { name: `${input.name} ${input.code}`, exact: true })).toBeVisible()
  return { id: body.id as string, code: input.code, name: input.name, label: `${input.name} · ${input.code}`, areaId: body.areaId as string }
}

export async function addSku(page: Page, input: { code: string; name: string; tracking: 'LOT' | 'SERIAL' | 'BULK'; unit?: 'MM' | 'EA'; minimum?: string; inspectionRequired?: boolean }) {
  await page.goto('/warehouse/catalog?tab=skus')
  await page.getByRole('button', { name: 'Tambah barang', exact: true }).click()
  await page.getByRole('textbox', { name: 'Kode barang', exact: true }).fill(input.code)
  await page.getByRole('textbox', { name: 'Nama barang', exact: true }).fill(input.name)
  await page.getByRole('combobox', { name: 'Pelacakan', exact: true }).selectOption(input.tracking)
  if (input.tracking !== 'SERIAL') await page.getByRole('combobox', { name: 'Satuan', exact: true }).selectOption(input.unit ?? 'EA')
  if (input.minimum) await page.getByRole('textbox', { name: /^Stok minimum/ }).fill(input.minimum)
  if (input.inspectionRequired === false) await page.getByRole('checkbox', { name: 'Wajib diperiksa sebelum tersedia', exact: true }).uncheck()
  await page.getByRole('button', { name: 'Tinjau perubahan', exact: true }).click()
  const response = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/skus' && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan barang', exact: true }).click()
  const saved = await response
  expect(saved.status()).toBe(201)
  const body = await saved.json()
  expect(body).toMatchObject({ code: input.code, name: input.name, tracking: input.tracking, revision: 0, state: 'ACTIVE' })
  await expect(page.getByRole('gridcell', { name: `${input.name} ${input.code}`, exact: true })).toBeVisible()
  return { id: body.id as string, ...input, minimumQuantityBase: body.minimumQuantityBase as string }
}

export async function addSupplier(page: Page, code: string, name: string) {
  await page.goto('/warehouse/catalog?tab=suppliers')
  await page.getByRole('button', { name: 'Tambah pemasok', exact: true }).click()
  await page.getByRole('textbox', { name: 'Kode pemasok', exact: true }).fill(code)
  await page.getByRole('textbox', { name: 'Nama pemasok', exact: true }).fill(name)
  await page.getByRole('button', { name: 'Tinjau perubahan', exact: true }).click()
  const response = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/suppliers' && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan pemasok', exact: true }).click()
  const saved = await response
  expect(saved.status()).toBe(201)
  const body = await saved.json()
  await expect(page.getByRole('gridcell', { name: `${name} ${code}`, exact: true })).toBeVisible()
  return { id: body.id as string, code, name }
}

export async function masterAction(page: Page, name: string, code: string, action: string) {
  const row = page.getByRole('row').filter({ has: page.getByRole('gridcell', { name: `${name} ${code}`, exact: true }) })
  await row.getByRole('button', { name: 'Aksi baris' }).click()
  await page.getByRole('menuitem', { name: action, exact: true }).click()
}
