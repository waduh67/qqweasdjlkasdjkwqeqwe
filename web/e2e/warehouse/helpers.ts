import { expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

export function identity(prefix: string) {
  const suffix = randomUUID().slice(0, 8)
  return { name: `${prefix} ${suffix}`, email: `${prefix.toLowerCase()}-${suffix}@example.test`, password: `Warehouse-${randomUUID()}!` }
}

export async function signup(page: Page) {
  const admin = identity('Admin')
  await page.goto('/signup')
  await page.getByLabel('Nama ISP').fill(`Gudang ${randomUUID().slice(0, 8)}`)
  await page.getByLabel('Nama admin').fill(admin.name)
  await page.getByLabel('Email admin').fill(admin.email)
  await page.getByLabel('Password (min. 8 karakter)').fill(admin.password)
  const response = page.waitForResponse(response => response.url().endsWith('/api/signup') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Daftar', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('heading', { name: 'Pendaftaran berhasil' })).toBeVisible()
  await page.getByRole('button', { name: 'Masuk sekarang', exact: true }).click()
  await login(page, admin)
  return admin
}

export async function login(page: Page, user: { email: string; password: string }) {
  if (!page.url().endsWith('/login')) await page.goto('/login')
  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Password').fill(user.password)
  await page.getByRole('button', { name: 'Masuk', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: 'Keluar', exact: true })).toBeVisible()
}

export async function createRole(page: Page, name: string, permissions: string[]) {
  await page.goto('/roles')
  await page.getByRole('button', { name: 'Role baru', exact: true }).click()
  await page.getByLabel('Nama').fill(name)
  for (const permission of permissions) await page.getByRole('checkbox', { name: permission, exact: true }).check()
  const response = page.waitForResponse(response => response.url().endsWith('/api/roles') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('gridcell', { name, exact: true })).toBeVisible()
}

export async function createUser(page: Page, role: string, options: { areas?: string[]; prefix?: string } = {}) {
  const user = identity(options.prefix ?? 'Pemeriksa')
  await page.goto('/users')
  await page.getByRole('button', { name: 'Pengguna baru', exact: true }).click()
  await page.getByLabel('Nama').fill(user.name)
  await page.getByLabel('Email').fill(user.email)
  await page.getByLabel('Password (min. 8 karakter)').fill(user.password)
  await page.getByRole('checkbox', { name: role, exact: true }).check()
  for (const area of options.areas ?? []) await page.getByRole('checkbox', { name: area, exact: true }).check()
  const response = page.waitForResponse(response => response.url().endsWith('/api/users') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('gridcell', { name: user.email, exact: true })).toBeVisible()
  return user
}

export async function openWarehouseMenu(page: Page) {
  const section = page.getByRole('button', { name: 'Gudang & Logistik', exact: true })
  const toggle = page.getByRole('button', { name: /sidebar/ })
  const mobile = (page.viewportSize()?.width ?? 1280) <= 820
  // A drawer translated offscreen still satisfies isVisible(); use its actual open state.
  if (mobile && (await toggle.getAttribute('aria-expanded')) === 'false') await toggle.tap()
  else if (!mobile && !(await section.isVisible())) await toggle.click()
  if ((await section.getAttribute('aria-expanded')) === 'false') {
    if (mobile) await section.tap()
    else await section.click()
  }
}
