import { expect, test, type Page } from '@playwright/test'
import { myResidual } from '../src/test/myMaterialsFixture'
import { returnDetailsFixture, returnFixture, returnQuarantine, returnSourceFixture } from '../src/test/warehouseReturnFixture'
import { approvalIds as ids } from '../src/test/warehouseApprovalFixture'
import { migrationCaseFixture } from '../src/test/warehouseMigrationFixture'
import { provenanceCaseFixture, provenanceEvidenceFixture, provenanceFinalizationReviewFixture, provenanceOpeningFixture, provenanceOpeningSummaryFixture, provenanceReviewFixture, provenanceSummaryFixture } from '../src/test/warehouseProvenanceFixture'

const paged = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
async function mockWarehouse(page: Page) {
  const reads: string[] = [], writes: { path: string; body: Record<string, unknown> }[] = [], errors: string[] = []
  let received = false, created = false
  const profile = { id: ids.requester, email: 'qa@example.test', name: 'QA', tenantId: ids.policy, tenantSlug: 'qa', platformAdmin: true, permissions: [], areaIds: [], roleIds: [], twoFactorEnabled: true }
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    let body: unknown = paged([])
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/areas') body = []
    else if (path.startsWith('/api/v1/warehouse/') || path.startsWith('/api/work-orders/')) {
      reads.push(url.pathname + url.search)
      if (request.method() === 'POST') {
        writes.push({ path, body: request.postDataJSON() })
        if (path.endsWith('/residuals/acknowledge')) { received = true; body = { documentId: myResidual().id, state: 'RECEIVED_IN_INSPECTION' } }
        else if (path.endsWith('/returns')) { created = true; body = returnFixture() }
        else if (path.endsWith('/resolutions')) body = { ...migrationCaseFixture().resolution, revision: 2 }
        else throw new Error('Unexpected write ' + path)
      } else if (path.endsWith('/material-returns/pending')) body = paged(received ? [] : [myResidual()])
      else if (path.endsWith('/returns/workbench')) body = paged(created ? [returnDetailsFixture()] : [])
      else if (path.endsWith('/returns/sources')) body = paged([{ ...returnSourceFixture(), sourceDocumentId: myResidual().id, code: myResidual().code }])
      else if (path.endsWith('/details')) body = returnDetailsFixture()
      else if (path.endsWith('/history/page')) body = paged([returnFixture()])
      else if (path.endsWith('/locations/' + returnQuarantine.id)) body = returnQuarantine
      else if (path.endsWith('/locations')) body = paged([{ ...returnQuarantine, id: ids.source, kind: 'WAREHOUSE', issueEligible: true }])
      else if (path.endsWith('/provenance')) body = provenanceSummaryFixture()
      else if (path.endsWith('/provenance/cases')) body = paged([provenanceCaseFixture()])
      else if (path.endsWith('/provenance/cases/' + ids.line)) body = provenanceCaseFixture()
      else if (path.endsWith('/finalization')) body = provenanceFinalizationReviewFixture(false)
      else if (path.endsWith('/review')) body = provenanceReviewFixture()
      else if (path.endsWith('/evidence')) body = paged([provenanceEvidenceFixture()])
      else if (path.endsWith('/resolutions')) body = paged([migrationCaseFixture().resolution])
      else if (path.endsWith('/opening')) body = paged([provenanceOpeningSummaryFixture()])
      else if (path.endsWith('/opening/' + ids.document)) body = provenanceOpeningFixture()
      else if (path.endsWith('/skus')) body = paged([{ id: ids.sku, code: 'DROP', name: 'Kabel drop lama', tracking: 'LOT', baseUnit: 'MM', revision: 0, state: 'ACTIVE', category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0' }])
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { reads, writes, errors }
}

for (const width of [375, 1280]) {
  test(`pending returns retain the list and require reviewed reception at ${width}px`, async ({ page }, testInfo) => {
    const { reads, writes, errors } = await mockWarehouse(page)
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/warehouse/returns')
    const filter = page.getByRole('textbox', { name: 'Cari kode atau barang retur' })
    await filter.fill('RET')
    await expect.poll(() => reads.some(path => path.includes('query=RET'))).toBe(true)
    expect(reads.some(path => path.includes('material-returns/pending'))).toBe(false)
    await page.getByRole('tab', { name: 'Menunggu penerimaan', exact: true }).click()
    await page.getByRole('button', { name: myResidual().code, exact: true }).waitFor()
    await page.screenshot({ path: testInfo.outputPath('pending-list.png'), fullPage: true })
    const cells = page.locator('.resource-data-table-grid [role=gridcell]')
    expect(await cells.count()).toBeGreaterThan(0)
    expect(await cells.evaluateAll(nodes => nodes.every(node => getComputedStyle(node).whiteSpace === 'nowrap'))).toBe(true)
    await page.getByRole('button', { name: myResidual().code, exact: true }).click()
    await page.getByRole('button', { name: 'Terima material', exact: true }).click()
    await page.getByRole('textbox', { name: 'Bukti penerimaan', exact: true }).fill('BA-TERIMA')
    await page.getByRole('button', { name: 'Tinjau penerimaan', exact: true }).click()
    expect(writes).toEqual([])
    await page.screenshot({ path: testInfo.outputPath('receive-review.png'), fullPage: true })
    await page.getByRole('button', { name: 'Terima material', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(writes).toHaveLength(1)
    expect(writes[0].body).toMatchObject({ documentId: myResidual().id, expectedRevision: myResidual().revision, evidenceReference: 'BA-TERIMA' })
    await page.getByRole('button', { name: 'Catat retur', exact: true }).click()
    await expect(page.getByRole('combobox', { name: 'Sumber retur' })).toHaveValue(new RegExp(myResidual().code))
    await page.getByRole('textbox', { name: 'Referensi bukti penerimaan retur' }).fill('BA-RETUR')
    await page.getByRole('button', { name: 'Tinjau penerimaan retur' }).click()
    await page.getByRole('button', { name: 'Catat retur', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'RET-001', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(filter).toHaveValue('RET')
    await expect(page.getByRole('link', { name: 'RET-001', exact: true })).toBeVisible()
    expect(writes).toHaveLength(2)
    expect(writes[1].body).toEqual({ origin: 'MATERIAL_RESIDUAL', sourceDocumentId: myResidual().id, quarantineLocationId: returnQuarantine.id, evidenceReference: 'BA-RETUR' })
    expect(errors).toEqual([])
  })

  test(`reconciliation keeps filters and supports nested evidence decisions at ${width}px`, async ({ page }, testInfo) => {
    const { reads, writes, errors } = await mockWarehouse(page)
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/warehouse/provenance')
    await page.getByRole('button', { name: 'Filter Jenis catatan', exact: true }).click()
    await page.getByRole('combobox', { name: 'Jenis catatan', exact: true }).selectOption('inventory_balance_projection')
    await expect.poll(() => reads.some(path => path.includes('sourceTable=inventory_balance_projection'))).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('reconciliation-list.png'), fullPage: true })
    const listReads = reads.filter(path => path.includes('/provenance/cases?')).length
    await page.getByRole('button', { name: 'Saldo lama · Gudang lama', exact: true }).click()
    await page.getByRole('checkbox', { name: 'Berita acara pemeriksaan kabel', exact: true }).check()
    await page.getByRole('button', { name: 'Catat keputusan pemeriksaan', exact: true }).click()
    await page.getByRole('combobox', { name: 'Hasil pemeriksaan', exact: true }).selectOption('BASELINE_STOCK')
    await page.getByRole('combobox', { name: 'SKU saldo awal', exact: true }).click()
    await page.getByRole('option', { name: 'Kabel drop lama · DROP', exact: true }).click()
    await page.getByRole('combobox', { name: 'Satuan pada bukti asli', exact: true }).selectOption('MM')
    await page.getByRole('checkbox', { name: 'Bukti menunjukkan stok ini milik ISP', exact: true }).check()
    await page.getByRole('textbox', { name: 'Alasan dan rujukan bukti', exact: true }).fill('Bukti fisik diperiksa')
    await page.getByRole('button', { name: 'Tinjau keputusan', exact: true }).click()
    await page.screenshot({ path: testInfo.outputPath('decision-review.png'), fullPage: true })
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await page.getByRole('button', { name: 'Catat keputusan pemeriksaan', exact: true }).click()
    await expect(page.getByRole('textbox', { name: 'Alasan dan rujukan bukti', exact: true })).toHaveValue('')
    await expect(page.getByRole('tab', { name: 'Dasar', exact: true })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Filter Jenis catatan', exact: true })).toContainText('Saldo lama')
    expect(reads.filter(path => path.includes('/provenance/cases?'))).toHaveLength(listReads)
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })

  test(`reconciliation shows activation steps and nested saved cases at ${width}px`, async ({ page }, testInfo) => {
    const { writes, errors } = await mockWarehouse(page)
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/warehouse/provenance?view=opening')
    await expect(page.getByRole('heading', { name: '1. Tinjau hasil pemeriksaan', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'OPEN-LEGACY', exact: true }).waitFor()
    await page.screenshot({ path: testInfo.outputPath('opening-steps.png'), fullPage: true })
    await page.getByRole('button', { name: 'OPEN-LEGACY', exact: true }).click()
    await page.getByRole('button', { name: 'Saldo lama', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Pemeriksaan catatan lama', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page.getByRole('link', { name: 'Buka persetujuan saldo awal', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await page.getByRole('button', { name: 'Tinjau hasil pemeriksaan', exact: true }).click()
    await page.getByRole('button', { name: 'Susun saldo awal', exact: true }).click()
    await expect(page.getByRole('textbox', { name: 'Referensi migrasi', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(page.getByRole('dialog', { name: 'Hasil pemeriksaan', exact: true })).toBeVisible()
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}
