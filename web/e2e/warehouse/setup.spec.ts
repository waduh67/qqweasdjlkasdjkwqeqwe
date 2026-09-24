import { expect, test } from '@playwright/test'
import { createRole, createUser, login, openWarehouseMenu, signup } from './helpers'

test('real signup, independent approver navigation and explicit unavailable route', async ({ page, request }, testInfo) => {
  const health = await request.get('/actuator/health')
  expect(health.ok()).toBeTruthy()
  expect(health.headers()['content-type']).toContain('json')
  expect(await health.json()).toMatchObject({ status: 'UP', components: { warehouse: { details: {
    database: 'warehouse_e2e', user: 'warehouse_app', marker: process.env.WAREHOUSE_ENVIRONMENT_MARKER,
  } } } })

  await signup(page)
  const roleName = 'Pemeriksa gudang'
  await createRole(page, roleName, ['inventory.approval.view', 'inventory.approval.decide'])
  const approver = await createUser(page, roleName)
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await login(page, approver)

  const warehouseReads: string[] = []
  page.on('request', req => { if (new URL(req.url()).pathname.startsWith('/api/v1/warehouse/')) warehouseReads.push(new URL(req.url()).pathname) })
  await openWarehouseMenu(page)
  await expect(page.getByRole('link', { name: 'Stok & Perangkat', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Ringkasan Gudang', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Gudang & Logistik', exact: true })).toBeVisible()
  await expect(page.getByText('0 persetujuan menunggu', { exact: true })).toBeVisible()
  const approvalResponse = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/approvals' && new URL(res.url()).search === '?page=0')
  await page.getByRole('link', { name: 'Buka persetujuan gudang', exact: true }).click()
  const response = await approvalResponse
  expect(response.ok()).toBeTruthy()
  expect(await response.json()).toMatchObject({ items: [], page: 0, totalElements: 0 })
  await expect(page.getByText('Tidak ada persetujuan dalam cakupan Anda', { exact: true })).toBeVisible()
  expect(warehouseReads.length).toBeGreaterThan(0)
  expect(warehouseReads.every(path => path === '/api/v1/warehouse/approvals')).toBeTruthy()

  // Obtain this real request's in-memory authorization only for a negative server assertion.
  // No session injection, browser mocks or database seed is used in this journey.
  const authorization = await response.request().headerValue('authorization')
  expect(authorization).toBeTruthy()
  const denied = await request.get('/api/v1/warehouse/stock', { headers: { Authorization: authorization! } })
  expect(denied.status()).toBe(403)
  await page.goto('/warehouse/stock')
  await expect(page.getByText('Akses gudang dibatasi', { exact: true })).toBeVisible()
  await page.goto('/warehouse/missing-page')
  await expect(page.getByText('Halaman gudang tidak tersedia', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Kembali ke ringkasan gudang' }).click()
  await expect(page.getByText('0 persetujuan menunggu', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('approver-navigation.png'), fullPage: true })
})
