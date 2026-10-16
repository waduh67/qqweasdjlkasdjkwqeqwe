import { expect, test, type Page, type Locator } from '@playwright/test'
import { assetHistoryFixture, assetWorkspaceFixture, assetIds } from '../src/test/customerAssetFixture'

const now = '2026-10-17T08:00:00Z'
const customer = { id: assetIds.customer, code: 'C-001', name: 'Budi Santoso', phone: '081234567890', email: 'budi@example.test', address: 'Jl. Cihanjuang No. 12, Cimahi', location: { longitude: 107.55, latitude: -6.87 }, areaId: null, idCardNumber: '3204000000000001', status: 'ACTIVE', awaitingInstallation: false,
  subscription: { id: 'sub-1', customerId: assetIds.customer, planId: 'plan-1', packageName: 'Rumah 50 Mbps', bandwidthMbps: 50, monthlyFee: '150000', status: 'ACTIVE', activatedAt: '2026-10-01T00:00:00Z', terminatedAt: null },
  onus: [{ id: 'onu-1', serialNumber: 'FIBERLAB0001', status: 'ONLINE', odpId: 'odp-1', odpCode: 'ODP-CIMAHI-001', odpPortNumber: 3, installRxPowerDbm: -20.5, installedAt: '2026-10-01T01:00:00Z' }],
}
const account = { id: 'access-1', subscriptionId: 'sub-1', customerId: customer.id, username: 'pelanggan0001', authType: 'PPPOE', planId: 'plan-1', planName: 'Rumah 50 Mbps', subscriptionPlanId: 'plan-1', nasId: 'nas-1', nasName: 'BRAS-CIMAHI', framedIp: '100.64.0.10', status: 'ACTIVE', fupEnabled: true, fupThrottled: false, periodUsageMb: 2048, fupQuotaMb: 100000 }
const session = { subscriberAccessId: account.id, username: account.username, online: true, framedIp: account.framedIp, nasId: 'nas-1', nasName: account.nasName, nasIp: '10.0.0.1', callingStationId: 'AA:BB:CC:DD:EE:01', uptimeSeconds: 4200, startedAt: now, lastSeenAt: now }
const cpe = { id: 'cpe-1', genieacsId: 'AABBCC-FiberLab-FIBERLAB0001', serialNumber: 'FIBERLAB0001', customerId: customer.id, onuId: 'onu-1', oui: 'AABBCC', productClass: 'FiberLab', manufacturer: 'FiberLab', model: 'HG-8245', softwareVersion: 'V1.2.3', ipAddress: '100.64.0.10', lastInformAt: now, online: true }
const invoice = { id: 'inv-1', number: 'INV-2026-10-0001', customerId: customer.id, subscriptionId: 'sub-1', periodStart: '2026-10-01', periodEnd: '2026-10-31', dueDate: '2026-10-10', amount: '166500', taxAmount: '16500', prorated: true, proratedDays: 16, status: 'OVERDUE', issuedAt: now, paidAt: null }

async function setup(page: Page, readOnly = false) {
  const profile = { id: 'qa', email: 'admin@example.test', name: 'Rina', tenantId: 'qa', tenantSlug: 'qa', platformAdmin: !readOnly, permissions: ['customer.customer.view', 'customer.onu.view', 'bng.access.view', 'bng.session.view', 'catalog.plan.view', 'billing.invoice.view', 'portal.credential.view', 'monitoring.metric.view', 'cpe.device.view', 'cpe.wifi.view', 'incident.ticket.view', 'workorder.order.view'], areaIds: [], roleIds: [], twoFactorEnabled: false }
  const writes: string[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    if (req.method() !== 'GET' && path !== '/api/auth/refresh') { writes.push(path); await route.fulfill({ status: 500, json: { detail: 'No writes in display tests.' } }); return }
    let body: unknown = []
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', refreshToken: 'test-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/v2/warehouse/workflow') body = { snapshot: { tenantId: assetIds.customer, epoch: 1, state: 'ENFORCED', workflow: 'REFERENCE' }, owner: true }
    else if (path === '/api/customers') body = { content: [customer], totalElements: 1, totalPages: 1, page: 0, size: 50 }
    else if (path === `/api/customers/${customer.id}`) body = customer
    else if (path.startsWith('/api/subscriber-360/')) body = { customer, subscription: customer.subscription, placement: null, session, cpeDevices: [cpe], openWorkOrder: { code: 'WO-2026-001' }, billing: { outstandingAmount: '166500' }, access: { billing: true, subscription: true, placement: true, session: true, cpe: true, workOrder: true } }
    else if (path.endsWith('/neighbors')) {
      const neighbor = { customerId: customer.id, customerName: customer.name, self: true, portNumber: 3, odpCode: 'ODP-CIMAHI-001', onuSerialNumber: 'FIBERLAB0001', liveStatus: 'ONLINE', onuStatus: 'ONLINE', liveRxPowerDbm: -20.5, installRxPowerDbm: -20.5, distanceMeters: 1250 }
      body = { sameOdp: [neighbor, { ...neighbor, customerId: 'customer-2', customerName: 'Siti Lestari', self: false, portNumber: 4, onuSerialNumber: 'FIBERLAB0002' }], samePonPort: [neighbor] }
    } else if (path.startsWith('/api/gis/trace/')) body = { hops: [{ kind: 'CUSTOMER', code: 'C-001', detail: customer.name }, { kind: 'ODP', code: 'ODP-CIMAHI-001', detail: 'Port 3' }, { kind: 'ODC', code: 'ODC-CIMAHI-001', detail: 'Splitter 1:8' }, { kind: 'PON_PORT', code: 'PON1' }, { kind: 'OLT', code: 'OLT-CIMAHI-001' }, { kind: 'SITE', code: 'POP-CIMAHI' }, { kind: 'BRAS', code: 'BRAS-CIMAHI', online: true }], onuSerialNumber: 'FIBERLAB0001', odpPortNumber: 3, installRxPowerDbm: -20.5, liveRxPowerDbm: -20.7, opticalHealth: 'GOOD', distanceMeters: 1250, estimatedLossDb: 20.5, bras: { ...session, rateProfileName: '50M/25M' } }
    else if (path.startsWith('/api/onboarding/migrations/')) body = { customerId: customer.id, billingManaged: true, billingDay: 10, firstInvoiceOn: '2026-11-10', accessId: account.id, username: account.username, authType: 'PPPOE', onus: [{ onuId: 'onu-1', oltCode: 'OLT-CIMAHI-001', ponPortLabel: 'PON1' }] }
    else if (path === '/api/catalog/plans') body = [{ id: 'plan-1', name: 'Rumah 50 Mbps', downMbps: 50, upMbps: 25, price: 150000, active: true, serviceTypes: ['PPPOE'], fupEnabled: false }]
    else if (path === '/api/bng/nas') body = [{ id: 'nas-1', name: 'BRAS-CIMAHI' }]
    else if (path === '/api/bng/access') body = [account]
    else if (path.endsWith('/session')) body = session
    else if (path.endsWith('/traffic')) body = { subscriberAccessId: account.id, hours: Number(url.searchParams.get('hours')), currentDownMbps: 18.4, currentUpMbps: 2.1, totalBytes: 5830400000, points: Array.from({ length: 12 }, (_, i) => ({ time: new Date(Date.parse(now) - (12 - i) * 3600000).toISOString(), downMbps: 10 + i % 5 * 4, upMbps: 1 + i % 3 })) }
    else if (path.endsWith('/metrics')) body = [{ onuId: 'onu-1', serialNumber: 'FIBERLAB0001', time: now, status: 'ONLINE', rxPowerDbm: -20.7, txPowerDbm: 2.5, distanceMeters: 1250, downCause: 'DYING_GASP', lastOffAt: '2026-10-16T07:00:00Z', lastOnAt: '2026-10-16T07:05:00Z' }]
    else if (path === '/api/monitoring/onus/onu-1/history') body = { onuId: 'onu-1', averageRxPowerDbm: -20.5, minRxPowerDbm: -21, maxRxPowerDbm: -20.2, trendDbPerDay: -0.1, degrading: false, points: Array.from({ length: 12 }, (_, i) => ({ time: new Date(Date.parse(now) - (12 - i) * 3600000).toISOString(), rxPowerDbm: -20.5 - i % 3 * 0.1, status: 'ONLINE' })) }
    else if (path === '/api/cpe/devices') body = [cpe]
    else if (path === '/api/cpe/devices/cpe-1') body = { device: cpe, recentActions: [{ id: 'action-1', action: 'REBOOT', status: 'SUCCESS', detail: 'Perintah diterima', requestedBy: 'qa', requestedByEmail: 'admin@example.test', requestedAt: now }] }
    else if (path.endsWith('/live')) body = { wifi: [{ ref: 'wlan1', ssid: 'Rumah Budi', passphrase: null, band: '2.4 GHz', enabled: true }], hosts: [{ hostName: 'Laptop Budi', ipAddress: '192.168.1.10', macAddress: 'AA:BB:CC:DD:EE:01', active: true }] }
    else if (path.endsWith('/firmware')) body = [{ name: 'HG8245-V1.3.0.bin', version: 'V1.3.0', sizeBytes: 12000000 }]
    else if (path.startsWith('/api/portal-admin/')) body = { provisioned: true, active: true, login: 'C-001' }
    else if (path === '/api/billing/invoices') body = [invoice, { ...invoice, id: 'inv-2', number: 'INV-2026-09-0001', status: 'PAID', paidAt: now }]
    else if (path === '/api/incidents') body = [{ id: 'inc-1', title: 'Kabel putus di Cihanjuang', rootLabel: 'ODP-CIMAHI-001', severity: 'CRITICAL', status: 'OPEN', openedAt: now }]
    else if (path === '/api/work-orders') body = { content: [{ id: 'wo-1', code: 'WO-2026-001', title: 'Pemeriksaan jalur pelanggan Cihanjuang', type: 'REPAIR', status: 'IN_PROGRESS', createdAt: now, completedAt: null, assignees: [{ name: 'Andi' }, { name: 'Rina' }], incidentId: 'inc-1' }] }
    else if (path.includes('workbench/history')) body = { items: [assetHistoryFixture()], page: 0, size: 10, totalElements: 1 }
    else if (path.endsWith('/workbench')) body = assetWorkspaceFixture()
    else if (path === '/api/cpe/acs/server') body = { configured: true, cwmpUrl: 'https://acs.example.test', acsUsername: 'cpe', acsPassword: null, connectionRequestUsername: 'cpe', connectionRequestPassword: null, periodicInformEnabled: true, periodicInformIntervalSeconds: 300 }
    await route.fulfill({ status: 200, json: body })
  })
  return { writes, errors }
}
async function section(panel: Locator, label: string) {
  await expect(panel.getByRole('tabpanel')).toBeVisible()
  const picker = panel.getByRole('combobox', { name: 'Bagian pelanggan' })
  if (await picker.isVisible()) await picker.selectOption({ label })
  else await panel.getByRole('tab', { name: label, exact: true }).click()
  return panel.getByRole('tabpanel', { name: label, exact: true })
}
const sections = ['Ringkasan', 'Langganan', 'Akun jaringan', 'Perangkat', 'Portal pelanggan', 'Jalur koneksi', 'Pelanggan sekitar', 'Metrik', 'Trafik', 'ACS', 'Tagihan', 'Tiket & work order', 'Aktivitas']
for (const width of [375, 1440]) for (const theme of ['light', 'dark']) {
  test(`complete customer sections ${width}px ${theme}`, async ({ page }, info) => {
    const { writes, errors } = await setup(page)
    await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/customers?search=Budi')
    await page.getByRole('button', { name: customer.name, exact: true }).click()
    const panel = page.getByRole('dialog', { name: customer.name, exact: true })
    for (const label of sections) {
      const content = await section(panel, label)
      await expect(content.getByRole('heading').first()).toBeVisible()
      await expect(content.getByText(/Memuat/).first()).not.toBeVisible()
      expect(await content.locator('.fui-DataGridCell .badge,.fui-DataGridCell .card,.fui-DataGridCell .fui-Badge').count()).toBe(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      for (const input of await content.locator('.customer-filters input, .customer-filters select').all()) {
        const box = await input.boundingBox(); expect(box?.width, `${label}: usable filter width`).toBeGreaterThan(140)
      }
      if (['Ringkasan', 'Akun jaringan', 'Metrik', 'ACS', 'Tagihan', 'Tiket & work order'].includes(label)) await page.screenshot({ path: info.outputPath(`${label.replaceAll(' ', '-')}.png`), fullPage: true, animations: 'disabled' })
    }
    await section(panel, 'Pelanggan sekitar'); await panel.getByRole('textbox', { name: 'Cari pelanggan sekitar' }).fill('Siti')
    await expect(panel.getByRole('gridcell', { name: 'Siti Lestari', exact: true })).toBeVisible()
    await expect(panel.getByRole('gridcell', { name: 'Budi Santoso (pelanggan ini)', exact: true })).toHaveCount(0)
    await section(panel, 'Tagihan'); await panel.getByRole('combobox', { name: 'Status tagihan' }).selectOption('PAID')
    await expect(panel.getByRole('gridcell', { name: 'INV-2026-09-0001', exact: true })).toBeVisible()
    await expect(panel.getByRole('gridcell', { name: 'INV-2026-10-0001', exact: true })).toHaveCount(0)
    await section(panel, 'Akun jaringan'); await panel.getByRole('button', { name: 'Ganti paket / BRAS', exact: true }).click()
    const edit = page.getByRole('dialog', { name: 'Ganti paket / BRAS', exact: true }); await expect(edit).toHaveAttribute('data-resource-layer', '1')
    await edit.getByRole('button', { name: 'Tutup', exact: true }).click(); await expect(panel.getByRole('heading', { name: 'Sesi jaringan' })).toBeVisible()
    await panel.getByRole('button', { name: 'Reset password', exact: true }).click()
    const reset = page.getByRole('dialog', { name: 'Reset password jaringan', exact: true }); await expect(reset.getByLabel('Password baru')).toHaveAttribute('type', 'password')
    await reset.getByRole('button', { name: 'Tampilkan password' }).click(); await expect(reset.getByRole('textbox', { name: 'Password baru' })).toBeVisible()
    await reset.getByRole('button', { name: 'Tutup', exact: true }).click()
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click(); await expect(page).toHaveURL('/customers?search=Budi')
    expect(writes).toEqual([]); expect(errors).toEqual([])
  })
}
test('read-only customer hides writes and reports traffic failures without stale values', async ({ page }) => {
  const { writes, errors } = await setup(page, true)
  await page.goto('/customers'); await page.getByRole('button', { name: customer.name, exact: true }).click()
  const panel = page.getByRole('dialog', { name: customer.name, exact: true })
  await section(panel, 'Akun jaringan'); await expect(panel.getByText(account.username, { exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Reset password', exact: true })).toHaveCount(0)
  await section(panel, 'Portal pelanggan'); await expect(panel.getByText('C-001', { exact: true }).last()).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Nonaktifkan', exact: true })).toHaveCount(0)
  await section(panel, 'Trafik'); await expect(panel.getByText('18.4 Mbps', { exact: true })).toBeVisible()
  await page.route('**/api/bng/access/access-1/traffic?hours=168', route => route.fulfill({ status: 503, json: { detail: 'Unavailable' } }))
  await panel.getByRole('button', { name: '7 hari', exact: true }).click()
  await expect(panel.getByRole('alert')).toHaveText('Tren trafik gagal dimuat.')
  await expect(panel.getByText('18.4 Mbps', { exact: true })).toHaveCount(0)
  expect(writes).toEqual([]); expect(errors).toEqual([])
})
