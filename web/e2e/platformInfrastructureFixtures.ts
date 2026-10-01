import type { Page } from '@playwright/test'
import type { PlatformAcsSettings, UpdatePlatformAcsSettings } from '../src/api/platformAcs'
import type { RadiusServerView } from '../src/api/radiusServer'
import type { VpnServerView } from '../src/api/vpn'
import type { PivotMasterConfigView } from '../src/api/platformBilling'

export async function installInfrastructureRoutes(page: Page, platformAdmin = true) {
  const profile = { id: 'qa', email: 'qa@example.test', name: 'QA', tenantId: 'qa', tenantSlug: 'qa',
    platformAdmin, permissions: ['platform.acs.view', 'cpe.acs.view', 'cpe.device.view'],
    areaIds: [], roleIds: [], twoFactorEnabled: true }
  let acs: PlatformAcsSettings = { version: 'qa-v1', nbiUrl: 'https://api-acs.example.test',
    username: 'api-user', passwordSet: true, cwmpUrl: 'http://acs.example.test:7547/', persisted: false }
  const radius: RadiusServerView = { id: 'radius-1', name: 'RADIUS QA', host: 'radius.example.test',
    authPort: 1812, acctPort: 1813, coaPort: 3799, sharedSecret: 'synthetic-radius-secret',
    dbUrl: 'jdbc:postgresql://db.example.test:5432/radius', dbUser: 'radius',
    maxTenants: 2, tenantCount: 0, status: 'ACTIVE' }
  const vpn: VpnServerView = { id: 'vpn-1', name: 'Hub QA', host: 'vpn.example.test', port: 1194,
    protocol: 'TCP', tunnelCidr: '10.8.0.0/24', serverAddress: '10.8.0.1', status: 'ACTIVE',
    hasCaCert: true, hasTlsAuth: false, pkiReady: true, peerCount: 0, nodeToken: null, installCommand: null }
  const billing = { defaultGraceDays: 7, defaultDueDays: 14, defaultBillingDay: 1, defaultMonthlyFee: 100000, currency: 'IDR' }
  const pivot: PivotMasterConfigView = { enabled: false, sandbox: true, merchantIdSet: true,
    merchantSecretSet: true, callbackApiKeySet: false, credentialsSet: true, platformFeeMinor: 0,
    platformFeeType: 'FIXED', payoutFeeMinor: 0, payoutFeeType: 'FIXED', payoutChannelCode: null,
    payoutAccountNumber: null, defaultBusinessType: null, defaultBusinessStructure: null,
    defaultParentIndustry: null, defaultChildIndustry: null, defaultMcc: null, defaultDigitalStatus: null,
    defaultBusinessCountry: null, defaultCountryOfEntity: null, defaultLogoUrl: null, defaultWebsite: null,
    defaultDistrictId: null, defaultPostCode: null }
  const events: { method: string; path: string; body: unknown }[] = []
  let failLoad = false, failSave = false, failRadiusSave = false
  let pending: { promise: Promise<void>; resolve: () => void } | null = null
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const data: unknown = request.postData() ? request.postDataJSON() : null
    events.push({ method: request.method(), path, body: data })
    let status = 200, body: unknown = []
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', tokenType: 'Bearer',
      accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'test-refresh',
      refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path.startsWith('/api/vpn/servers')) body = request.method() === 'GET' ? [vpn] : vpn
    else if (path.startsWith('/api/platform/radius-servers')) {
      if (path.endsWith('/test-connection')) body = { success: true, message: 'Database terhubung' }
      else if (request.method() === 'GET') body = [radius]
      else if (failRadiusSave) { status = 503; body = { detail: 'Gagal menyimpan RADIUS sintetis' } }
      else body = radius
    }
    else if (path === '/api/platform/billing/settings') body = billing
    else if (path === '/api/platform/pivot-config') body = pivot
    else if (path === '/api/platform/acs-settings') {
      if (request.method() === 'PUT') {
        const deferred = pending
        if (deferred) { await deferred.promise; pending = null }
        if (failSave) { status = 503; body = { detail: 'Gagal menyimpan ACS sintetis' } }
        else {
          const update = request.postDataJSON() as UpdatePlatformAcsSettings
          acs = { ...acs, nbiUrl: update.nbiUrl, username: update.username, cwmpUrl: update.cwmpUrl,
            passwordSet: acs.passwordSet || !!update.password, persisted: true, version: 'qa-v2' }
          body = acs
        }
      } else if (failLoad) { status = 503; body = { detail: 'Gagal memuat ACS sintetis' } }
      else body = acs
    } else if (path === '/api/platform/acs-settings/test') body = { reachable: true, latencyMs: 12, error: null }
    else if (path === '/api/cpe/acs/server') body = { nbiBaseUrl: acs.nbiUrl, cwmpUrl: acs.cwmpUrl,
      acsUsername: null, acsPassword: null, connectionRequestUsername: null, connectionRequestPassword: null,
      periodicInformEnabled: true, periodicInformIntervalSeconds: 300, syncIntervalSeconds: 300, configured: true }
    else if (path === '/api/cpe/acs/health') body = { status: 'ONLINE', latencyMs: 12,
      checkedAt: '2026-10-01T00:00:00Z', message: 'ACS terjangkau' }
    else if (path === '/api/cpe/acs/stats') body = { totalDevices: 0, onlineDevices: 0, offlineDevices: 0,
      avgRxPowerDbm: null, signalSampleCount: 0, lastSyncAt: null, lastSyncOk: null }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { events, acs: () => acs, setAcs: (value: Partial<PlatformAcsSettings>) => { acs = { ...acs, ...value } },
    failLoad: (value: boolean) => { failLoad = value }, failSave: (value: boolean) => { failSave = value },
    failRadiusSave: (value: boolean) => { failRadiusSave = value },
    deferSave: () => {
      let resolve: () => void = () => undefined
      const promise = new Promise<void>(done => { resolve = done })
      pending = { promise, resolve }
      return resolve
    } }
}
