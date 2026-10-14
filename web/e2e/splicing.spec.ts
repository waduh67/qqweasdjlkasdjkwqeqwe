import { expect, test, type Page } from '@playwright/test'
import type { ConnectionPointRequest, FiberConnectionPointView, FiberConnectionView } from '../src/api/network'
import { spliceCable, spliceFixture } from '../src/test/spliceFixture'
import { spliceOptions } from '../src/utils/spliceOptions'
import { installOltInventoryRoutes } from './oltInventoryFixtures'

async function setup(page: Page, jointBox = false, manage = true, longLabels = false) {
  const data = spliceFixture()
  if (jointBox) { data.closureKind = 'JOINT_BOX'; data.closureId = 'jb-qa'; data.closureCode = 'JB-CRB-001'; data.points = []; data.cables.push(spliceCable('out')) }
  if (longLabels) {
    data.cables[0].code = 'DIST-JB-CRB-001-ODP-CRB-001'
    data.cables[1].code = 'DIST-ODC-CRB-001-JB-CRB-001'
    data.cables.forEach(cable => { cable.cores[0].connectionId = 'already-connected' })
  }
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
  await expect(manager.getByRole(manage ? 'region' : 'table', { name: manage ? 'Meja sambung' : 'Daftar sambungan' })).toBeVisible()
  return { data, manager, creates, updates, deletes, errors, failNextSave: () => { failSave = true } }
}
async function inputPair(page: Page) {
  await page.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru', exact: true }).click()
  const target = page.getByRole('region', { name: 'Titik tujuan', exact: true })
  await expect(target.getByRole('button')).toHaveCount(1)
  await target.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input', exact: true }).click()
}

for (const width of [375, 1440]) {
  test(`ODP interactive points, optional details and retained selection at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page), document = await page.evaluateHandle(() => window.document)
    await inputPair(page)
    const board = state.manager.getByRole('region', { name: 'Meja sambung', exact: true })
    await expect(page.getByRole('dialog', { name: 'Tambah sambungan', exact: true })).toHaveCount(0)
    await board.screenshot({ path: info.outputPath('interactive-pair.png') })
    await state.manager.screenshot({ path: info.outputPath('single-view.png') })
    expect(await board.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
    const target = page.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input', exact: true })
    await target.click()
    await expect(board.getByRole('button', { name: 'Sambungkan', exact: true })).toBeDisabled()
    expect(state.creates).toHaveLength(0)
    await target.click()
    await expect(state.manager.getByRole('tab')).toHaveCount(0)
    await state.manager.getByRole('textbox', { name: 'Cari kabel', exact: true }).fill('tidak-ada')
    await expect(state.manager.getByRole('table', { name: 'Kabel di lokasi', exact: true }).getByRole('cell', { name: 'DIST-JB-ODP', exact: true })).toHaveCount(0)
    await expect(state.manager.getByRole('textbox', { name: 'Cari sambungan', exact: true })).toHaveValue('')
    await state.manager.getByRole('textbox', { name: 'Cari kabel', exact: true }).clear()
    await expect(target).toHaveAttribute('aria-pressed', 'true')
    await board.getByRole('button', { name: 'Detail sambungan', exact: true }).click()
    await board.getByRole('textbox', { name: 'Redaman (dB)' }).fill('0.08')
    await board.getByRole('button', { name: 'Sambungkan', exact: true }).click()
    await expect(board.getByRole('button', { name: 'Sambungkan', exact: true })).toBeDisabled()
    await expect(state.manager.getByRole('cell', { name: 'DIST-JB-ODP · Core 1', exact: true })).toBeVisible()
    expect(state.creates).toHaveLength(1); expect(state.creates[0].lossDb).toBe(0.08)
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    const cells = state.manager.getByRole('table', { name: 'Daftar sambungan' }).locator('tbody td:not(:first-child)')
    expect(await cells.evaluateAll(elements => elements.every(el => getComputedStyle(el).whiteSpace === 'nowrap' && !el.querySelector('.card,.badge')))).toBe(true)
    expect(state.errors).toEqual([])
  })
}

test('joint box excludes its source cable and supports keyboard connection', async ({ page }, info) => {
  const state = await setup(page, true)
  const source = page.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 7 · Merah', exact: true })
  await source.focus(); await source.press('Enter')
  const target = page.getByRole('region', { name: 'Titik tujuan', exact: true })
  await expect(target.getByRole('button', { name: /DIST-JB-ODP/ })).toHaveCount(0)
  await target.getByRole('button', { name: 'Pilih tujuan DIST-ODP-LANJUTAN · Core 7 · Merah', exact: true }).press('Space')
  await state.manager.getByRole('region', { name: 'Meja sambung', exact: true }).screenshot({ path: info.outputPath('joint-box-pair.png') })
  await page.getByRole('button', { name: 'Sambungkan', exact: true }).click()
  await expect(state.manager.getByRole('cell', { name: 'DIST-ODP-LANJUTAN · Core 7', exact: true })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('failed save retains points; editing and disconnect remain available', async ({ page }) => {
  const state = await setup(page)
  await inputPair(page)
  state.failNextSave(); await page.getByRole('button', { name: 'Sambungkan', exact: true }).click()
  await expect(state.manager.getByRole('alert')).toHaveText('Titik tujuan sudah terpakai.')
  await expect(page.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Sambungkan', exact: true }).click()
  await state.manager.getByRole('table', { name: 'Daftar sambungan', exact: true }).getByRole('checkbox').check()
  await state.manager.getByRole('button', { name: 'Ubah', exact: true }).click()
  await page.getByRole('textbox', { name: 'Redaman (dB)' }).fill('0.12')
  await page.getByRole('button', { name: 'Tinjau + simpan', exact: true }).click()
  await page.getByRole('dialog', { name: 'Ubah sambungan', exact: true }).getByRole('button', { name: 'Simpan', exact: true }).click()
  await expect(state.manager.getByRole('cell', { name: '0.12 dB', exact: true })).toBeVisible()
  expect(state.updates).toHaveLength(1)
  await state.manager.getByRole('table', { name: 'Daftar sambungan', exact: true }).getByRole('checkbox').check()
  await state.manager.getByRole('table', { name: 'Kabel di lokasi', exact: true }).getByRole('checkbox').check()
  await expect(state.manager.getByRole('table', { name: 'Daftar sambungan', exact: true }).getByRole('checkbox')).toBeChecked()
  await expect(state.manager.getByRole('button', { name: 'Hapus catatan', exact: true })).toBeDisabled()
  await state.manager.getByRole('button', { name: 'Lepas', exact: true }).click()
  await page.getByRole('dialog', { name: 'Lepas sambungan', exact: true }).getByRole('button', { name: 'Lepas', exact: true }).click()
  await expect(state.manager.getByText('Belum ada sambungan.', { exact: true })).toBeVisible()
  expect(state.deletes).toHaveLength(1); expect(state.errors).toEqual([])
})

test('read-only users see the list without creation or mutation controls', async ({ page }) => {
  const state = await setup(page, false, false)
  await expect(state.manager.getByRole('button', { name: 'Sambungkan', exact: true })).toHaveCount(0)
  await expect(state.manager.getByRole('tab')).toHaveCount(0)
  await expect(state.manager.getByRole('cell', { name: 'DIST-JB-ODP', exact: true })).toBeVisible()
  await expect(state.manager.getByRole('checkbox')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

for (const panelWidth of [385, 620]) {
  test(`joint-box content stays inside a ${panelWidth}px panel on desktop`, async ({ page }, info) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    const state = await setup(page, true, true, true)
    const drawer = page.locator('.azure-blade.blade-detail')
    await drawer.evaluate((node, width) => {
      const style = (node as HTMLElement).style
      style.setProperty('width', `${width}px`, 'important')
      style.setProperty('max-width', `${width}px`, 'important')
      style.setProperty('min-width', '0', 'important')
    }, panelWidth)
    const board = state.manager.getByRole('region', { name: 'Meja sambung', exact: true })
    const source = board.getByRole('region', { name: 'Titik sumber', exact: true })
    const target = board.getByRole('region', { name: 'Titik tujuan', exact: true })
    await source.getByRole('button', { name: `Pilih sumber ${state.data.cables[0].code} · Core 7 · Merah`, exact: true }).click()
    await target.getByRole('button', { name: `Pilih tujuan ${state.data.cables[1].code} · Core 7 · Merah`, exact: true }).click()
    const sourceBox = (await source.boundingBox())!, targetBox = (await target.boundingBox())!
    expect(targetBox.y).toBeGreaterThanOrEqual(sourceBox.y + sourceBox.height)
    expect(Math.abs(sourceBox.x - targetBox.x)).toBeLessThan(2)
    const metrics = await drawer.locator('.azure-blade-body').evaluate(body => ({
      width: body.clientWidth, scrollWidth: body.scrollWidth,
      children: [...body.querySelectorAll<HTMLElement>('.asset-detail-panel, .splice-manager, .splice-interactive, .splice-side, .splice-pair-preview')].map(node => ({ width: node.clientWidth, scrollWidth: node.scrollWidth })),
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.width + 1)
    for (const child of metrics.children) expect(child.scrollWidth).toBeLessThanOrEqual(child.width + 1)
    await board.screenshot({ path: info.outputPath(`contained-panel-${panelWidth}.png`) })
    await expect(state.manager.getByRole('table', { name: 'Daftar sambungan', exact: true })).toBeVisible()
    await expect(state.manager.getByRole('table', { name: 'Kabel di lokasi', exact: true })).toBeVisible()
    await expect(state.manager.getByRole('tab')).toHaveCount(0)
    const table = state.manager.getByRole('region', { name: 'Tabel kabel', exact: true })
    await table.focus(); await table.press('ArrowRight')
    await expect(table).toBeFocused()
    expect(state.errors).toEqual([])
  })
}
