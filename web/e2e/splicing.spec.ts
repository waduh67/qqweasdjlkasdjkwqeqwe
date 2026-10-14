import { expect, test, type Page } from '@playwright/test'
import type { ConnectionPointRequest, FiberConnectionPointView, FiberConnectionView } from '../src/api/network'
import { spliceCable, spliceFixture } from '../src/test/spliceFixture'
import { spliceOptions } from '../src/utils/spliceOptions'
import { installOltInventoryRoutes } from './oltInventoryFixtures'

async function setup(page: Page, jointBox = false, manage = true) {
  const data = spliceFixture()
  if (jointBox) { data.closureKind = 'JOINT_BOX'; data.closureId = 'jb-qa'; data.closureCode = 'JB-CRB-001'; data.points = []; data.cables.push(spliceCable('out')) }
  await installOltInventoryRoutes(page, false, false, {}, ['network.splice.view', 'network.jointbox.view', ...(manage ? ['network.splice.manage'] : [])])
  const node = { id: data.closureId, code: data.closureCode, name: jointBox ? 'Joint box Cirebon 001' : 'ODP Cirebon 001', address: 'Cirebon',
    location: { longitude: 108.55, latitude: -6.7 }, odcId: 'odc-qa', odcName: 'ODC Cirebon 001', splitterRatio: '1:16', splitterCount: 1,
    splitterLegs: 16, capacity: jointBox ? 48 : 16, trayCount: 4, spliceCount: 0, status: 'ACTIVE', installedOn: null, mounting: null, notes: null }
  const creates: Record<string, unknown>[] = [], updates: Record<string, unknown>[] = [], deletes: string[] = [], errors: string[] = []
  let failSave = false
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/odps' || path === '/api/joint-boxes') return json({ content: [node], totalElements: 1, totalPages: 1, page: 0, size: 100 })
    if (path === `/api/odps/${data.closureId}` || path === `/api/joint-boxes/${data.closureId}`) return json(node)
    if (path.endsWith('/ports') && path.startsWith('/api/odps/')) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
    if (path === '/api/fiber-connections/workbench') return json(data)
    if (path === '/api/fiber-connections' && method === 'POST') {
      const body = route.request().postDataJSON() as { a: ConnectionPointRequest; b: ConnectionPointRequest; method: FiberConnectionView['method']; lossDb: number | null; note: string | null }
      creates.push(body)
      if (failSave) { failSave = false; return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ detail: 'Titik tujuan sudah terpakai.' }) }) }
      const toPoint = (point: ConnectionPointRequest): FiberConnectionPointView => {
        const found = spliceOptions(data).find(option => point.kind === 'CORE' ? option.point.coreId === point.coreId
          : option.point.kind === point.kind && option.point.nodeId === point.nodeId && option.point.portNumber === point.portNumber)!
        return { kind: point.kind, kindLabel: point.kind, label: found.fullLabel, coreId: point.coreId ?? null, cableId: found.cableId,
          cableCode: found.cableCode, coreNumber: point.kind === 'CORE' ? found.number : null, colorHex: null,
          nodeId: point.nodeId ?? null, portNumber: point.portNumber ?? null, portSide: point.portSide ?? null }
      }
      const row: FiberConnectionView = { id: 'connection-qa', closureKind: data.closureKind, closureId: data.closureId,
        a: toPoint(body.a), b: toPoint(body.b), method: body.method, methodLabel: 'Fusion', lossDb: body.lossDb, note: body.note,
        workOrderId: null, workOrderCode: null, splicedById: 'operator-qa', splicedByName: 'Operator QA', splicedAt: '2026-10-14T12:00:00Z' }
      data.connections.push(row); data.spliceCount++
      for (const point of [body.a, body.b]) {
        if (point.kind === 'CORE') data.cables.flatMap(cable => cable.cores).find(core => core.core.id === point.coreId)!.connectionId = row.id
        else data.points.find(candidate => candidate.kind === point.kind && candidate.nodeId === point.nodeId && candidate.portNumber === point.portNumber)!.connectionId = row.id
      }
      return json(row)
    }
    if (path === '/api/fiber-connections/connection-qa' && method === 'PUT') {
      const body = route.request().postDataJSON(); updates.push(body); Object.assign(data.connections[0], body); return json(data.connections[0])
    }
    if (path === '/api/fiber-connections/connection-qa' && method === 'DELETE') {
      deletes.push(path); data.connections = []; data.spliceCount = 0; return route.fulfill({ status: 204 })
    }
    return route.fallback()
  })
  await page.goto('/inventory')
  await page.getByRole('tab', { name: jointBox ? 'Joint box' : 'ODP', exact: true }).click()
  await page.getByText(node.name, { exact: true }).click()
  const manager = page.getByRole('region', { name: 'Sambungan serat', exact: true })
  await manager.scrollIntoViewIfNeeded()
  await expect(manager.getByRole('table', { name: 'Daftar sambungan' })).toBeVisible()
  return { data, manager, creates, updates, deletes, errors, failNextSave: () => { failSave = true } }
}
async function pick(page: Page, label: string, option: string) {
  await page.getByRole('combobox', { name: label, exact: true }).click()
  await page.getByRole('option', { name: option, exact: true }).click()
}
async function inputPair(page: Page) {
  await page.getByRole('button', { name: 'Tambah sambungan', exact: true }).click()
  await pick(page, 'Core sumber', 'Core 1 · Biru')
  await expect(page.getByRole('combobox', { name: 'Port tujuan' })).toHaveValue('Input')
}

for (const width of [375, 1440]) {
  test(`ODP form, review, save and retained parent at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page), document = await page.evaluateHandle(() => window.document)
    await state.manager.screenshot({ path: info.outputPath('connection-list.png') })
    await page.getByRole('button', { name: 'Tambah sambungan', exact: true }).click()
    await page.getByRole('dialog', { name: 'Tambah sambungan', exact: true }).getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(state.manager).toBeVisible()
    await inputPair(page)
    const target = page.getByRole('combobox', { name: 'Port tujuan' })
    await page.locator('.combobox').filter({ has: target }).getByRole('button', { name: 'Hapus pilihan' }).click()
    await expect(target).toHaveValue('')
    await page.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
    expect(state.creates).toHaveLength(0)
    await pick(page, 'Port tujuan', 'Input')
    const dialog = page.getByRole('dialog', { name: 'Tambah sambungan', exact: true })
    await page.getByRole('combobox', { name: 'Port tujuan' }).click()
    await expect(page.getByRole('listbox').getByRole('option')).toHaveCount(1)
    await page.getByRole('option', { name: 'Input', exact: true }).click()
    await dialog.screenshot({ path: info.outputPath('connection-form.png') })
    expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
    await page.getByRole('button', { name: 'Berikutnya: Detail', exact: true }).click()
    await page.getByRole('textbox', { name: 'Redaman (dB)' }).fill('0.08')
    await page.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await expect(dialog.getByText('DIST-JB-ODP · Core 1 · Biru', { exact: true })).toBeVisible()
    await expect(dialog.getByText('SPL-1 · 1:16 · Input', { exact: true })).toBeVisible()
    expect(state.creates).toHaveLength(0)
    await dialog.screenshot({ path: info.outputPath('connection-review.png') })
    await page.getByRole('button', { name: 'Buat sambungan', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    await expect(state.manager.getByRole('cell', { name: 'DIST-JB-ODP · Core 1', exact: true })).toBeVisible()
    expect(state.creates).toHaveLength(1); expect(state.creates[0].lossDb).toBe(0.08)
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    const cells = state.manager.getByRole('table', { name: 'Daftar sambungan' }).locator('tbody td:not(:first-child)')
    expect(await cells.evaluateAll(elements => elements.every(el => getComputedStyle(el).whiteSpace === 'nowrap' && !el.querySelector('.card,.badge')))).toBe(true)
    expect(state.errors).toEqual([])
  })
}

test('joint box hides its source cable from destination choices', async ({ page }) => {
  const state = await setup(page, true)
  await page.getByRole('button', { name: 'Tambah sambungan', exact: true }).click()
  await pick(page, 'Kabel sumber', 'DIST-JB-ODP'); await pick(page, 'Core sumber', 'Core 7 · Merah')
  await page.getByRole('combobox', { name: 'Kabel tujuan' }).click()
  await expect(page.getByRole('option', { name: 'DIST-JB-ODP', exact: true })).toHaveCount(0)
  await page.getByRole('option', { name: 'DIST-ODP-LANJUTAN', exact: true }).click()
  await pick(page, 'Core tujuan', 'Core 7 · Merah')
  await page.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
  await page.getByRole('button', { name: 'Buat sambungan', exact: true }).click()
  await expect(state.manager.getByRole('cell', { name: 'DIST-ODP-LANJUTAN · Core 7', exact: true })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('failed save retains the draft; editing and removing stay in the command bar', async ({ page }) => {
  const state = await setup(page)
  await inputPair(page); await page.getByRole('tab', { name: 'Tinjau + buat', exact: true }).click()
  state.failNextSave(); await page.getByRole('button', { name: 'Buat sambungan', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Tambah sambungan', exact: true })).toBeVisible()
  await expect(page.getByText('Titik tujuan sudah terpakai.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Buat sambungan', exact: true }).click()
  await state.manager.getByRole('checkbox').check()
  await state.manager.getByRole('button', { name: 'Ubah', exact: true }).click()
  await page.getByRole('textbox', { name: 'Redaman (dB)' }).fill('0.12')
  await page.getByRole('button', { name: 'Tinjau + simpan', exact: true }).click()
  await page.getByRole('dialog', { name: 'Ubah sambungan', exact: true }).getByRole('button', { name: 'Simpan', exact: true }).click()
  await expect(state.manager.getByRole('cell', { name: '0.12 dB', exact: true })).toBeVisible()
  expect(state.updates).toHaveLength(1)
  await state.manager.getByRole('checkbox').check()
  await state.manager.getByRole('button', { name: 'Lepas', exact: true }).click()
  await page.getByRole('dialog', { name: 'Lepas sambungan', exact: true }).getByRole('button', { name: 'Lepas', exact: true }).click()
  await expect(state.manager.getByText('Belum ada sambungan.', { exact: true })).toBeVisible()
  expect(state.deletes).toHaveLength(1); expect(state.errors).toEqual([])
})

test('read-only users see the list without creation or mutation controls', async ({ page }) => {
  const state = await setup(page, false, false)
  await expect(state.manager.getByRole('button', { name: 'Tambah sambungan', exact: true })).toHaveCount(0)
  await state.manager.getByRole('tab', { name: 'Kabel di lokasi' }).click()
  await expect(state.manager.getByRole('cell', { name: 'DIST-JB-ODP', exact: true })).toBeVisible()
  await expect(state.manager.getByRole('checkbox')).toHaveCount(0)
  expect(state.errors).toEqual([])
})
