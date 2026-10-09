import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import type { TokenResponse, Role, User } from '../../src/api/types'
import type { PlatformTenant } from '../../src/api/tenant'
import { pageOf, record, text, uuid } from '../../src/api/warehouse/codec'
import { location, sku } from '../../src/api/warehouse/models'
import { referencePosition, referenceStock, referenceWorkOrder, workType } from '../../src/api/warehouse/reference'
import { identity, login } from './helpers'

async function capture(page: Page, info: TestInfo, name: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' })
}

test('technician follows instructions, uploads named evidence and completes with own material', async ({ page }, info) => {
  test.setTimeout(240_000)
  const root = await page.request.post('/api/auth/login', { data: { email: 'root@ftth.local', password: 'rootadmin123' } })
  expect(root.ok()).toBeTruthy()
  const rootSession: TokenResponse = await root.json()
  const owner = identity('Owner')
  const onboard = await page.request.post('/api/platform/tenants', { headers: { Authorization: 'Bearer ' + rootSession.accessToken }, data: { slug: 'field-' + randomUUID().slice(0, 8), name: 'Teknisi QA', adminName: owner.name, adminEmail: owner.email, adminPassword: owner.password } })
  expect(onboard.status()).toBe(201)
  const tenant: PlatformTenant = await onboard.json()
  const ownerDetail = await page.request.get('/api/platform/tenants/' + tenant.id, { headers: { Authorization: 'Bearer ' + rootSession.accessToken } })
  expect(ownerDetail.ok()).toBeTruthy()
  const provisioned: PlatformTenant = await ownerDetail.json()
  expect(provisioned.owner?.email).toBe(owner.email)
  const authenticate = async (account: { email: string; password: string }) => {
    const response = await page.request.post('/api/auth/login', { data: account })
    expect(response.ok()).toBeTruthy()
    const session: TokenResponse = await response.json()
    return { Authorization: 'Bearer ' + session.accessToken }
  }
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
  const roles: Role[] = await rolesResponse.json()
  expect(roles.map(role => role.name)).toEqual(expect.arrayContaining(['Admin', 'Manager', 'Teknisi NE', 'Teknisi FO']))
  const area = record(await command('/api/areas', { code: 'FIELD', name: 'Area teknisi' }))
  const areaId = uuid(area.id)
  const warehouse = pageOf(location)(await get('/api/v2/warehouse/locations')).items.find(row => row.kind === 'WAREHOUSE')
  if (!warehouse) throw new Error('Default warehouse missing')
  const technician = identity('Teknisi'), other = identity('TeknisiLain')
  const members: { account: typeof technician; user: User; headers: { Authorization: string } }[] = []
  for (const [index, account] of [technician, other].entries()) {
    const role = roles.find(row => row.name === (index === 0 ? 'Teknisi NE' : 'Teknisi FO'))
    if (!role) throw new Error('Default technician role missing')
    const response = await page.request.post('/api/users', { headers, data: { ...account, roleIds: [role.id] } })
    expect(response.status()).toBe(201)
    const user: User = await response.json()
    await command('/api/users/' + user.id + '/access', { roleIds: [role.id], areaIds: [areaId] }, headers, 'PUT')
    await command('/api/v1/warehouse/settings/scopes/' + user.id + '/' + warehouse.id, { expectedRevision: 0, active: true }, headers, 'PUT')
    members.push({ account, user, headers: await authenticate(account) })
  }
  const assigned = members[0]
  if (!assigned) throw new Error('Assigned technician missing')
  await command('/api/v2/warehouse/workflow/drain', { expectedEpoch: 0 })
  const review = record(await get('/api/v2/warehouse/workflow/review'))
  await command('/api/v2/warehouse/workflow/activate', { expectedEpoch: 1, reviewHash: text(review.reviewHash), reason: 'Real technician QA' })
  const material = sku(await command('/api/v2/warehouse/skus', { code: 'CONNECTOR', name: 'Konektor teknisi', tracking: 'BULK', baseUnit: 'EA' }))
  await command('/api/v2/warehouse/receipts', { warehouseId: warehouse.id, lines: [{ skuId: material.id, quantityBase: '18' }] })
  for (const member of members) {
    let request = record(await command('/api/v2/warehouse/requests', { kind: 'RESTOCK', reason: 'Persediaan lapangan', lines: [{ skuId: material.id, baseUnit: 'EA', requestedBase: '9' }] }, member.headers))
    const requestId = uuid(request.id)
    const lines = request.lines
    if (!Array.isArray(lines) || lines.length !== 1) throw new Error('Request line missing')
    const lineId = uuid(record(lines[0]).id)
    request = record(await command('/api/v2/warehouse/requests/' + requestId + '/review', { expectedRevision: 0, lines: [{ lineId, approvedBase: '9' }] }))
    if (request.state === 'MANAGER_REVIEW') request = record(await command('/api/v2/warehouse/requests/' + requestId + '/decision', { expectedRevision: request.revision, approved: true }))
    const stock = referenceStock(await get('/api/v2/warehouse/stock/' + material.id))
    const position = stock.positions.find(row => row.holderKind === 'WAREHOUSE' && BigInt(row.quantityBase) >= 9n)
    if (!position) throw new Error('Warehouse stock missing')
    await command('/api/v2/warehouse/requests/' + requestId + '/handovers', { expectedRevision: request.revision, lineId, warehouseId: warehouse.id, lines: [{ stockIdentityId: position.stockIdentityId, quantityBase: '9' }] })
  }
  const types = await get('/api/v2/work-orders/types')
  if (!Array.isArray(types)) throw new Error('Work types missing')
  const type = types.map(workType).find(row => row.name === 'Pasang Baru')
  if (!type) throw new Error('Default work type missing')
  const work = referenceWorkOrder(await command('/api/v2/work-orders', { typeId: type.id, title: 'Pasang layanan pelanggan', technicianId: assigned.user.id, areaId, description: 'Hubungi pelanggan, pasang konektor, cek sinyal, lalu dokumentasikan hasil.' }))
  expect((await page.request.get('/api/v2/warehouse/stock/' + material.id, { headers: assigned.headers })).status()).toBe(403)
  const own = pageOf(referencePosition)(await get('/api/v2/warehouse/my-materials', assigned.headers))
  expect(own.items.every(row => row.holderId === assigned.user.id)).toBeTruthy()
  expect(own.items[0]?.quantityBase).toBe('9')
  await login(page, technician)
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => localStorage.setItem('ftth.theme', value), theme)
    await page.goto('/my-materials')
    await expect(page.getByRole('gridcell', { name: material.name + ' · ' + material.code, exact: true })).toBeVisible()
    await expect(page.getByRole('gridcell', { name: '9 unit', exact: true })).toBeInViewport()
    await capture(page, info, theme + '-materials')
    await page.goto('/my-work-orders')
    await page.getByRole('link', { name: work.code + ' · ' + work.title, exact: true }).waitFor()
    expect(await page.getByRole('link', { name: work.code + ' · ' + work.title, exact: true }).evaluate(element => {
      const cell = element.closest('[role="gridcell"]')
      if (!cell) return false
      const bounds = cell.getBoundingClientRect()
      return bounds.right <= innerWidth && Array.from(element.getClientRects()).every(rect => rect.left >= bounds.left && rect.right <= bounds.right + 1)
    })).toBeTruthy()
    await capture(page, info, theme + '-task-list')
    await page.getByRole('link', { name: work.code + ' · ' + work.title, exact: true }).click()
    await expect(page.getByRole('heading', { name: '1. Instruksi pekerjaan' })).toBeVisible()
    await capture(page, info, theme + '-instructions')
    await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).scrollIntoViewIfNeeded()
    await capture(page, info, theme + '-completion-scroll-end')
  }
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Lengkapi foto wajib')
  for (const slot of type.photoSlots) {
    await page.getByLabel('Unggah foto ' + slot, { exact: true }).setInputFiles(fileURLToPath(new URL('./fixtures/inspection.png', import.meta.url)))
    await expect(page.getByRole('dialog')).toBeVisible()
    await capture(page, info, 'photo-review-' + type.photoSlots.indexOf(slot))
    await page.getByRole('button', { name: 'Unggah foto', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByLabel('Ganti foto ' + slot, { exact: true })).toBeVisible()
  }
  await page.getByRole('button', { name: 'Lihat foto ' + type.photoSlots[0], exact: true }).click()
  await expect(page.getByRole('img', { name: 'Bukti ' + type.photoSlots[0], exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Tambah material', exact: true }).click()
  await page.getByRole('combobox', { name: 'Barang 1', exact: true }).click()
  const option = page.getByRole('option').filter({ hasText: material.name })
  await expect(option).toBeEnabled()
  await capture(page, info, 'own-material-picker')
  await option.click()
  await page.getByLabel(/Terpakai/).fill('10')
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('melebihi stok')
  await page.getByLabel(/Terpakai/).fill('3')
  await page.getByLabel('Catatan hasil pekerjaan').fill('Sinyal normal dan pelanggan terhubung.')
  await page.context().setOffline(true)
  await expect(page.getByRole('status').filter({ hasText: 'Offline.' })).toBeVisible()
  await expect(page.getByLabel('Catatan hasil pekerjaan')).toHaveValue('Sinyal normal dan pelanggan terhubung.')
  await expect(page.getByLabel(/Terpakai/)).toHaveValue('3')
  await expect(page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true })).toBeDisabled()
  await capture(page, info, 'offline-draft')
  await page.context().setOffline(false)
  await expect(page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true })).toBeEnabled()
  const progressed = referenceWorkOrder(await command('/api/v2/work-orders/' + work.id + '/progress', { expectedRevision: work.revision + type.photoSlots.length, state: 'PENDING', notes: 'Instruksi diperbarui ketika draf terbuka.' }, assigned.headers))
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Tugas atau penugasan sudah berubah')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByLabel('Catatan hasil pekerjaan')).toHaveValue('Sinyal normal dan pelanggan terhubung.')
  await expect(page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true })).toBeEnabled()
  expect(progressed.revision).toBe(work.revision + type.photoSlots.length + 1)
  await page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Konektor teknisi')
  await capture(page, info, 'completion-review')
  const attempts: { key: string | undefined; body: string | null }[] = []
  await page.route('**/api/v2/work-orders/' + work.id + '/complete', async route => {
    attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
    if (attempts.length === 1) {
      // Commit through the real API, then lose its response to exercise an ambiguous retry.
      const response = await route.fetch()
      expect(response.ok()).toBeTruthy()
      await route.abort('connectionreset')
    } else await route.continue()
  })
  await page.getByRole('button', { name: 'Kirim hasil dan selesai', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Penyimpanan belum terkonfirmasi')
  await expect(page.getByRole('button', { name: 'Batal', exact: true })).toBeDisabled()
  await capture(page, info, 'completion-response-lost')
  await page.getByRole('button', { name: 'Coba transaksi yang sama', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Hasil pekerjaan tersimpan', exact: true })).toBeVisible()
  await page.getByRole('heading', { name: 'Hasil pekerjaan tersimpan', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.getByRole('button', { name: 'Periksa dan selesaikan', exact: true })).toHaveCount(0)
  await capture(page, info, 'completed-history')
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.key).toBeTruthy()
  expect(attempts[1]).toEqual(attempts[0])
  const remaining = pageOf(referencePosition)(await get('/api/v2/warehouse/my-materials', assigned.headers))
  expect(remaining.items[0]?.quantityBase).toBe('6')
  const second = members[1]
  if (!second) throw new Error('Other technician missing')
  const unaffected = pageOf(referencePosition)(await get('/api/v2/warehouse/my-materials', second.headers))
  expect(unaffected.items[0]?.quantityBase).toBe('9')
})
