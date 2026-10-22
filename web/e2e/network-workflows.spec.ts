import { expect, test, type Page } from '@playwright/test'

async function setup(page: Page, platformAdmin = true) {
  const profile = { id: 'qa', email: 'qa@example.test', name: 'QA', tenantId: 'qa', tenantSlug: 'qa', platformAdmin, permissions: platformAdmin ? [] : ['bng.nas.view', 'bng.nas.manage', 'iam.area.view'], areaIds: [], roleIds: [], twoFactorEnabled: true }
  const writes: { path: string; body: Record<string, unknown> }[] = [], errors: string[] = []
  let vpnCreated = false
  const vpnAccount = { id: 'vpn-1', label: 'MikroTik Lab Laptop', serverName: 'VPN Jakarta', host: 'vpn.example.test', port: 1194, protocol: 'TCP', cipher: 'AES-256-CBC', securityType: 'OpenVPN', username: 'mikrotik-lab-laptop', overlayIp: '10.8.0.2', remotePort: 20001, winboxAddress: 'vpn.example.test:20001', forwards: [], routes: [], status: 'ENABLED', online: false, lastHandshakeAt: null, supportsV6: true, password: 'demo-test-password',
    routerOsCommand: '/interface/ovpn-client/add name="ovpn-lab" connect-to=vpn.example.test port=1194 protocol=tcp user="mikrotik-lab-laptop" password="demo-test-password" mode=ip add-default-route=no use-peer-dns=no verify-server-certificate=no auth=sha1 cipher=aes256-cbc disabled=no',
    routerOsCommandV6: '/interface ovpn-client add name="ovpn-lab" connect-to=vpn.example.test port=1194 user="mikrotik-lab-laptop" password="demo-test-password" mode=ip add-default-route=no auth=sha1 cipher=aes256 disabled=no' }
  const nas = { id: 'bras-1', name: 'BRAS Tebet', vendor: 'MIKROTIK', address: '10.8.0.3', nasIdentifier: 'tebet', hasCoaSecret: true, collectorId: null, enabled: true, apiUsername: 'operator', hasApiSecret: true, apiPort: 443, apiUseTls: true, areaIds: ['tebet'], reachability: 'VPN' }
  const devices = [{ id: 'ont-1', serialNumber: 'HWTC00112233', customerId: null, customerName: 'Budi Santoso', manufacturer: 'Huawei', model: 'HG8245H', softwareVersion: 'V5', online: true, lastInformAt: '2026-10-11T09:00:00Z', ipAddress: '10.20.0.2', ssid: 'Budi Home', pppoeUsername: 'budi', pppoeOnline: true, rxPowerDbm: -23.4, txPowerDbm: 2.1, temperatureC: 42 },
    { id: 'ont-2', serialNumber: 'ZTEG44556677', customerId: null, customerName: 'Siti Aminah', manufacturer: 'ZTE', model: 'F670L', softwareVersion: 'V1', online: false, lastInformAt: '2026-10-10T09:00:00Z', ipAddress: '10.20.0.3', ssid: 'Siti Home', pppoeUsername: 'siti', pppoeOnline: false, rxPowerDbm: -28, txPowerDbm: 2.5, temperatureC: 39 }]
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    let body: unknown = { items: [], content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 }
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/areas') body = [{ id: 'tebet', code: 'TEBET', name: 'Tebet', parentId: null }, { id: 'pancoran', code: 'PANCORAN', name: 'Pancoran', parentId: null }]
    else if (path === '/api/vpn/accounts') body = vpnCreated ? [{ ...vpnAccount, password: null, routerOsCommand: null, routerOsCommandV6: null }] : []
    else if (path === '/api/vpn/accounts/generate') { vpnCreated = true; body = vpnAccount }
    else if (path === '/api/vpn/accounts/vpn-1/routeros' || path === '/api/vpn/accounts/vpn-1/ovpn') {
      await route.fulfill({ status: 200, contentType: 'text/plain', body: url.searchParams.get('variant') === 'V6' ? vpnAccount.routerOsCommandV6 : vpnAccount.routerOsCommand })
      return
    }
    else if (path === '/api/bng/nas') body = request.method() === 'GET' ? [nas] : { ...nas, ...request.postDataJSON() }
    else if (path === '/api/bng/radius-endpoint') body = { host: '203.0.113.4', authPort: 1812, acctPort: 1813, coaPort: 3799, configured: true, vpnHosts: [{ tunnelCidr: '10.8.0.0/24', host: '10.8.0.1' }], isolirAddressList: 'isolir' }
    else if (path === '/api/cpe/acs/server') body = { nbiBaseUrl: 'http://genieacs:7557', cwmpUrl: 'https://acs.example.test', acsUsername: 'ont', acsPassword: 'demo-secret', connectionRequestUsername: 'acs', connectionRequestPassword: 'demo-request-secret', periodicInformEnabled: true, periodicInformIntervalSeconds: 300, syncIntervalSeconds: 60, configured: true }
    else if (path === '/api/cpe/acs/health') body = { status: 'ONLINE', latencyMs: 12, checkedAt: '2026-10-11T09:00:00Z', message: 'ok' }
    else if (path === '/api/cpe/acs/stats') body = { totalDevices: 248, onlineDevices: 236, offlineDevices: 12, avgRxPowerDbm: -22.4, signalSampleCount: 230, lastSyncAt: '2026-10-11T09:00:00Z', lastSyncOk: true }
    else if (path === '/api/cpe/acs/devices') body = devices.filter(item => (!url.searchParams.get('status') || item.online === (url.searchParams.get('status') === 'ONLINE')) && (!url.searchParams.get('brand') || item.manufacturer === url.searchParams.get('brand')))
    else if (path === '/api/cpe/acs/logs') body = [{ id: 'log-1', deviceId: 'ont-1', serialNumber: 'HWTC00112233', customerName: 'Budi Santoso', action: 'REFRESH', status: 'SUCCESS', detail: 'Parameter perangkat diperbarui.', requestedByEmail: 'rina@example.test', requestedAt: '2026-10-11T09:00:00Z' }]
    if (request.method() !== 'GET' && path !== '/api/auth/refresh') writes.push({ path, body: request.postDataJSON() })
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { writes, errors }
}
async function compactControls(page: Page) {
  const group = page.locator('.resource-list-controls:visible').first()
  const bar = await group.getByRole('toolbar').boundingBox(), filters = await group.locator('.resource-filter-bar').boundingBox()
  expect(bar).not.toBeNull(); expect(filters).not.toBeNull()
  expect(filters!.y - bar!.y - bar!.height).toBeGreaterThanOrEqual(-1)
  expect(filters!.y - bar!.y - bar!.height).toBeLessThanOrEqual(2)
  expect(Math.abs(filters!.x - bar!.x)).toBeLessThanOrEqual(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}
async function headerControls(page: Page) {
  const header = page.locator('.page-header')
  const title = await header.getByRole('heading', { name: 'BRAS & RADIUS', exact: true }).boundingBox()
  const bar = await header.getByRole('toolbar').boundingBox()
  const filters = await page.locator('.resource-filter-bar:visible').first().boundingBox()
  expect(title).not.toBeNull(); expect(bar).not.toBeNull(); expect(filters).not.toBeNull()
  await expect(header.getByRole('button', { name: 'Tambah BRAS', exact: true })).toBeVisible()
  expect(filters!.y).toBeGreaterThanOrEqual(bar!.y + bar!.height)
  if (page.viewportSize()!.width > 720) expect(Math.abs(title!.y + title!.height / 2 - bar!.y - bar!.height / 2)).toBeLessThanOrEqual(2)
  else expect(bar!.y).toBeGreaterThanOrEqual(title!.y + title!.height)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}
for (const width of [375, 1280]) {
  test(`VPN credentials, configuration downloads and retained list at ${width}px`, async ({ page }, testInfo) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/vpn')
    const search = page.getByPlaceholder('Cari label, server, username, atau IP…')
    await search.fill('MikroTik')
    const originalDocument = await page.evaluateHandle(() => document)
    await page.getByRole('button', { name: 'Buat akun VPN', exact: true }).click()
    const form = page.getByRole('dialog', { name: 'Buat akun VPN', exact: true })
    await form.getByRole('textbox', { name: 'Label', exact: true }).fill('MikroTik Lab Laptop')
    await form.getByRole('tab', { name: 'Tinjau + buat' }).click()
    await form.getByRole('button', { name: 'Buat akun VPN', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Sambungkan perangkat', exact: true })
    await expect(panel).toBeVisible()
    await expect(form).toHaveCount(0)
    await expect(panel.getByLabel('Password', { exact: true })).toHaveAttribute('type', 'password')
    await expect(panel.getByRole('textbox', { name: 'Username', exact: true })).toHaveValue('mikrotik-lab-laptop')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('vpn-connection.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('tab', { name: 'Konfigurasi', exact: true }).click()
    await expect(panel.locator('pre')).toContainText('cipher=aes256-cbc')
    await page.screenshot({ path: testInfo.outputPath('vpn-configuration.png'), fullPage: true, animations: 'disabled' })
    const v7Download = page.waitForEvent('download')
    await panel.getByRole('button', { name: 'Unduh .rsc' }).click()
    expect((await v7Download).suggestedFilename()).toBe('mikrotik-lab-laptop.rsc')
    await panel.getByRole('combobox', { name: 'Versi RouterOS' }).selectOption('V6')
    await expect(panel.locator('pre')).toContainText('cipher=aes256 disabled=no')
    const request = page.waitForRequest('**/api/vpn/accounts/vpn-1/ovpn?variant=V6'), download = page.waitForEvent('download')
    await panel.getByRole('button', { name: 'Unduh .ovpn' }).click()
    await request
    expect((await download).suggestedFilename()).toBe('mikrotik-lab-laptop-v6.ovpn')
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await expect(search).toHaveValue('MikroTik')
    await expect(page.getByRole('gridcell', { name: 'MikroTik Lab Laptop Aksi sel', exact: true })).toBeVisible()
    expect(await page.evaluate(original => document === original, originalDocument)).toBe(true)
    await expect(page).toHaveURL('/vpn')
    expect(writes.map(write => write.path)).toEqual(['/api/vpn/accounts/generate'])
    expect(errors).toEqual([])
  })
  test(`BRAS step navigation, nested guide and save at ${width}px`, async ({ page }, testInfo) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/bras')
    await expect(page.getByText('BRAS Tebet', { exact: true })).toBeVisible()
    await headerControls(page)
    await page.screenshot({ path: testInfo.outputPath('bras-list.png'), fullPage: true })
    await page.getByRole('button', { name: 'Tambah BRAS', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Tambah BRAS', exact: true })
    await panel.getByRole('button', { name: 'Berikutnya: RADIUS' }).click()
    await expect(panel.getByRole('alert')).toContainText('Isi nama BRAS')
    await panel.getByRole('textbox', { name: 'Nama BRAS' }).fill('BRAS Selatan')
    await panel.getByRole('textbox', { name: 'Alamat manajemen' }).fill('10.8.0.4')
    await page.screenshot({ path: testInfo.outputPath('bras-basics.png'), fullPage: true })
    await panel.getByRole('button', { name: 'Berikutnya: RADIUS' }).click()
    await panel.getByLabel('Shared secret RADIUS', { exact: true }).fill('demo-radius-secret')
    await panel.getByRole('button', { name: 'Lihat konfigurasi RouterOS' }).click()
    const guide = page.getByRole('dialog', { name: 'Konfigurasi RouterOS', exact: true })
    await expect(guide).toHaveAttribute('data-resource-layer', '1')
    await guide.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Lihat konfigurasi RouterOS' })).toBeFocused()
    await expect(panel.getByLabel('Shared secret RADIUS', { exact: true })).toHaveValue('demo-radius-secret')
    await panel.getByRole('button', { name: 'Berikutnya: Akses RouterOS' }).click()
    await panel.getByRole('textbox', { name: 'Nama pengguna API' }).fill('operator')
    await panel.getByRole('spinbutton', { name: 'Port API' }).fill('8443')
    await page.screenshot({ path: testInfo.outputPath('bras-routeros.png'), fullPage: true })
    await panel.getByRole('button', { name: 'Berikutnya: Cakupan area' }).click()
    await panel.getByRole('checkbox', { name: 'Tebet' }).check()
    await panel.getByRole('tab', { name: 'Dasar', exact: true }).click()
    await expect(panel.getByRole('textbox', { name: 'Nama BRAS' })).toHaveValue('BRAS Selatan')
    await panel.getByRole('tab', { name: 'Tinjau + buat' }).click()
    await expect(panel.getByRole('tabpanel', { name: 'Tinjau + buat' })).not.toContainText('demo-radius-secret')
    expect(writes).toHaveLength(0)
    await page.screenshot({ path: testInfo.outputPath('bras-review.png'), fullPage: true })
    await panel.getByRole('button', { name: 'Buat', exact: true }).click()
    await expect(panel).toHaveCount(0)
    expect(writes).toHaveLength(1)
    expect(writes[0].body).toMatchObject({ name: 'BRAS Selatan', areaIds: ['tebet'], apiPort: 8443 })
    expect(errors).toEqual([])
  })
  test(`ACS tabs, filters, single-line values and settings at ${width}px`, async ({ page }, testInfo) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/acs')
    await expect(page.getByText('Total perangkat', { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('acs-overview.png'), fullPage: true })
    await page.getByRole('tab', { name: 'Perangkat', exact: true }).click()
    await expect(page.getByText('HWTC00112233', { exact: true })).toBeVisible()
    await compactControls(page)
    const heights = await page.locator('.network-panel:not([hidden]) .fui-DataGridCell').evaluateAll(cells => cells.map(cell => cell.getBoundingClientRect().height))
    expect(heights.length).toBeGreaterThan(0)
    expect(Math.max(...heights)).toBeLessThanOrEqual(40)
    await page.screenshot({ path: testInfo.outputPath('acs-devices.png'), fullPage: true })
    await page.getByRole('button', { name: 'Filter Status ACS' }).click()
    await page.getByRole('combobox', { name: 'Status ACS' }).selectOption('OFFLINE')
    await expect(page.getByText('HWTC00112233', { exact: true })).toHaveCount(0)
    await expect(page.getByText('ZTEG44556677', { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: 'Setelan ONT', exact: true }).click()
    await expect(page.getByText('demo-secret', { exact: true })).toHaveCount(0)
    await expect(page.getByText('300 detik', { exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('acs-settings.png'), fullPage: true })
    await page.getByRole('tab', { name: 'Perangkat', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Filter Status ACS' })).toContainText('Offline')
    await page.getByRole('button', { name: 'Hapus Status ACS' }).click()
    await expect(page.getByText('HWTC00112233', { exact: true })).toBeVisible()
    await page.getByRole('tab', { name: 'Aktivitas', exact: true }).click()
    await expect(page.getByRole('columnheader', { name: 'Tindakan' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('acs-activity.png'), fullPage: true })
    expect(writes).toHaveLength(0)
    expect(errors).toEqual([])
  })
}

for (const width of [375, 1440]) {
  test(`RADIUS capacity recovery retains the tenant BRAS form at ${width}px`, async ({ page }, info) => {
    const { errors, writes } = await setup(page, false)
    let calls = 0, resume!: () => void
    await page.route('**/api/bng/radius-endpoint', async route => {
      calls += 1
      if (calls === 1) { await route.fulfill({ status: 409, json: { detail: 'Kapasitas server RADIUS penuh. Hubungi administrator platform.' } }); return }
      await new Promise<void>(resolve => { resume = resolve })
      await route.fulfill({ json: { host: 'vpn.example.test', authPort: 1812, acctPort: 1813, coaPort: 3799, configured: true,
        vpnHosts: [{ tunnelCidr: '10.208.67.0/24', host: '10.208.67.1' }], isolirAddressList: 'isolir' } })
    })
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/bras')
    await page.getByRole('button', { name: 'Tambah BRAS', exact: true }).click()
    const form = page.getByRole('dialog', { name: 'Tambah BRAS', exact: true })
    await form.getByRole('textbox', { name: 'Nama BRAS', exact: true }).fill('BRAS Demo')
    await form.getByRole('textbox', { name: 'Alamat manajemen', exact: true }).fill('10.208.67.2')
    await form.getByRole('button', { name: 'Berikutnya: RADIUS' }).click()
    await expect(form.getByRole('alert')).toContainText('Kapasitas server RADIUS penuh.')
    await expect(form.getByText('Belum diatur', { exact: true })).toHaveCount(0)
    await form.getByLabel('Shared secret RADIUS', { exact: true }).fill('fixture-radius-secret')
    await form.getByRole('button', { name: 'Lihat konfigurasi RouterOS' }).click()
    const guide = page.getByRole('dialog', { name: 'Konfigurasi RouterOS', exact: true })
    await expect(guide.getByRole('alert')).toContainText('Kapasitas server RADIUS penuh.')
    await page.screenshot({ path: info.outputPath('radius-capacity.png'), fullPage: true })
    const retained = await guide.elementHandle()
    await guide.getByRole('button', { name: 'Coba lagi', exact: true }).click()
    await expect(guide.getByRole('status')).toContainText('Memuat server RADIUS')
    await expect.poll(() => calls).toBe(2); resume()
    await expect(guide.locator('code').first()).toContainText('address=10.208.67.1 secret=fixture-radius-secret')
    await expect(guide.locator('code').first()).toContainText('authentication-port=1812 accounting-port=1813')
    expect(await retained?.evaluate(node => node.isConnected)).toBe(true)
    await page.screenshot({ path: info.outputPath('radius-routeros-ready.png'), fullPage: true })
    await guide.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(form.getByLabel('Shared secret RADIUS', { exact: true })).toHaveValue('fixture-radius-secret')
    await form.getByRole('tab', { name: 'Dasar', exact: true }).click()
    await expect(form.getByRole('textbox', { name: 'Nama BRAS', exact: true })).toHaveValue('BRAS Demo')
    await expect(form.getByRole('textbox', { name: 'Alamat manajemen', exact: true })).toHaveValue('10.208.67.2')
    expect(writes).toEqual([]); expect(errors).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
