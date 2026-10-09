import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { pageOf } from '../../src/api/warehouse/codec'
import { sku, type WarehouseSku } from '../../src/api/warehouse/models'
import { referencePosition, referenceWorkOrder, workType, type ReferencePosition } from '../../src/api/warehouse/reference'
import { referenceRequest } from '../../src/api/warehouse/referenceRequests'
import { referenceReturn, referenceReturnDetail } from '../../src/api/warehouse/referenceReturns'
import { displayUnit, formatBaseQuantity } from '../../src/api/warehouse/quantity'
import { login } from './helpers'
import { captureReferenceDocument as capture, referenceTenant } from './reference-setup'

async function choose(page: Page, label: string, name: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click()
  await page.getByRole('option', { name, exact: true }).click()
}
async function switchAccount(page: Page, account: { readonly email: string; readonly password: string }) {
  await page.getByRole('button', { name: 'Keluar', exact: true }).click()
  await login(page, account)
}
async function responseAfter(page: Page, path: string, label: string) {
  const pending = page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === 'POST')
  await page.getByRole('button', { name: label, exact: true }).click()
  const response = await pending
  expect(response.ok(), path + ': ' + response.status()).toBeTruthy()
  return referenceReturn(await response.json())
}
const positionLabel = (item: ReferencePosition) => [item.skuName, item.serial ?? 'Tanpa serial', formatBaseQuantity(item.quantityBase, item.baseUnit) + ' ' + displayUnit(item.baseUnit), item.locationName, item.stockIdentityId.slice(-8)].join(' · ')

async function setup(page: Page) {
  const fixture = await referenceTenant(page, 'returns')
  const admin = await fixture.createMember('Admin'), manager = await fixture.createMember('Manager')
  const technician = await fixture.createMember('Teknisi FO'), other = await fixture.createMember('Teknisi NE')
  await fixture.activate()
  const own = async () => pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/my-materials', technician.headers))
  async function issue(material: WarehouseSku, quantityBase: string, serials: readonly { readonly serial: string }[] = []) {
    await fixture.command('/api/v2/warehouse/receipts', { warehouseId: fixture.warehouse.id, lines: [{ skuId: material.id, quantityBase, serials }] }, admin.headers)
    let request = referenceRequest(await fixture.command('/api/v2/warehouse/requests', { kind: 'RESTOCK', reason: 'Persediaan untuk retur', lines: [{ skuId: material.id, baseUnit: material.baseUnit, requestedBase: quantityBase }] }, technician.headers))
    const path = '/api/v2/warehouse/requests/' + request.id, lineId = request.lines[0].id
    request = referenceRequest(await fixture.command(path + '/review', { expectedRevision: request.revision, lines: [{ lineId, approvedBase: quantityBase }] }, admin.headers))
    if (request.state === 'MANAGER_REVIEW') request = referenceRequest(await fixture.command(path + '/decision', { expectedRevision: request.revision, approved: true }, manager.headers))
    const positions = pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/stock/' + material.id + '/positions?availableOnly=true&locationId=' + fixture.warehouse.id, admin.headers))
    await fixture.command(path + '/handovers', { expectedRevision: request.revision, lineId, warehouseId: fixture.warehouse.id, lines: positions.items.map(item => ({ stockIdentityId: item.stockIdentityId, quantityBase: item.quantityBase })) }, admin.headers)
    return (await own()).items.filter(item => item.skuId === material.id)
  }
  return { ...fixture, admin, technician, other, own, issue }
}

for (const theme of ['light', 'dark']) {
  test(theme + ' cable return preserves pending custody, rejection and exactly once receipt after a lost response', async ({ page }, info) => {
    test.setTimeout(300_000)
    await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
    const fixture = await setup(page)
    const material = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', inspectionRequired: false }))
    const positions = await fixture.issue(material, '82501'), original = positions[0]
    if (!original) throw new Error('Missing own cable')
    await login(page, fixture.technician.account)
    await page.goto('/my-materials')
    await page.getByRole('link', { name: 'Ajukan atau pantau retur material', exact: true }).click()
    await expect(page.getByText('Belum ada retur', { exact: true })).toBeVisible()
    await capture(page, info, 'technician-empty-returns')
    let current = original
    for (const received of [false, true]) {
      await page.getByRole('button', { name: 'Retur baru', exact: true }).click()
      await choose(page, 'Material milik saya 1', positionLabel(current))
      await page.getByRole('textbox', { name: 'Jumlah dikembalikan 1 (m)', exact: true }).fill('12,501')
      await choose(page, 'Gudang tujuan retur', (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code)
      await page.getByRole('textbox', { name: 'Alasan retur', exact: true }).fill('Sisa kabel setelah pekerjaan')
      await capture(page, info, 'technician-return-form-' + received)
      await page.getByRole('button', { name: 'Tinjau retur', exact: true }).click()
      await capture(page, info, 'technician-return-review-' + received)
      let view = await responseAfter(page, '/api/v2/warehouse/returns', 'Ajukan retur')
      expect(view.state).toBe('PENDING')
      expect((await fixture.own()).items[0].quantityBase).toBe('82501')
      await expect(page.getByText('Menunggu penerimaan', { exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: 'Terima retur', exact: true })).toHaveCount(0)
      await capture(page, info, 'technician-pending-detail-' + received)
      const path = '/api/v2/warehouse/returns/' + view.id
      expect((await page.request.get(path, { headers: fixture.other.headers })).status()).toBe(404)
      expect((await page.request.get('/api/v2/warehouse/stock/' + material.id, { headers: fixture.technician.headers })).status()).toBe(403)
      await switchAccount(page, fixture.admin.account)
      await page.goto('/warehouse/returns?id=' + view.id)
      await page.getByRole('button', { name: received ? 'Terima retur' : 'Tolak retur', exact: true }).click()
      await page.getByRole('textbox', { name: received ? 'Catatan penerimaan retur' : 'Alasan penolakan retur', exact: true }).fill(received ? 'Barang diterima fisik' : 'Barang belum diserahkan')
      await capture(page, info, 'admin-decision-form-' + received)
      await page.getByRole('button', { name: 'Tinjau keputusan retur', exact: true }).click()
      await capture(page, info, 'admin-decision-review-' + received)
      if (received) {
        const attempts: { readonly key: string | undefined; readonly body: string | null }[] = []
        await page.route('**' + path + '/decision', async route => {
          attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
          if (attempts.length === 1) { const response = await route.fetch(); expect(response.ok()).toBeTruthy(); await route.abort('failed') }
          else await route.continue()
        })
        await page.getByRole('button', { name: 'Terima retur', exact: true }).click()
        await expect(page.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeDisabled()
        await capture(page, info, 'admin-receipt-response-lost')
        view = await responseAfter(page, path + '/decision', 'Coba transaksi yang sama')
        expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0])
        await page.unroute('**' + path + '/decision')
        expect(view.state).toBe('RECEIVED')
        expect(view.movementId).toBeTruthy()
      } else {
        view = await responseAfter(page, path + '/decision', 'Tolak retur')
        expect(view.state).toBe('REJECTED'); expect(view.movementId).toBeNull()
      }
      await expect(page.getByText(received ? 'Diterima gudang' : 'Ditolak', { exact: true })).toBeVisible()
      await capture(page, info, 'admin-final-detail-' + received)
      const detail = referenceReturnDetail(await fixture.get(path, fixture.technician.headers))
      expect(detail.timeline.map(event => event.action)).toEqual(['SUBMIT', 'DECIDE'])
      const stock = (await fixture.own()).items[0]
      if (!stock) throw new Error('Remaining cable missing')
      expect(stock.quantityBase).toBe(received ? '70000' : '82501'); current = stock
      await switchAccount(page, fixture.technician.account)
      await page.goto('/warehouse/returns?id=' + view.id)
      await capture(page, info, 'technician-final-detail-' + received)
      await page.getByRole('button', { name: 'Kembali ke daftar', exact: true }).click()
      await expect(page.getByRole('gridcell', { name: received ? 'Diterima gudang' : 'Ditolak', exact: true })).toBeVisible()
    }
    const stock = pageOf(referencePosition)(await fixture.get('/api/v2/warehouse/stock/' + material.id + '/positions', fixture.admin.headers))
    expect(stock.items.filter(item => item.holderKind === 'WAREHOUSE').reduce((sum, item) => sum + BigInt(item.quantityBase), 0n)).toBe(12501n)
    await page.goto('/my-materials')
    await expect(page.getByRole('gridcell', { name: '70,000 m', exact: true })).toBeVisible()
    await capture(page, info, 'technician-remaining-material')
  })

  test(theme + ' serialized return and physical receipt revalidation after technician consumption', async ({ page }, info) => {
    test.setTimeout(300_000)
    await page.addInitScript(value => localStorage.setItem('ftth.theme', value), theme)
    const fixture = await setup(page)
    const device = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'ONT', name: 'ONT pelanggan', tracking: 'SERIAL', baseUnit: 'EA', inspectionRequired: false }))
    const devices = await fixture.issue(device, '2', [{ serial: 'ONT-RET-1' }, { serial: 'ONT-RET-2' }]), selected = devices[0]
    if (!selected) throw new Error('Own serial missing')
    await login(page, fixture.technician.account)
    await page.goto('/warehouse/returns')
    await page.getByRole('button', { name: 'Retur baru', exact: true }).click()
    await choose(page, 'Material milik saya 1', positionLabel(selected))
    await expect(page.getByRole('textbox', { name: 'Jumlah dikembalikan 1 (unit)', exact: true })).toBeDisabled()
    await choose(page, 'Gudang tujuan retur', (fixture.warehouse.name ?? fixture.warehouse.code) + ' · ' + fixture.warehouse.code)
    await page.getByRole('textbox', { name: 'Alasan retur', exact: true }).fill('Perangkat tidak dipakai')
    await capture(page, info, 'technician-serial-return-form')
    await page.getByRole('button', { name: 'Tinjau retur', exact: true }).click()
    let view = await responseAfter(page, '/api/v2/warehouse/returns', 'Ajukan retur')
    await switchAccount(page, fixture.admin.account)
    await page.goto('/warehouse/returns?id=' + view.id)
    await page.getByRole('button', { name: 'Terima retur', exact: true }).click()
    await page.getByRole('button', { name: 'Tinjau keputusan retur', exact: true }).click()
    view = await responseAfter(page, '/api/v2/warehouse/returns/' + view.id + '/decision', 'Terima retur')
    expect(view.lines[0].serial).toBe(selected.serial)
    expect((await fixture.own()).items.filter(item => item.skuId === device.id)).toHaveLength(1)
    await capture(page, info, 'admin-serial-received')
    const material = sku(await fixture.command('/api/v2/warehouse/skus', { code: 'CONN', name: 'Konektor', tracking: 'BULK', baseUnit: 'EA', inspectionRequired: false }))
    const issued = (await fixture.issue(material, '9'))[0]
    if (!issued) throw new Error('Own connector missing')
    const pending = referenceReturn(await fixture.command('/api/v2/warehouse/returns', { warehouseId: fixture.warehouse.id, skuId: material.id, lines: [{ stockIdentityId: issued.stockIdentityId, quantityBase: '9' }], reason: 'Material menunggu penyerahan' }, fixture.technician.headers))
    const type = workType(await fixture.command('/api/v2/work-orders/types', { name: 'Pemeriksaan konektor', workType: 'PREVENTIVE', materialRequired: true, photoSlots: ['Hasil'] }))
    let work = referenceWorkOrder(await fixture.command('/api/v2/work-orders', { typeId: type.id, title: 'Pakai material sebelum retur diterima', technicianId: fixture.technician.user.id, areaId: fixture.areaId }))
    const photo = await page.request.post('/api/v2/work-orders/' + work.id + '/evidence', { headers: { ...fixture.technician.headers, 'Idempotency-Key': randomUUID() }, multipart: { expectedRevision: String(work.revision), slot: 'Hasil', file: { name: 'inspection.png', mimeType: 'image/png', buffer: await readFile(new URL('./fixtures/inspection.png', import.meta.url)) } } })
    expect(photo.ok()).toBeTruthy(); work = referenceWorkOrder(await photo.json())
    work = referenceWorkOrder(await fixture.command('/api/v2/work-orders/' + work.id + '/complete', { expectedRevision: work.revision, notes: 'Konektor telah terpakai', materials: [{ stockIdentityId: issued.stockIdentityId, quantityBase: '3' }] }, fixture.technician.headers))
    expect(work.state).toBe('COMPLETED')
    await page.goto('/warehouse/returns?id=' + pending.id)
    await page.getByRole('button', { name: 'Terima retur', exact: true }).click()
    await page.getByRole('button', { name: 'Tinjau keputusan retur', exact: true }).click()
    const conflict = page.waitForResponse(response => response.url().endsWith('/returns/' + pending.id + '/decision'))
    await page.getByRole('button', { name: 'Terima retur', exact: true }).click()
    expect((await conflict).status()).toBe(409)
    await expect(page.getByRole('button', { name: 'Muat ulang dokumen', exact: true })).toBeVisible()
    await capture(page, info, 'admin-consumed-stock-conflict')
    await page.getByRole('button', { name: 'Muat ulang dokumen', exact: true }).click()
    await expect(page.getByText('Menunggu penerimaan', { exact: true })).toBeVisible()
    expect(referenceReturnDetail(await fixture.get('/api/v2/warehouse/returns/' + pending.id, fixture.technician.headers)).timeline).toHaveLength(1)
    expect((await fixture.own()).items.find(item => item.skuId === material.id)?.quantityBase).toBe('6')
    await capture(page, info, 'admin-pending-after-conflict')
  })
}
