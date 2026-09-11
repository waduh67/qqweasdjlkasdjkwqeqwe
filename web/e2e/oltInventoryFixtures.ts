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
type PollMode = 'reachable' | 'unreachable' | 'error' | 'malformed'

export async function installOltInventoryRoutes(
  page: Page,
  allowDeviceRead = true,
  allowManualPoll = true,
  oltOverrides: Partial<OltView> = {},
) {
  const servedOlt = { ...olt, ...oltOverrides }
  let mode: Mode = 'populated'
  let pollMode: PollMode = 'reachable'
  let reads = 0
  let polls = 0
  let oltListReads = 0
  let oltDetailReads = 0
  let impactedReads = 0
  let tileReads = 0
  let pollGeneration = 0
  let pendingPoll: { promise: Promise<void>; resolve: () => void } | null = null
  const permissions = ['network.olt.view', 'gis.map.view', 'network.odp.view', 'customer.customer.view']
  if (allowDeviceRead) permissions.push('monitoring.provisioning.view')
  if (allowManualPoll) permissions.push('monitoring.collector.manage')
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
    if (path === '/api/olts') {
      oltListReads += 1
      return json(route, { content: [servedOlt], page: 0, size: 25, totalElements: 1, totalPages: 1 })
    }
    if (path === '/api/olts/' + servedOlt.id) {
      oltDetailReads += 1
      return json(route, servedOlt)
    }
    if (path === '/api/sites') return json(route, { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 })
    if (path === '/api/gis/olts/' + servedOlt.id + '/onus') return json(route, { oltId: servedOlt.id, oltCode: servedOlt.code, onus: [] })
    if (path === '/api/gis/impacted') {
      impactedReads += 1
      return json(route, { cables: [], nodes: [] })
    }
    if (path.includes('/api/gis/tiles/')) {
      tileReads += 1
      return route.fulfill({
        status: 200, contentType: 'application/vnd.mapbox-vector-tile', body: Buffer.alloc(0),
      })
    }
    if (path === '/api/monitoring/olts/' + servedOlt.id + '/poll' && route.request().method() === 'POST') {
      polls += 1
      const deferred = pendingPoll
      if (deferred) {
        await deferred.promise
        if (pendingPoll === deferred) pendingPoll = null
      }
      if (pollMode === 'error') {
        return route.fulfill({
          status: 409, contentType: 'application/json',
          body: JSON.stringify({ detail: 'Polling SNMP sedang berjalan' }),
        })
      }
      if (pollMode === 'malformed') return json(route, { oltId: servedOlt.id })
      pollGeneration += 1
      return json(route, {
        oltId: servedOlt.id, oltCode: servedOlt.code,
        reachable: pollMode === 'reachable', readingCount: pollMode === 'reachable' ? 2 : 0,
        failureReason: pollMode === 'reachable' ? null : 'timeout', checkedAt: '2026-09-11T08:00:00Z',
      })
    }
    if (path === '/api/monitoring/olts/' + servedOlt.id + '/onus') {
      reads += 1
      switch (mode) {
        case 'populated': return json(route, pollGeneration === 0 ? snapshot : {
          ...snapshot,
          readAt: '2026-09-11T08:00:01Z',
          onus: [{ ...snapshot.onus[0], serialNumber: 'AFTERPOLL001' }],
        })
        case 'empty': return json(route, { ...snapshot, onus: [], warnings: ['Sub-tree serial kosong; periksa kecocokan OID.'] })
        case 'error': return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ message: 'Pembacaan SNMP kehabisan waktu' }) })
      }
    }
    return json(route, [])
  })
  await page.route(new RegExp('https://.*(?:cartocdn|arcgisonline|demotiles[.]maplibre)[.].*'), (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64'),
    })
  })
  return {
    setMode: (next: Mode) => { mode = next },
    setPollMode: (next: PollMode) => { pollMode = next },
    deferNextPoll: () => {
      let resolve = () => undefined
      const promise = new Promise<void>((done) => { resolve = done })
      pendingPoll = { promise, resolve }
      return () => pendingPoll?.resolve()
    },
    readCount: () => reads,
    pollCount: () => polls,
    oltListReadCount: () => oltListReads,
    oltDetailReadCount: () => oltDetailReads,
    impactedReadCount: () => impactedReads,
    tileReadCount: () => tileReads,
  }
}
