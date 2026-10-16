import { expect, test, type Page, type Locator } from '@playwright/test'
import { assetHistoryFixture, assetWorkspaceFixture, assetIds } from '../src/test/customerAssetFixture'

async function setup(page: Page, platformAdmin = false, unlocated = false) {
  const customer = { id: assetIds.customer, code: 'C-001', name: 'Budi Santoso', phone: '081234567890', email: 'budi@example.test', address: 'Jl. Cihanjuang No. 12, Cimahi', location: unlocated ? null : { longitude: 107.55, latitude: -6.87 }, areaId: null, idCardNumber: '3204000000000001', status: unlocated ? 'PROSPECT' : 'ACTIVE', subscription: { id: 'sub-1', customerId: assetIds.customer, planId: 'plan-1', packageName: 'Rumah 50 Mbps', bandwidthMbps: 50, monthlyFee: '150000', status: unlocated ? 'PENDING' : 'ACTIVE', activatedAt: unlocated ? null : '2026-10-01T00:00:00Z', terminatedAt: null }, onus: [], awaitingInstallation: unlocated }
  const profile = { id: 'qa', email: 'admin@example.test', name: 'Rina', tenantId: 'qa', tenantSlug: 'qa', platformAdmin, permissions: ['customer.customer.view', 'customer.customer.create', 'customer.customer.update', 'customer.subscription.update', 'catalog.plan.view', 'customer.onu.view', 'customer.onu.assign', 'workorder.order.view', 'workorder.order.field', 'iam.user.view', 'iam.user.resetpassword', 'iam.area.view', 'bng.access.manage', 'workorder.order.create', 'billing.gateway.view', 'billing.gateway.manage'], areaIds: [], roleIds: [], twoFactorEnabled: false }
  const staff = { id: 'staff', name: 'Andi', email: 'andi@example.test', status: 'ACTIVE', roleIds: [], areaIds: [], platformAdmin: false, tenantOwner: false, twoFactorEnabled: false }
  const writes: string[] = [], errors: string[] = [], reads: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (request.method() === 'GET') reads.push(path)
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
    else if (path === '/api/areas' || path === '/api/roles' || path === '/api/bng/nas') body = []
    else if (path.includes('workbench/history')) body = { items: [assetHistoryFixture()], page: 0, size: 10, totalElements: 1 }
    else if (path.endsWith('/workbench')) body = assetWorkspaceFixture()
    else if (path.startsWith('/api/platform/billing/')) body = { defaultMonthlyFee: 150000 }
    else if (path === '/api/platform/tenants') body = { content: [{ id: 'tenant-qa', slug: 'qa', name: 'Tenant QA', status: 'ACTIVE', owner: { id: 'owner-qa', name: 'Owner QA', email: 'owner@example.test', status: 'ACTIVE' }, subscription: null }], totalElements: 1 }
    else if (path === '/api/users') body = { content: [staff, { ...staff, id: 'owner', name: 'Pemilik tenant', email: 'owner@example.test', tenantOwner: true }], totalElements: 2 }
    if (request.method() !== 'GET' && path !== '/api/auth/refresh') {
      writes.push(path)
      if (path === '/api/me/password') { await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ detail: 'Password saat ini belum sesuai.' }) }); return }
      if (path.endsWith('/reset-password')) { await route.fulfill({ status: 204 }); return }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { writes, errors, reads }
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

for (const width of [375, 1440]) {
  for (const located of [true, false]) {
  test(`PSB keeps a rejected draft through five steps (${located ? 'located' : 'unlocated'}) at ${width}px`, async ({ page }, info) => {
    const { errors, reads } = await setup(page), requests: Record<string, unknown>[] = []
    await page.route('**/api/onboarding/psb', async route => {
      requests.push(route.request().postDataJSON())
      await route.fulfill({ status: requests.length === 1 ? 422 : 200, contentType: 'application/json', body: JSON.stringify(requests.length === 1
        ? { detail: 'Data PSB belum dapat disimpan.' }
        : { customerId: assetIds.customer, subscriptionId: 'sub-1', accessId: 'access-1', username: 'cust-001', workOrderId: 'wo-1', workOrderCode: 'WO-001' }) })
    })
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/customers?q=Budi&status=ACTIVE')
    await expect(page.getByRole('button', { name: 'Budi Santoso', exact: true })).toBeVisible()
    expect(reads).not.toContain('/api/catalog/plans')
    await page.screenshot({ path: info.outputPath('customers-psb-commandbar.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('button', { name: 'PSB Ekspres', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Buat PSB', exact: true })
    await panel.getByRole('button', { name: 'Berikutnya: Lokasi' }).click()
    await expect(panel.getByRole('tab', { name: 'Pelanggan', exact: true })).toHaveAttribute('aria-selected', 'true')
    await panel.getByRole('textbox', { name: 'Nama', exact: true }).fill('Budi Baru')
    await panel.getByRole('button', { name: 'Berikutnya: Lokasi' }).click()
    await panel.getByRole('textbox', { name: 'Alamat', exact: true }).fill('Jl. Cihanjuang 12')
    if (located) {
      await panel.getByLabel('Longitude', { exact: true }).fill('107.55'); await panel.getByLabel('Latitude', { exact: true }).fill('-6.87')
    } else {
      await expect(panel.getByText('Koordinat (opsional)', { exact: true })).toBeVisible()
      await panel.getByLabel('Longitude', { exact: true }).fill('107.55')
      await panel.getByRole('button', { name: 'Berikutnya: Paket' }).click()
      await expect(panel.getByRole('tab', { name: 'Lokasi', exact: true })).toHaveAttribute('aria-selected', 'true')
      await panel.getByRole('button', { name: 'Kosongkan koordinat' }).click()
    }
    await panel.getByRole('button', { name: 'Berikutnya: Paket' }).click()
    await panel.getByRole('button', { name: 'Berikutnya: Akun jaringan' }).click()
    await panel.getByLabel(/^Password/).fill('Fixture-network-secret')
    await expect(panel.getByLabel(/^Password/)).toHaveAttribute('type', 'password')
    await page.screenshot({ path: info.outputPath('psb-network.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Berikutnya: Pemasangan' }).click()
    await panel.getByRole('textbox', { name: 'Catatan pemasangan' }).fill('Hubungi sebelum datang')
    await page.screenshot({ path: info.outputPath('psb-installation.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await expect(panel.getByText('Budi Baru', { exact: true })).toBeVisible()
    await expect(panel.getByText('Fixture-network-secret', { exact: true })).toHaveCount(0); expect(requests).toEqual([])
    await panel.getByRole('button', { name: 'Buat PSB', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('PSB belum berhasil dibuat')
    await panel.getByRole('tab', { name: 'Pelanggan', exact: true }).click()
    await expect(panel.getByRole('textbox', { name: 'Nama', exact: true })).toHaveValue('Budi Baru')
    await panel.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
    await panel.getByRole('button', { name: 'Buat PSB', exact: true }).click()
    await expect(panel).toHaveCount(0); await expect(page.getByText('PSB dibuat', { exact: true })).toBeVisible()
    await expect(page).toHaveURL('/customers?q=Budi&status=ACTIVE')
    await expect.poll(() => reads.filter(path => path === '/api/customers').length).toBe(2)
    await expect(page.getByText('Fixture-network-secret', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Tugaskan teknisi', exact: true })).toHaveAttribute('href', '/work-orders/wo-1')
    expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0])
    expect(requests[1]).toMatchObject({ name: 'Budi Baru', planId: 'plan-1', serviceType: 'PPPOE', secret: 'Fixture-network-secret', location: located ? { latitude: -6.87, longitude: 107.55 } : null, assignees: [] })
    expect(errors).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })

  }

  test(`payment profile saves separately from registration at ${width}px`, async ({ page }, info) => {
    const { errors } = await setup(page), writes: { path: string; input: Record<string, unknown> }[] = []
    let account = { provisioned: false, masterActive: true, type: 'NON_KYC', status: 'NOT_PROVISIONED', kycStatus: 'NOT_REQUIRED', legalName: '', merchantEmail: '', merchantPhone: '', picName: '', picEmail: '', picPhone: '', address: '', payoutChannelCode: '', payoutAccountNumber: '', payoutAccountName: '', payoutReady: false, profileComplete: false, payoutFeeType: 'FIXED', payoutFeeMinor: 0 }
    await page.route('**/api/billing/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() !== 'GET') {
        const input = route.request().postData() ? route.request().postDataJSON() : {}; writes.push({ path, input })
        if (writes.length === 1) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'Coba lagi.' }) }); return }
        if (path.endsWith('/profile')) account = { ...account, ...input, payoutChannelCode: String(input.channelCode ?? ''), payoutAccountNumber: String(input.accountNumber ?? ''), payoutAccountName: String(input.accountName ?? '') }
        if (path.endsWith('/provision')) account = { ...account, provisioned: true, status: 'CREATED' }
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.endsWith('/gateway-settings') ? { provider: 'PIVOT', enabled: true, manualTransferEnabled: false, manualQrisEnabled: false } : path.endsWith('/balance') ? { availableMinor: 0, currency: 'IDR', subAccount: true } : path.endsWith('/payouts') ? [] : account) })
    })
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/payment-gateway')
    await page.getByRole('button', { name: 'Lengkapi profil', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Profil akun pembayaran', exact: true })
    await panel.getByLabel('Nama legal bisnis').fill('Cimahi Net')
    await panel.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Daftarkan sub-account', exact: true })).toBeDisabled(); expect(writes).toEqual([])
    await panel.getByRole('button', { name: 'Simpan profil', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('Belum berhasil disimpan')
    await panel.getByRole('tab', { name: 'Bisnis', exact: true }).click()
    await expect(panel.getByLabel('Nama legal bisnis')).toHaveValue('Cimahi Net')
    await panel.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
    await panel.getByRole('button', { name: 'Simpan profil', exact: true }).click(); await expect(panel).toHaveCount(0)
    expect(writes.map(write => write.path)).toEqual(['/api/billing/pivot-account/profile', '/api/billing/pivot-account/profile'])
    await page.getByRole('button', { name: 'Lengkapi profil', exact: true }).click()
    await panel.getByLabel('Email bisnis').fill('billing@example.test'); await panel.getByLabel('Telepon bisnis').fill('081234567890'); await panel.getByLabel('Alamat bisnis').fill('Cimahi')
    await panel.getByRole('button', { name: 'Berikutnya: Penanggung jawab' }).click()
    await panel.getByLabel('Nama PIC').fill('Rina'); await panel.getByLabel('Email PIC').fill('rina@example.test'); await panel.getByLabel('Telepon PIC').fill('081234567890')
    await panel.getByRole('button', { name: 'Berikutnya: Rekening' }).click()
    await panel.getByRole('combobox').fill('BCA'); await page.getByRole('option').filter({ hasText: /BCA/ }).first().click()
    await panel.getByLabel('Nomor rekening').fill('1234567890'); await panel.getByLabel('Nama pemilik rekening').fill('Rina')
    await page.screenshot({ path: info.outputPath('payment-bank.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await panel.getByRole('button', { name: 'Daftarkan sub-account', exact: true }).click(); await expect(panel).toHaveCount(0)
    expect(writes.slice(2).map(write => write.path)).toEqual(['/api/billing/pivot-account/profile', '/api/billing/pivot-account/provision'])
    expect(writes[2].input).toMatchObject({ legalName: 'Cimahi Net', picName: 'Rina', accountNumber: '1234567890', accountName: 'Rina' })
    expect(errors).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}


for (const width of [375, 1440]) {
  test(`PSB opens from customers and legacy bookmarks without remounting the list at ${width}px`, async ({ page }) => {
    const { errors, reads, writes } = await setup(page)
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/customers?q=Budi&status=ACTIVE')
    await expect(page.getByRole('button', { name: 'Budi Santoso', exact: true })).toBeVisible()
    await expect(page.locator('a[href="/express-psb"]')).toHaveCount(0)
    const customerList = await page.locator('.customers-page').elementHandle()
    const toolbar = page.getByRole('toolbar', { name: 'Aksi', exact: true })
    await expect(toolbar.getByRole('button', { name: 'Tambah pelanggan', exact: true })).toBeVisible()
    await expect(toolbar.getByRole('button', { name: 'PSB Ekspres', exact: true })).toBeVisible()
    expect(await toolbar.locator(':scope > button').allTextContents()).toEqual(['Tambah pelanggan', 'PSB Ekspres'])
    await toolbar.getByRole('button', { name: 'PSB Ekspres', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Buat PSB', exact: true })
    await expect(panel.getByRole('tab', { name: 'Pelanggan', exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Batal', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await expect(page).toHaveURL('/customers?q=Budi&status=ACTIVE')
    await expect(toolbar.getByRole('button', { name: 'PSB Ekspres', exact: true })).toBeFocused()
    expect(await customerList?.evaluate(node => node.isConnected)).toBe(true)
    expect(reads.filter(path => path === '/api/customers')).toHaveLength(1)
    await page.goto('/express-psb?q=Budi&status=ACTIVE')
    await expect(page).toHaveURL('/customers?q=Budi&status=ACTIVE&create=psb')
    await expect(panel.getByRole('tab', { name: 'Pelanggan', exact: true })).toBeVisible()
    await panel.getByRole('textbox', { name: 'Nama', exact: true }).fill('Draft yang dibatalkan')
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Buang perubahan?', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Lanjutkan pengisian', exact: true }).click()
    await expect(panel.getByRole('textbox', { name: 'Nama', exact: true })).toHaveValue('Draft yang dibatalkan')
    await panel.getByRole('button', { name: 'Batal', exact: true }).click()
    await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await expect(page).toHaveURL('/customers?q=Budi&status=ACTIVE')
    await expect(page.getByRole('button', { name: 'Budi Santoso', exact: true })).toBeVisible()
    expect(reads.filter(path => path === '/api/customers')).toHaveLength(2)
    expect(writes).toEqual([]); expect(errors).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

test('platform admin resets the selected tenant owner with an identity precondition', async ({ page }) => {
  const { errors } = await setup(page, true), writes: unknown[] = []
  await page.route('**/api/platform/tenants/tenant-qa/owner/password', async route => { writes.push(route.request().postDataJSON()); await route.fulfill({ status: 204 }) })
  await page.goto('/platform/tenants')
  await page.getByRole('row').filter({ hasText: 'Tenant QA' }).getByRole('button', { name: 'Aksi baris' }).click()
  await page.getByRole('menuitem', { name: 'Reset password owner', exact: true }).click()
  const panel = page.getByRole('dialog', { name: 'Reset password owner', exact: true })
  await expect(panel.getByText('Owner QA · owner@example.test', { exact: true })).toBeVisible()
  await panel.getByLabel('Password baru').fill('Reset-owner-fixture'); await panel.getByLabel('Konfirmasi password').fill('Reset-owner-fixture')
  await panel.getByRole('button', { name: 'Simpan', exact: true }).click(); await expect(panel).toHaveCount(0)
  expect(writes).toEqual([{ expectedOwnerUserId: 'owner-qa', newPassword: 'Reset-owner-fixture' }]); expect(errors).toEqual([])
})


for (const width of [375, 1440]) {
  test(`OLT creation separates identity and management credentials at ${width}px`, async ({ page }, info) => {
    const { errors } = await setup(page, true), writes: unknown[] = []
    await page.route('**/api/sites?*', route => route.fulfill({ json: { content: [{ id: 'site-1', code: 'POP-01', name: 'POP Cimahi', address: 'Cimahi', areaId: null, location: { latitude: -6.87, longitude: 107.55 }, oltCount: 0 }], totalElements: 1, totalPages: 1 } }))
    await page.route('**/api/olts', async route => { writes.push(route.request().postDataJSON()); await route.fulfill({ json: { id: 'olt-new' } }) })
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/inventory')
    await page.getByRole('tab', { name: 'OLT', exact: true }).click()
    await page.getByRole('button', { name: 'Tambah OLT', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Tambah OLT', exact: true })
    await panel.getByRole('textbox', { name: 'Kode', exact: true }).fill('OLT-01')
    await panel.getByRole('textbox', { name: 'Nama', exact: true }).fill('OLT Cimahi')
    await panel.getByRole('button', { name: 'Berikutnya: SNMP' }).click()
    await panel.getByLabel(/Community string/).fill('Fixture-olt-secret')
    await page.screenshot({ path: info.outputPath('olt-snmp.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Berikutnya: Web management' }).click()
    await panel.getByRole('button', { name: 'Berikutnya: Lokasi' }).click()
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await expect(panel.getByText('OLT Cimahi', { exact: true })).toBeVisible()
    await expect(panel.getByText('Fixture-olt-secret', { exact: true })).toHaveCount(0); expect(writes).toEqual([])
    await panel.getByRole('button', { name: 'Simpan', exact: true }).click(); await expect(panel).toHaveCount(0)
    expect(writes).toEqual([expect.objectContaining({ code: 'OLT-01', name: 'OLT Cimahi', siteId: 'site-1', snmpCommunity: 'Fixture-olt-secret', snmpPort: 161 })]); expect(errors).toEqual([])
  })
}


test('unlocated PSB has one clear status and opens both customer blades', async ({ page }) => {
  const { errors } = await setup(page, false, true)
  await page.goto('/customers')
  const row = page.getByRole('row').filter({ hasText: 'Budi Santoso' })
  await expect(row.getByText('Menunggu instalasi', { exact: true })).toBeVisible()
  await expect(row.getByText('Prospek', { exact: true })).toHaveCount(0)
  await row.getByRole('button', { name: 'Budi Santoso', exact: true }).click()
  const detail = page.getByRole('dialog', { name: 'Budi Santoso', exact: true })
  await expect(detail.getByRole('button', { name: 'Lihat di peta', exact: true })).toBeDisabled()
  await expect(detail.getByText('Belum ditentukan', { exact: true })).toBeVisible()
  await detail.getByRole('button', { name: 'Tutup', exact: true }).click()
  await row.getByRole('button', { name: 'Aksi sel', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Edit', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Nama', exact: true })).toHaveValue('Budi Santoso')
  expect(errors).toEqual([])
})
