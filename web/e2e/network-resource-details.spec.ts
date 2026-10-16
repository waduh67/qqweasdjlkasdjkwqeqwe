import { expect, test, type Page, type Locator } from '@playwright/test'
import { installOltInventoryRoutes, olt } from './oltInventoryFixtures'
import { spliceFixture } from '../src/test/spliceFixture'

async function choose(panel: Locator, label: string, key: string) {
  const tab = panel.getByRole('tab', { name: label, exact: true })
  if (await tab.isVisible()) await tab.click()
  else await panel.getByRole('combobox', { name: /Bagian/ }).selectOption(key)
}
async function bounds(page: Page, panel: Locator) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
}
for (const width of [375, 1440]) {
  test(`network details retain the list and nested edits at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 950 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await installOltInventoryRoutes(page, true, true, {}, ['network.site.view', 'network.odc.view', 'network.odf.view', 'network.jointbox.view', 'network.odp.update', 'network.splice.view', 'network.splice.manage', 'network.splitter.view'])
    const node = { id: 'node-qa', code: 'ODP-CRB-001', name: 'ODP Cirebon 001', address: 'Jalan Siliwangi, Cirebon', location: olt.location,
      odcId: 'odc-qa', odcName: 'ODC Cirebon', oltName: 'OLT Cirebon', ponPortLabel: 'PON1', splitterRatio: '1:8', splitterCount: 1, splitterLegs: 8,
      capacity: 24, trayCount: 4, spliceCount: 2, usedPortCount: 2, portCount: 24, status: 'ACTIVE', installedOn: null, mounting: null, notes: null,
      olts: [], siteName: 'POP Cirebon', siteId: 'site-qa', oltCount: 1, odpCount: 4 }
    const writes: string[] = []
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), path = url.pathname
      const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
      if (route.request().method() !== 'GET' && !path.includes('/auth/')) writes.push(path)
      const match = path.match(/^\/api\/(sites|odcs|odps|odfs|joint-boxes)(\/node-qa)?$/)
      if (match) {
        const kind = ({ sites: 'POP', odcs: 'ODC', odps: 'ODP', odfs: 'ODF', 'joint-boxes': 'JB' })[match[1]]
        const value = { ...node, code: `${kind}-CRB-001`, name: `${kind} Cirebon 001` }
        return json(match[2] ? value : { content: [value], totalElements: 1, totalPages: 1, size: 25, page: 0 })
      }
      if (path === '/api/splitters') return json({ ownerKind: 'ODP', ownerId: node.id, splitters: [] })
      if (path === '/api/fiber-trace/closure') return json([])
      if (path === '/api/fiber-connections/workbench') return json({ ...spliceFixture(), closureId: node.id })
      return route.fallback()
    })
    await page.goto('/inventory?keep=1')
    const document = await page.evaluateHandle(() => window.document)
    for (const [tab, kind] of [['Site / POP', 'POP'], ['ODC', 'ODC'], ['ODP', 'ODP'], ['ODF', 'ODF'], ['Joint box', 'JB']]) {
      const inventoryTab = page.locator('.inventory-page').getByRole('tab', { name: tab, exact: true })
      const anyTab = page.getByRole('tab', { name: tab, exact: true })
      if (await inventoryTab.count()) await inventoryTab.click(); else await anyTab.click()
      await page.getByText(`${kind} Cirebon 001`, { exact: true }).click()
      const panel = page.getByRole('dialog', { name: `${kind}-CRB-001`, exact: true })
      await expect(panel).toHaveAttribute('data-resource-layer', '0')
      await expect(panel.getByRole('definition').filter({ hasText: `${kind} Cirebon 001` })).toBeVisible()
      await bounds(page, panel)
      await page.screenshot({ path: info.outputPath(`${kind}-overview.png`), fullPage: true, animations: 'disabled' })
      await choose(panel, 'Lokasi', 'location')
      await expect(panel.getByText('Lintang', { exact: true })).toBeVisible()
      await choose(panel, 'Ringkasan', 'overview')
      if (kind === 'ODP') {
        await panel.getByRole('button', { name: 'Edit', exact: true }).click()
        const edit = page.getByRole('dialog', { name: 'Edit ODP-CRB-001', exact: true })
        await expect(edit).toHaveAttribute('data-resource-layer', '1')
        await edit.getByRole('button', { name: 'Tutup', exact: true }).click()
        await expect(panel).toBeVisible()
        await choose(panel, 'Sambungan', 'splicing')
        const source = panel.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru', exact: true })
        await source.click()
        await choose(panel, 'Ringkasan', 'overview')
        await choose(panel, 'Sambungan', 'splicing')
        await expect(source).toHaveAttribute('aria-pressed', 'true')
        await bounds(page, panel)
        expect((await panel.getByRole('textbox', { name: 'Cari sambungan' }).boundingBox())!.width).toBeGreaterThan(180)
        await page.screenshot({ path: info.outputPath('ODP-splicing.png'), fullPage: true, animations: 'disabled' })
      }
      await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
      await expect(panel).toHaveCount(0)
    }
    await page.getByRole('tab', { name: 'OLT', exact: true }).click()
    await page.getByText(olt.name, { exact: true }).click()
    const panel = page.getByRole('dialog', { name: olt.code, exact: true })
    await expect(panel.getByText('IP manajemen', { exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('OLT-overview.png'), fullPage: true, animations: 'disabled' })
    await choose(panel, 'ONU di OLT', 'onuolt')
    await expect(panel.getByText('TEST001122AA', { exact: true })).toBeVisible()
    await bounds(page, panel)
    await page.screenshot({ path: info.outputPath('OLT-onus.png'), fullPage: true, animations: 'disabled' })
    await choose(panel, 'Akses manajemen', 'management')
    await expect(panel.getByText('Community', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    expect(writes).toEqual([]); expect(errors).toEqual([])
  })

  test(`incident overview, activity and nested notification at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 950 })
    await installOltInventoryRoutes(page, false, false, {}, ['incident.ticket.view', 'incident.ticket.update', 'incident.ticket.close', 'notification.broadcast.send'])
    await page.addInitScript(() => localStorage.setItem('ftth.theme', 'dark'))
    const errors: string[] = [], writes: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    const incident = { id: 'incident-1', rootType: 'ONU', rootLabel: 'Siti Lestari', severity: 'MAJOR', status: 'OPEN', title: 'Sinyal ONU Siti Lestari', alarmCount: 1, affectedCustomerCount: 1, suspectedCause: null, openedAt: '2026-10-17T03:00:00Z', lastSeenAt: '2026-10-17T04:00:00Z' }
    await page.route('**/api/incidents**', async route => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() === 'POST') { writes.push(path); incident.status = path.endsWith('/resolve') ? 'RESOLVED' : 'ACKNOWLEDGED' }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(route.request().method() === 'POST' ? incident : path === '/api/incidents' ? [incident] : { incident,
        timeline: [{ type: 'OPENED', message: 'Insiden dibuka.', at: incident.openedAt }],
        members: [{ entityType: 'ONU', entityId: 'onu-1', label: 'Siti Lestari', kind: 'ONU_HIGH_RX', severity: 'MAJOR' }] }) })
    })
    await page.goto('/incidents?keep=1')
    const document = await page.evaluateHandle(() => window.document)
    await page.getByRole('button', { name: incident.title, exact: true }).click()
    const panel = page.getByRole('dialog', { name: incident.title, exact: true })
    await expect(panel.getByText('Sinyal terima terlalu kuat', { exact: true })).toBeVisible()
    await bounds(page, panel)
    await page.screenshot({ path: info.outputPath('incident-overview.png'), fullPage: true, animations: 'disabled' })
    expect(await panel.locator('.fui-DataGridCell .card,.fui-DataGridCell .badge').count()).toBe(0)
    await panel.getByRole('button', { name: 'Beritahu pelanggan' }).click()
    const form = page.getByRole('dialog', { name: 'Beritahu pelanggan', exact: true })
    await expect(form).toHaveAttribute('data-resource-layer', '1')
    await form.getByRole('tab', { name: 'Tinjau + kirim' }).click()
    await page.screenshot({ path: info.outputPath('incident-message-review.png'), fullPage: true, animations: 'disabled' })
    await form.getByRole('button', { name: 'Batal', exact: true }).click()
    await panel.getByRole('tab', { name: 'Aktivitas', exact: true }).click()
    await expect(panel.getByText('Insiden dibuka.', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Akui insiden' }).click()
    await expect(panel.getByRole('button', { name: 'Akui insiden' })).toHaveCount(0)
    await panel.getByRole('button', { name: 'Tutup insiden' }).click()
    await expect(panel.getByRole('button', { name: 'Tutup insiden' })).toHaveCount(0)
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    expect(writes).toHaveLength(2); expect(errors).toEqual([])
  })
}
