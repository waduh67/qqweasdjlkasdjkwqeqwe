import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { TokenResponse, User } from '../../src/api/types'
import type { PlatformTenant } from '../../src/api/tenant'
import { identity, login } from './helpers'

async function capture(page: Page, info: TestInfo, name: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' })
}

test('platform changes owner and password with fresh drafts and immediate session revocation', async ({ page }, info) => {
  test.setTimeout(240_000)
  const root = { email: 'root@ftth.local', password: 'rootadmin123' }
  const rootResponse = await page.request.post('/api/auth/login', { data: root })
  expect(rootResponse.ok()).toBeTruthy()
  const rootSession: TokenResponse = await rootResponse.json()
  const headers = { Authorization: 'Bearer ' + rootSession.accessToken }
  const initial = identity('Owner')
  const tenantName = 'Owner QA ' + randomUUID().slice(0, 8)
  const onboard = await page.request.post('/api/platform/tenants', { headers, data: {
    slug: 'owner-qa-' + randomUUID().slice(0, 8), name: tenantName,
    adminName: initial.name, adminEmail: initial.email, adminPassword: initial.password,
  } })
  expect(onboard.status()).toBe(201)
  const tenant: PlatformTenant = await onboard.json()
  expect((await page.request.get('/api/platform/tenants/' + tenant.id, { headers })).ok()).toBeTruthy()
  const initialResponse = await page.request.post('/api/auth/login', { data: initial })
  expect(initialResponse.ok()).toBeTruthy()
  const initialSession: TokenResponse = await initialResponse.json()
  const tenantHeaders = { Authorization: 'Bearer ' + initialSession.accessToken }
  const selected = identity('Pengganti')
  let selectedId = ''
  for (let index = 0; index < 21; index++) {
    const account = index === 0 ? selected : identity('Operator')
    const response = await page.request.post('/api/users', { headers: tenantHeaders, data: account })
    expect(response.status()).toBe(201)
    const created: User = await response.json()
    if (index === 0) selectedId = created.id
  }
  const selectedResponse = await page.request.post('/api/auth/login', { data: selected })
  expect(selectedResponse.ok()).toBeTruthy()
  const selectedSession: TokenResponse = await selectedResponse.json()
  await login(page, root)
  await page.goto('/platform/tenants')
  await page.getByRole('searchbox').fill(tenantName)
  const openOwner = async () => {
    await page.getByRole('button', { name: 'Kelola owner ' + tenantName, exact: true }).click()
    await expect(page.getByText('Owner saat ini', { exact: true })).toBeVisible()
    await expect(page.getByLabel('Calon owner', { exact: true })).toBeEnabled()
  }
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => localStorage.setItem('ftth.theme', value), theme)
    await page.reload()
    await page.getByRole('searchbox').fill(tenantName)
    await page.getByRole('button', { name: 'Kelola owner ' + tenantName, exact: true }).scrollIntoViewIfNeeded()
    await capture(page, info, theme + '-owner-list')
    await openOwner()
    await capture(page, info, theme + '-owner-panel')
    await page.getByRole('button', { name: 'Berikutnya', exact: true }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Halaman 2 dari 2' })).toBeVisible()
    await page.getByLabel('Cari calon owner').fill(selected.email)
    await expect(page.getByLabel('Calon owner', { exact: true }).locator('option', { hasText: selected.email })).toHaveCount(1)
    await page.getByLabel('Calon owner', { exact: true }).selectOption(selectedId)
    await page.getByLabel('Cari calon owner').fill('tidak-ada-' + randomUUID())
    await expect(page.getByText('Tidak ada pengguna yang cocok di tenant ini.')).toBeVisible()
    await expect(page.getByLabel('Calon owner', { exact: true })).toHaveValue(selectedId)
    await capture(page, info, theme + '-owner-empty-search')
    await page.getByLabel(/^Password baru/).fill('short')
    await page.getByLabel('Konfirmasi password baru').fill('short')
    await page.getByRole('button', { name: 'Ganti password owner', exact: true }).click()
    await expect(page.getByText('Password minimal 8 karakter', { exact: true })).toBeVisible()
    await expect(page.getByLabel(/^Password baru/)).toHaveValue('short')
    await page.locator('.azure-blade-body').evaluate(element => { element.scrollTop = element.scrollHeight })
    await capture(page, info, theme + '-owner-validation-scroll-end')
    await page.getByRole('button', { name: 'Batal', exact: true }).click()
    await openOwner()
    await expect(page.getByLabel('Calon owner', { exact: true })).toHaveValue(initialSession.user.id)
    await expect(page.getByLabel(/^Password baru/)).toHaveValue('')
    await page.keyboard.press('Escape')
    await expect(page.getByText('Owner saat ini', { exact: true })).toHaveCount(0)
    await openOwner()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page.getByText('Owner saat ini', { exact: true })).toHaveCount(0)
  }
  await openOwner()
  await page.getByLabel('Cari calon owner').fill(selected.email)
  await expect(page.getByLabel('Calon owner', { exact: true }).locator('option', { hasText: selected.email })).toHaveCount(1)
  await page.getByLabel('Calon owner', { exact: true }).selectOption(selectedId)
  const bound = page.waitForResponse(response => response.url().endsWith('/owner') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: 'Simpan owner', exact: true }).click()
  expect((await bound).status()).toBe(200)
  await expect(page.getByText('Owner saat ini', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: 'diubah menjadi' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Kelola owner ' + tenantName })).toHaveText(selected.name)
  expect((await page.request.get('/api/me', { headers: tenantHeaders })).status()).toBe(401)
  expect((await page.request.get('/api/me', { headers: { Authorization: 'Bearer ' + selectedSession.accessToken } })).status()).toBe(401)
  const freshResponse = await page.request.post('/api/auth/login', { data: selected })
  expect(freshResponse.ok()).toBeTruthy()
  const beforeReset: TokenResponse = await freshResponse.json()
  await openOwner()
  const password = identity('Password').password
  await page.getByLabel(/^Password baru/).fill(password)
  await page.getByLabel('Konfirmasi password baru').fill('different-password')
  await page.getByRole('button', { name: 'Ganti password owner', exact: true }).click()
  await expect(page.getByText('Konfirmasi password belum cocok')).toBeVisible()
  await page.getByLabel('Konfirmasi password baru').fill(password)
  const reset = page.waitForResponse(response => response.url().endsWith('/owner/password') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Ganti password owner', exact: true }).click()
  expect((await reset).status()).toBe(204)
  await expect(page.getByText('Owner saat ini', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: 'Password owner' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Kelola owner ' + tenantName })).toHaveText(selected.name)
  await page.getByRole('button', { name: 'Kelola owner ' + tenantName }).scrollIntoViewIfNeeded()
  await capture(page, info, 'owner-reset-success')
  expect((await page.request.get('/api/me', { headers: { Authorization: 'Bearer ' + beforeReset.accessToken } })).status()).toBe(401)
  expect((await page.request.post('/api/auth/refresh', { data: { refreshToken: beforeReset.refreshToken } })).status()).toBe(401)
  expect((await page.request.post('/api/auth/login', { data: selected })).status()).toBe(401)
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, { ...selected, password })
  await expect(page.getByRole('button', { name: 'Keluar', exact: true })).toBeVisible()
})
