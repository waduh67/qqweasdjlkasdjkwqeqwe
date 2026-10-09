import { expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { identity, login } from '../warehouse/helpers'

export async function signupLegacy(page: Page) {
  const admin = identity('Admin')
  await page.goto('/signup')
  await page.getByLabel('Nama ISP').fill(`Gudang lama ${randomUUID().slice(0, 8)}`)
  await page.getByLabel('Nama admin').fill(admin.name)
  await page.getByLabel('Email admin').fill(admin.email)
  await page.getByLabel('Password (min. 8 karakter)').fill(admin.password)
  const response = page.waitForResponse(response => new URL(response.url()).pathname === '/api/signup' && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Daftar', exact: true }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('heading', { name: 'Pendaftaran berhasil' })).toBeVisible()
  await page.getByRole('button', { name: 'Masuk sekarang', exact: true }).click()
  await login(page, admin)
  return admin
}
