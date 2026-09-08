import type { Page, Route } from '@playwright/test'
import type { OltView } from '../src/api/network'
import type { OltOnusSnapshot } from '../src/api/oltOnus'

export const olt: OltView = {
  id: 'olt-qa', code: 'OLT-QA', name: 'OLT Laboratorium', siteId: 'site-qa', siteName: 'Lab',
  vendor: 'HSGQ', model: 'HSGQ-G01ID', managementIp: '127.0.0.1', status: 'ACTIVE',
  snmpConfigured: true, snmpPort: 1161, pollable: true, ponPortCount: 1,
  location: { longitude: 106.995, latitude: -6.243 }, areaId: null, description: null,
  snmpEnabled: true, snmpVersion: 'V2C', webEnabled: false, webProtocol: 'HTTP',
  webPort: null, webUsername: null, webPasswordConfigured: false,
}

const snapshot: OltOnusSnapshot = {
  oltId: olt.id, oltCode: olt.code, vendor: olt.vendor, systemDescription: 'HSGQ-G01ID',
  readAt: '2026-09-08T12:00:00Z',
  onus: [
    { index: '16777472', ontId: 'PON01/0', name: 'ONT01/000', serialNumber: 'TEST001122AA',
      state: 'Active', runningState: 'ONLINE', configState: 'Normal', deviceType: null,
      rxPowerDbm: -22, lastUpTime: '1970/01/12 19:22:12', lastDownTime: null, lastDownCause: null },
    { index: '16777479', ontId: 'PON01/7', name: 'ONT01/007', serialNumber: 'TEST001122BB',
      state: 'Active', runningState: 'OFFLINE', configState: 'Initial', deviceType: null,
      rxPowerDbm: null, lastUpTime: null, lastDownTime: null, lastDownCause: null },
  ],
  warnings: ['Device type, last down time, dan last down cause belum dipetakan untuk firmware ini.'],
}

type Mode = 'populated' | 'empty' | 'error'

export async function installOltInventoryRoutes(page: Page, allowDeviceRead = true) {
  let mode: Mode = 'populated'
  let reads = 0
  const permissions = ['network.olt.view', 'gis.map.view', 'network.odp.view', 'customer.customer.view']
  if (allowDeviceRead) permissions.push('monitoring.provisioning.view')
  const profile = {
    id: 'operator-qa', email: 'operator@example.test', name: 'Operator QA', tenantId: 'tenant-qa',
    tenantSlug: 'qa', platformAdmin: false, roleIds: ['role-qa'], permissions, areaIds: [], twoFactorEnabled: true,
  }
  const json = (route: Route, body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'browser-test-refresh'))
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/refresh') return json(route, {
      accessToken: 'browser-test-token', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z',
      refreshToken: 'browser-test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile,
    })
    if (path === '/api/me') return json(route, profile)
    if (path === '/api/subscription/lock') return json(route, { locked: false })
    if (path === '/api/olts') return json(route, { content: [olt], page: 0, size: 25, totalElements: 1, totalPages: 1 })
    if (path === '/api/olts/' + olt.id) return json(route, olt)
    if (path === '/api/sites') return json(route, { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 })
    if (path === '/api/gis/olts/' + olt.id + '/onus') return json(route, { oltId: olt.id, oltCode: olt.code, onus: [] })
    if (path === '/api/gis/impacted') return json(route, { cables: [], nodes: [] })
    if (path.includes('/api/gis/tiles/')) return route.fulfill({ status: 204 })
    if (path === '/api/monitoring/olts/' + olt.id + '/onus') {
      reads += 1
      switch (mode) {
        case 'populated': return json(route, snapshot)
        case 'empty': return json(route, { ...snapshot, onus: [], warnings: ['Sub-tree serial kosong; periksa kecocokan OID.'] })
        case 'error': return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ message: 'Pembacaan SNMP kehabisan waktu' }) })
      }
    }
    return json(route, [])
  })
  await page.route(new RegExp('https://.*(?:cartocdn|arcgisonline|demotiles[.]maplibre)[.].*'), (route) => {
    if (route.request().url().endsWith('.png')) return route.fulfill({
      contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6L1kAAAAASUVORK5CYII=', 'base64'),
    })
    return route.fulfill({ status: 204 })
  })
  return { setMode: (next: Mode) => { mode = next }, readCount: () => reads }
}
