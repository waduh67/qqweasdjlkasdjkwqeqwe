import { expect, test, type Page } from '@playwright/test'
import { installOltInventoryRoutes, olt } from './oltInventoryFixtures'

async function setup(page: Page, canUpdate = true) {
  await installOltInventoryRoutes(page, true, false, {}, canUpdate ? ['network.olt.update'] : [])
  const ports = [{ id: 'pon-1', oltId: olt.id, label: 'PON1', status: 'ACTIVE', description: null, odcCount: 1 }]
  const writes: unknown[] = [], errors: string[] = []
  let reads = 0, failNext = false
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**/pon-ports*', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.startsWith('/api/monitoring/')) {
      reads++
      if (failNext) { failNext = false; return route.fulfill({ status: 502, json: { detail: 'OLT tidak dapat dihubungi.' } }) }
      return route.fulfill({ json: { oltId: olt.id, readAt: '2026-10-13T00:00:00Z', warnings: ['Port tanpa ONU mungkin belum terdeteksi.'],
        ports: [{ label: 'PON1', onuCount: 4, fromInterface: false }, { label: 'PON2', onuCount: 4, fromInterface: false }] } })
    }
    if (route.request().method() === 'POST') {
      writes.push(route.request().postDataJSON())
      ports.push({ ...ports[0], id: 'pon-3', label: route.request().postDataJSON().label, odcCount: 0 })
      return route.fulfill({ status: 201, json: ports.at(-1) })
    }
    return route.fulfill({ json: ports })
  })
  await page.route('**/api/olts/*/pon-ports/import', async route => {
    const body = route.request().postDataJSON()
    writes.push(body)
    ports.push({ ...ports[0], id: 'pon-2', label: 'PON2', odcCount: 0 })
    return route.fulfill({ json: { created: 1, existing: 0 } })
  })
  await page.goto('/olts/' + olt.id)
  await page.getByRole('tab', { name: /PON/ }).click()
  return { writes, errors, reads: () => reads, fail: () => { failNext = true } }
}

for (const width of [375, 1280]) {
  test(`PON detection, review and manual entry preserve the OLT at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page)
    const document = await page.evaluateHandle(() => window.document)
    await page.getByRole('button', { name: 'Deteksi port', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Deteksi port PON', exact: true })
    await expect(panel.getByRole('checkbox', { name: 'Pilih PON1' })).toBeDisabled()
    await expect(panel.getByRole('checkbox', { name: 'Pilih PON2' })).toBeChecked()
    expect(state.writes).toHaveLength(0)
    expect(await page.evaluate(() => window.document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    const indicator = panel.getByRole('checkbox', { name: 'Pilih PON2' }).locator('..').locator('.fui-Checkbox__indicator')
    await expect(indicator).toHaveCSS('border-top-style', 'solid')
    await expect(indicator).toHaveCSS('height', '16px')
    await page.screenshot({ path: testInfo.outputPath('pon-detection.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('button', { name: 'Tinjau + buat' }).click()
    expect(state.writes).toHaveLength(0)
    await panel.getByRole('button', { name: 'Buat', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await expect(page.getByRole('gridcell', { name: 'PON2', exact: true })).toBeVisible()
    expect(state.writes).toEqual([{ labels: ['PON2'] }])
    if (width < 720) {
      await page.locator('.resource-list-controls').getByRole('button', { name: 'Aksi lainnya' }).click()
      await page.getByRole('menuitem', { name: 'Tambah manual' }).click()
    } else await page.getByRole('button', { name: 'Tambah manual' }).click()
    const manual = page.getByRole('dialog', { name: 'Tambah port PON', exact: true })
    await manual.getByRole('textbox', { name: 'Label port' }).fill('PON3')
    await manual.getByRole('button', { name: 'Tinjau + buat' }).click()
    await manual.getByRole('button', { name: 'Buat', exact: true }).click()
    await expect(manual).toHaveCount(0)
    await expect(page.getByRole('gridcell', { name: 'PON3', exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('pon-list.png'), fullPage: true, animations: 'disabled' })
    expect(state.writes).toEqual([{ labels: ['PON2'] }, { label: 'PON3' }])
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    expect(state.errors).toEqual([])
  })
}

test('failed detection can retry and closing the result keeps the list unchanged', async ({ page }) => {
  const state = await setup(page)
  state.fail()
  await page.getByRole('button', { name: 'Deteksi port', exact: true }).click()
  const panel = page.getByRole('dialog', { name: 'Deteksi port PON', exact: true })
  await expect(panel.getByRole('alert')).toContainText('OLT tidak dapat dihubungi.')
  await panel.getByRole('button', { name: 'Coba lagi' }).click()
  await expect(panel.getByRole('checkbox', { name: 'Pilih PON2' })).toBeChecked()
  await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
  await expect(panel).toHaveCount(0)
  expect(state.reads()).toBe(2)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('read-only operators have no discovery or creation actions', async ({ page }) => {
  const state = await setup(page, false)
  await expect(page.getByRole('gridcell', { name: 'PON1', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Deteksi port', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Tambah (manual|port)/ })).toHaveCount(0)
  expect(state.reads()).toBe(0)
  expect(state.writes).toEqual([])
})
