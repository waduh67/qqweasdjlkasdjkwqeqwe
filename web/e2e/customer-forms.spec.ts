import { expect, test, type Page, type Locator } from '@playwright/test'
import { assetHistoryFixture, assetWorkspaceFixture, assetIds } from '../src/test/customerAssetFixture'

async function setup(page: Page) {
  const customer = { id: assetIds.customer, code: 'C-001', name: 'Budi Santoso', phone: '081234567890', email: 'budi@example.test', address: 'Jl. Cihanjuang No. 12, Cimahi', location: { longitude: 107.55, latitude: -6.87 }, areaId: null, idCardNumber: '3204000000000001', status: 'ACTIVE', subscription: { id: 'sub-1', customerId: assetIds.customer, planId: 'plan-1', packageName: 'Rumah 50 Mbps', bandwidthMbps: 50, monthlyFee: '150000', status: 'ACTIVE', activatedAt: '2026-10-01T00:00:00Z', terminatedAt: null }, onus: [], awaitingInstallation: false }
  const profile = { id: 'qa', email: 'admin@example.test', name: 'Rina', tenantId: 'qa', tenantSlug: 'qa', platformAdmin: false, permissions: ['customer.customer.view', 'customer.customer.create', 'customer.customer.update', 'customer.subscription.update', 'catalog.plan.view', 'customer.onu.view', 'customer.onu.assign', 'workorder.order.view', 'workorder.order.field', 'iam.user.view', 'iam.user.resetpassword', 'iam.area.view'], areaIds: [], roleIds: [], twoFactorEnabled: false }
  const staff = { id: 'staff', name: 'Andi', email: 'andi@example.test', status: 'ACTIVE', roleIds: [], areaIds: [], platformAdmin: false, tenantOwner: false, twoFactorEnabled: false }
  const writes: string[] = [], errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let body: unknown = { content: [], items: [], totalElements: 0, totalPages: 0, page: 0, size: 25 }
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', refreshToken: 'test-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/v2/warehouse/workflow') body = { snapshot: { tenantId: assetIds.customer, epoch: 1, state: 'ENFORCED', workflow: 'REFERENCE' }, owner: true }
    else if (path === '/api/customers') body = { content: [customer], totalElements: 1, totalPages: 1, page: 0, size: 50 }
    else if (path === `/api/customers/${customer.id}`) body = customer
    else if (path.startsWith('/api/gis/trace/')) body = null
    else if (path.startsWith('/api/subscriber-360/')) body = { customer, subscription: customer.subscription, placement: null, session: null, cpeDevices: [], openWorkOrder: null, billing: null, access: { billing: false, subscription: true, placement: true, session: false, cpe: false, workOrder: false } }
    else if (path === '/api/catalog/plans') body = [{ id: 'plan-1', name: 'Rumah 50 Mbps', downMbps: 50, upMbps: 25, price: 150000, active: true, serviceTypes: ['PPPOE'], fupEnabled: false }]
    else if (path === '/api/areas' || path === '/api/roles') body = []
    else if (path.includes('workbench/history')) body = { items: [assetHistoryFixture()], page: 0, size: 10, totalElements: 1 }
    else if (path.endsWith('/workbench')) body = assetWorkspaceFixture()
    else if (path === '/api/users') body = { content: [staff, { ...staff, id: 'owner', name: 'Pemilik tenant', email: 'owner@example.test', tenantOwner: true }], totalElements: 2 }
    if (request.method() !== 'GET' && path !== '/api/auth/refresh') {
      writes.push(path)
      if (path === '/api/me/password') { await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ detail: 'Password saat ini belum sesuai.' }) }); return }
      if (path.endsWith('/reset-password')) { await route.fulfill({ status: 204 }); return }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { writes, errors }
}
async function section(panel: Locator, label: string) {
  const picker = panel.getByRole('combobox', { name: 'Bagian pelanggan' })
  if (await picker.isVisible()) await picker.selectOption({ label })
  else await panel.getByRole('tab', { name: label, exact: true }).click()
}
for (const width of [375, 1440]) {
  test(`customer sections and nested details at ${width}px`, async ({ page }, info) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/customers?search=Budi')
    await page.getByRole('button', { name: 'Budi Santoso', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Budi Santoso', exact: true })
    await expect(panel.getByRole('heading', { name: 'Ringkasan', exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('customer-overview.png'), fullPage: true, animations: 'disabled' })
    await section(panel, 'Langganan'); await panel.getByRole('button', { name: 'Ganti paket', exact: true }).click()
    const child = page.getByRole('dialog', { name: 'Ganti paket', exact: true })
    await expect(child).toHaveAttribute('data-resource-layer', '1')
    await child.getByRole('spinbutton', { name: 'Harga negosiasi' }).fill('140000')
    await child.getByRole('button', { name: 'Tutup', exact: true }).click(); await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await section(panel, 'Perangkat'); await expect(panel.getByRole('button', { name: 'ONU-A1', exact: true })).toBeVisible()
    expect(await panel.locator('.fui-DataGridCell .card,.fui-DataGridCell .badge,.fui-DataGridCell code').count()).toBe(0)
    await panel.getByRole('button', { name: 'Aksi baris' }).click(); await expect(page.getByRole('menuitem', { name: 'Ganti perangkat', exact: true })).toBeVisible(); await page.keyboard.press('Escape')
    await page.screenshot({ path: info.outputPath('customer-devices.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'ONU-A1', exact: true }).click()
    const asset = page.getByRole('dialog', { name: 'ONU-A1', exact: true })
    await expect(asset.getByText('RCV-01', { exact: true })).toBeVisible(); await asset.getByRole('button', { name: 'Tutup', exact: true }).click()
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page).toHaveURL('/customers?search=Budi'); expect(writes).toEqual([]); expect(errors).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
  test(`password change and tenant reset at ${width}px`, async ({ page }, info) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/account/security')
    await page.getByRole('button', { name: 'Ganti password', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Ganti password', exact: true })
    await panel.getByLabel('Password saat ini').fill('Wrong-current-password'); await panel.getByLabel('Password baru').fill('New-test-password'); await panel.getByLabel('Konfirmasi password').fill('New-test-password')
    await panel.getByRole('button', { name: 'Simpan', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('Password saat ini belum sesuai.'); await expect(panel.getByLabel('Password baru')).toHaveAttribute('type', 'password')
    await page.screenshot({ path: info.outputPath('password-change.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click(); await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await page.goto('/users'); await page.getByRole('row').filter({ hasText: 'andi@example.test' }).getByRole('button', { name: 'Aksi baris' }).click(); await page.getByRole('menuitem', { name: 'Reset password', exact: true }).click()
    const reset = page.getByRole('dialog', { name: /Reset password/ })
    await reset.getByLabel('Password baru').fill('Reset-test-password'); await reset.getByLabel('Konfirmasi password').fill('Reset-test-password'); await reset.getByRole('button', { name: 'Simpan', exact: true }).click()
    await expect(reset).toHaveCount(0); expect(writes).toEqual(['/api/me/password', '/api/users/staff/reset-password']); expect(errors).toEqual([])
  })
}
