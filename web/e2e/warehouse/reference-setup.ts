import { expect, type Page, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { TokenResponse, Role, User } from '../../src/api/types'
import { pageOf, record, text, uuid } from '../../src/api/warehouse/codec'
import { location } from '../../src/api/warehouse/models'
import { workflow } from '../../src/api/warehouse/reference'
import { identity } from './helpers'

export async function captureReference(page: Page, info: TestInfo, name: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ' fits viewport').toBeTruthy()
  await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' })
}

export async function referenceTenant(page: Page, prefix: string) {
  const authenticate = async (account: { readonly email: string; readonly password: string }) => {
    const response = await page.request.post('/api/auth/login', { data: account })
    expect(response.ok()).toBeTruthy()
    const session: TokenResponse = await response.json()
    return { Authorization: 'Bearer ' + session.accessToken }
  }
  const root = await authenticate({ email: 'root@ftth.local', password: 'rootadmin123' })
  const owner = identity('Owner')
  const onboard = await page.request.post('/api/platform/tenants', { headers: root, data: {
    slug: prefix + '-' + randomUUID().slice(0, 8), name: prefix + ' QA',
    adminName: owner.name, adminEmail: owner.email, adminPassword: owner.password,
  } })
  expect(onboard.status()).toBe(201)
  const tenantId = uuid(record(await onboard.json()).id)
  const headers = await authenticate(owner)
  const get = async (path: string, authority = headers): Promise<unknown> => {
    const response = await page.request.get(path, { headers: authority })
    expect(response.ok(), path + ': ' + response.status()).toBeTruthy()
    return response.json()
  }
  const command = async (path: string, data: unknown, authority = headers, method: 'POST' | 'PUT' = 'POST'): Promise<unknown> => {
    const response = await page.request.fetch(path, { method, headers: { ...authority, 'Idempotency-Key': randomUUID() }, data })
    expect(response.ok(), path + ': ' + response.status()).toBeTruthy()
    return response.status() === 204 ? null : response.json()
  }
  const rolesResponse = await page.request.get('/api/roles', { headers })
  expect(rolesResponse.ok()).toBeTruthy()
  const roles: Role[] = await rolesResponse.json()
  expect(roles.map(role => role.name)).toEqual(expect.arrayContaining(['Admin', 'Manager', 'Teknisi NE', 'Teknisi FO']))
  const areaId = uuid(record(await command('/api/areas', { code: 'QA', name: 'Area QA' })).id)
  const provisioned = pageOf(location)(await get('/api/v2/warehouse/locations')).items.find(row => row.kind === 'WAREHOUSE')
  if (!provisioned) throw new Error('Default warehouse missing')
  const warehouse = location(await command('/api/v2/warehouse/locations/' + provisioned.id, {
    code: provisioned.code, name: provisioned.name, kind: 'WAREHOUSE', areaId, issueEligible: true,
    expectedRevision: provisioned.revision,
  }, headers, 'PUT'))
  const createMember = async (roleName: string) => {
    const role = roles.find(row => row.name === roleName)
    if (!role) throw new Error('Default role missing: ' + roleName)
    const account = identity(roleName.replaceAll(' ', ''))
    const response = await page.request.post('/api/users', { headers, data: { ...account, roleIds: [role.id] } })
    expect(response.status()).toBe(201)
    const user: User = await response.json()
    await command('/api/users/' + user.id + '/access', { roleIds: [role.id], areaIds: [areaId] }, headers, 'PUT')
    await command('/api/v1/warehouse/settings/scopes/' + user.id + '/' + warehouse.id, { expectedRevision: 0, active: true }, headers, 'PUT')
    return { account, user, headers: await authenticate(account) }
  }
  const activate = async () => {
    const current = workflow(await get('/api/v2/warehouse/workflow'))
    if (current.workflow === 'REFERENCE') return
    await command('/api/v2/warehouse/workflow/drain', { expectedEpoch: current.epoch })
    const review = record(await get('/api/v2/warehouse/workflow/review'))
    await command('/api/v2/warehouse/workflow/activate', { expectedEpoch: current.epoch + 1, reviewHash: text(review.reviewHash), reason: 'Real reference browser QA' })
  }
  return { owner, tenantId, headers, get, command, warehouse, areaId, createMember, activate }
}
