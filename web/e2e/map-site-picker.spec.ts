import { expect, test, type Page } from '@playwright/test'
import { fromGeojsonVt } from '@maplibre/vt-pbf'
import { installOltInventoryRoutes, olt } from './oltInventoryFixtures'

const scopeKey = 'ftth.map.site:tenant-qa:operator-qa'
const sites = [
  { id: 'lab', code: 'POP-LAB', name: 'Laboratorium', location: { longitude: 106.995, latitude: -6.243 }, address: null, areaId: null, oltCount: 1 },
  { id: 'bandung', code: 'POP-BDG', name: 'Bandung', location: { longitude: 107.6, latitude: -6.9 }, address: null, areaId: null, oltCount: 2 },
]
const points = [
  { kind: 'olt', id: olt.id, code: olt.code, longitude: 106.994, latitude: -6.243 },
  { kind: 'odc', id: 'odc-qa', code: 'ODC-QA', longitude: 106.996, latitude: -6.243 },
  { kind: 'joint_box', id: 'jb-qa', code: 'JB-QA', longitude: 106.995, latitude: -6.244 },
  { kind: 'odp', id: 'odp-qa', code: 'ODP-QA', longitude: 106.997, latitude: -6.244 },
]
function world(at: { longitude: number; latitude: number }, z: number) {
  const sin = Math.sin(at.latitude * Math.PI / 180), n = 2 ** z
  return [(at.longitude / 360 + .5) * n, (.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * n]
}
function tile(z: number, x: number, y: number) {
  const layers: Record<string, { features: unknown[] }> = {}
  points.forEach((p, id) => {
    const xy = world(p, z).map((v, axis) => Math.round((v - [x, y][axis]) * 4096))
    if (xy.some(v => v < -32 || v > 4128)) return
    layers[p.kind] ??= { features: [] }
    layers[p.kind].features.push({ id, type: 1, geometry: [xy], tags: { id: p.id, code: p.code, status: 'ACTIVE' } })
  })
  return Buffer.from(fromGeojsonVt(layers as never))
}
async function setup(page: Page, allowSites = true) {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await installOltInventoryRoutes(page, true, false, {}, ['network.cable.create', 'network.odc.view', 'network.joint_box.view', ...(allowSites ? ['network.site.view'] : [])])
  const tiles: string[] = [], errors: string[] = []
  let siteReads = 0
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
      getCurrentPosition: (callback: PositionCallback) => { (window as unknown as { finishGeo: () => void }).finishGeo = () => callback({ coords: { longitude: 100, latitude: 0 } } as GeolocationPosition) },
    } })
  })
  await page.route('**/api/sites?*', route => { siteReads++; return route.fulfill({ json: { content: sites, page: 0, size: 200, totalPages: 1, totalElements: 2 } }) })
  await page.route('**/api/gis/tiles/**', route => {
    const [z, x, y] = new URL(route.request().url()).pathname.match(/tiles\/(\d+)\/(\d+)\/(\d+)/)!.slice(1).map(Number)
    tiles.push(`${z}/${x}/${y}`)
    return route.fulfill({ contentType: 'application/vnd.mapbox-vector-tile', body: tile(z, x, y) })
  })
  await page.route(/https:\/\/mt[0-3]\.google\.com\/vt\?/, route => route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64') }))
  await page.route('**/api/cables/source-ports?*', route => route.fulfill({ json: new URL(route.request().url()).searchParams.get('kind') === 'OLT'
    ? Array.from({ length: 8 }, (_, i) => ({ ponPortId: `pon-${i + 1}`, portNumber: null, label: `PON${i + 1}`, occupied: i === 0, occupiedByCable: i === 0 ? 'FEEDER-01' : null })) : [] }))
  await page.route('**/api/gis/odcs/odc-qa/blast-radius', route => route.fulfill({ json: { odcId: 'odc-qa', odcCode: 'ODC-QA', customerCount: 0, odpCount: 0, energized: true, odps: [], customers: [] } }))
  await page.route('**/api/joint-boxes/jb-qa', route => route.fulfill({ json: { id: 'jb-qa', code: 'JB-QA', name: 'Joint box QA', status: 'ACTIVE', trayCount: 1, capacity: 24, spliceCount: 0, location: points[2] } }))
  return { tiles, errors, siteReads: () => siteReads, at: (location: { longitude: number; latitude: number }, z = 16) => {
    const [x, y] = world(location, z).map(Math.floor)
    return tiles.includes(`${z}/${x}/${y}`)
  } }
}
async function chooseSite(page: Page, label: string) {
  await page.getByRole('combobox', { name: 'Pilih Site / POP' }).click()
  await page.getByRole('option', { name: label }).click()
}

for (const width of [375, 1440]) {
  test(`site search, location priority and selection persistence at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page)
    await page.goto('/map')
    const picker = page.getByRole('combobox', { name: 'Pilih Site / POP' })
    await picker.fill('bdg')
    await expect(page.getByRole('option', { name: 'POP-LAB · Laboratorium' })).toHaveCount(0)
    await page.getByRole('option', { name: 'POP-BDG · Bandung' }).click()
    await expect.poll(() => state.at(sites[1].location)).toBe(true)
    await page.evaluate(() => (window as unknown as { finishGeo: () => void }).finishGeo())
    await expect(picker).toHaveValue('POP-BDG · Bandung')
    await page.getByRole('button', { name: 'Peta gelap', exact: true }).click()
    await expect.poll(() => page.evaluate(key => localStorage.getItem(key), scopeKey)).toBe('bandung')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (width > 640) {
      const toolbar = (await page.locator('.map-toolbar').boundingBox())!, card = (await page.getByRole('region', { name: 'Site / POP' }).boundingBox())!
      expect(card.x).toBeGreaterThan(toolbar.x + toolbar.width)
      const document = await page.evaluateHandle(() => window.document)
      const group = page.getByRole('button', { name: 'Jaringan', exact: true })
      if (await group.getAttribute('aria-expanded') !== 'true') await group.click()
      await page.getByRole('link', { name: 'Aset jaringan', exact: true }).click()
      await page.getByRole('link', { name: 'Peta Jaringan', exact: true }).click()
      await expect(picker).toHaveValue('POP-BDG · Bandung')
      expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    }
    state.tiles.length = 0
    await page.reload()
    await expect(picker).toHaveValue('POP-BDG · Bandung')
    await expect.poll(() => state.at(sites[1].location)).toBe(true)
    await expect(page.getByRole('button', { name: 'Peta gelap', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await page.screenshot({ path: info.outputPath('map-site-picker.png'), animations: 'disabled' })
    expect(state.errors).toEqual([])
  })
}

test('explicit asset focus overrides the saved site', async ({ page }) => {
  const state = await setup(page)
  await page.addInitScript(key => localStorage.setItem(key, 'bandung'), scopeKey)
  await page.goto('/olts/' + olt.id)
  await page.getByRole('button', { name: 'Lihat di peta', exact: true }).click()
  await expect.poll(() => state.at(olt.location, 17)).toBe(true)
  await expect(page.getByRole('combobox', { name: 'Pilih Site / POP' })).toHaveValue('POP-BDG · Bandung')
  expect(state.at(sites[1].location)).toBe(false)
  expect(state.errors).toEqual([])
})

test('does not list sites without site read permission', async ({ page }) => {
  const state = await setup(page, false)
  await page.goto('/map')
  await expect(page.getByRole('button', { name: 'Lokasi saya' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Pilih Site / POP' })).toHaveCount(0)
  expect(state.siteReads()).toBe(0)
})

for (const [source, target] of [[0, 1], [1, 2], [2, 3]]) {
  test(`cable source form from ${points[source].kind} to ${points[target].kind}`, async ({ page }, info) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const state = await setup(page)
    await page.goto('/map')
    await chooseSite(page, 'POP-LAB · Laboratorium')
    await expect.poll(() => state.at(sites[0].location)).toBe(true)
    const canvas = page.locator('.maplibregl-canvas')
    const clickPoint = async (index: number) => {
      const box = (await canvas.boundingBox())!, center = world(sites[0].location, 16), point = world(points[index], 16)
      await canvas.click({ position: { x: box.width / 2 + (point[0] - center[0]) * 512, y: box.height / 2 + (point[1] - center[1]) * 512 } })
    }
    await clickPoint(source)
    await page.getByRole('button', { name: 'Tarik kabel', exact: true }).click()
    await clickPoint(target)
    const panel = page.getByRole('dialog', { name: 'Kabel baru', exact: true })
    await panel.getByRole('tab', { name: 'Sambungan', exact: true }).click()
    if (source === 0) {
      await panel.getByRole('combobox', { name: 'Port PON' }).selectOption('pon-2')
      await expect(panel.getByRole('button', { name: 'PON2', exact: true })).toHaveCount(0)
    } else await expect(panel.getByText(/Setelah kabel disimpan,.*Sambungan serat/)).toBeVisible()
    await page.screenshot({ path: info.outputPath('cable-source.png'), animations: 'disabled' })
    await panel.getByRole('tab', { name: 'Tinjau + buat' }).click()
    await expect(panel.locator('.resource-summary').filter({ hasText: 'Jenis kabel' })).toBeVisible()
    expect(state.errors).toEqual([])
  })
}
