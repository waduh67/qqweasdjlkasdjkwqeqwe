import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { migrationCaseFixture, migrationHash } from '@/test/warehouseMigrationFixture'
import { provenanceCaseFixture, provenanceEvidenceFixture, provenanceFinalizationFixture, provenanceFinalizationReviewFixture,
  provenanceOpeningFixture, provenanceOpeningSummaryFixture, provenanceReviewFixture, provenanceSummaryFixture } from '@/test/warehouseProvenanceFixture'
import { WarehouseProvenancePage } from './WarehouseProvenancePage'
import { baselinePreview } from './provenancePresentation'
import { migrationCase } from '@/api/warehouse/provenanceModels'
const access = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions,
  user: { id: '70000000-0000-4000-8000-000000000003' }, can: (permission: string) => permissions.has(permission) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: access.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: access.user }) }))
const root = '/api/v1/warehouse/provenance', batch = root + '/batches/' + id.other
const casePath = batch + '/cases/' + id.line
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, total = items.length) => ({ items, page: number, size: 25, totalElements: total })
const sku = { id: id.sku, code: 'DROP', name: 'Kabel drop lama', tracking: 'LOT', baseUnit: 'MM', revision: 0, state: 'ACTIVE',
  category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0' }
const location = { id: id.source, code: 'WH', name: 'Gudang lama', kind: 'WAREHOUSE', revision: 0, state: 'ACTIVE',
  parentLocationId: null, siteId: null, areaId: id.target, custodianId: null, issueEligible: true }
function transport(override?: (path: string, init?: RequestInit) => Response | undefined | Promise<Response | undefined>) {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    const special = await override?.(path, init)
    if (special) return special
    if (init?.method && init.method !== 'GET') throw new Error('Unexpected command ' + path)
    if (path === root) return json(provenanceSummaryFixture())
    if (path.startsWith(root + '/cases?')) return json(page([provenanceCaseFixture()]))
    if (path === root + '/cases/' + id.line) return json(provenanceCaseFixture())
    if (path === batch + '/finalization') return json(provenanceFinalizationReviewFixture(false))
    if (path === batch + '/review') return json(provenanceReviewFixture())
    if (path.startsWith(casePath + '/evidence?')) return json(page([provenanceEvidenceFixture()]))
    if (path.startsWith(casePath + '/resolutions?')) return json(page([migrationCaseFixture().resolution]))
    if (path.startsWith(batch + '/opening?')) return json(page([provenanceOpeningSummaryFixture()]))
    if (path === batch + '/opening/' + id.document) return json(provenanceOpeningFixture())
    if (path.startsWith('/api/v1/warehouse/skus?')) return json(page([sku]))
    if (path.startsWith('/api/v1/warehouse/locations?')) return json(page([location]))
    throw new Error('Unexpected read ' + path)
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}
function show(path = '/warehouse/provenance') { return render(<MemoryRouter initialEntries={[path]}><WarehouseProvenancePage /></MemoryRouter>) }
beforeEach(() => {
  access.permissions.clear()
  for (const permission of ['inventory.provenance.manage', 'inventory.sku.view', 'inventory.location.view', 'inventory.approval.view', 'inventory.item.view']) access.permissions.add(permission)
  tokenStore.clear()
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })
it('denies missing management access and malformed links before reading any warehouse data', () => {
  const fetch = transport()
  access.permissions.clear(); let view = show()
  expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); view.unmount()
  access.permissions.add('inventory.provenance.manage'); view = show('/warehouse/provenance?caseId=bad')
  expect(screen.getByText('Alamat pemeriksaan gudang tidak dikenal.')).toBeTruthy()
  expect(fetch).not.toHaveBeenCalled()
})
it('shows explicit source zeros and retries the same reviewed begin command after response loss', async () => {
  let begun = false, attempts = 0
  const fetch = transport((path, init) => {
    if (path === root) return json(provenanceSummaryFixture(begun ? 'VALIDATING' : 'LEGACY', true))
    if (path.startsWith(root + '/cases?')) return json(page([]))
    if (path === root + '/batches' && init?.method === 'POST') {
      begun = true; attempts++
      if (attempts === 1) throw new TypeError('Response lost after commit')
      return json(provenanceSummaryFixture('VALIDATING', true), 201)
    }
  })
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Mulai pemeriksaan gudang' }))
  expect(screen.getByText(/Saldo awal nol tetap memerlukan pemeriksaan/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Mulai pemeriksaan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await screen.findByText('Pemeriksaan berlangsung')
  const calls = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(calls).toHaveLength(2); expect(calls[0][1]?.body).toBe(calls[1][1]?.body)
  expect(new Headers(calls[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(calls[1][1]?.headers).get('Idempotency-Key'))
  expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ expectedEpoch: 0, expectedPreservationHash: migrationHash })
})
it('selects original evidence and explicit units without allowing a client-supplied baseline quantity', async () => {
  let latest = migrationCaseFixture().resolution
  const fetch = transport((path, init) => {
    if (path.startsWith(casePath + '/resolutions?')) return json(page([latest]))
    if (path === casePath + '/resolutions' && init?.method === 'POST') {
      const input = JSON.parse(String(init.body))
      latest = { ...latest, revision: 2, reason: input.reason }
      return json(latest, 201)
    }
  })
  show('/warehouse/provenance?caseId=' + id.line)
  fireEvent.click(await screen.findByRole('checkbox', { name: 'Berita acara pemeriksaan kabel' }))
  fireEvent.change(screen.getByRole('combobox', { name: 'Hasil pemeriksaan' }), { target: { value: 'BASELINE_STOCK' } })
  await screen.findByRole('option', { name: 'Kabel drop lama · DROP' })
  fireEvent.change(screen.getByRole('combobox', { name: 'SKU saldo awal' }), { target: { value: id.sku } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Satuan pada bukti asli' }), { target: { value: 'MM' } })
  fireEvent.click(screen.getByRole('checkbox', { name: 'Bukti menunjukkan stok ini milik ISP' }))
  fireEvent.change(screen.getByLabelText(/Alasan dan rujukan bukti/), { target: { value: 'Panjang\u0085belum diperiksa' } })
  expect(screen.getByRole('button', { name: 'Tinjau keputusan' })).toHaveProperty('disabled', true)
  fireEvent.change(screen.getByLabelText(/Alasan dan rujukan bukti/), { target: { value: 'Panjang diperiksa dalam milimeter' } })
  expect(screen.getAllByText('82,500 m').length).toBeGreaterThan(0)
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau keputusan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Simpan keputusan' }))
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
  const input = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]?.body))
  expect(input).toEqual({ expectedEpoch: 1, expectedCaseHash: migrationHash, expectedResolutionRevision: 1, kind: 'BASELINE_STOCK',
    reason: 'Panjang diperiksa dalam milimeter', evidenceIds: [id.decision], stock: { skuId: id.sku, sourceUnit: 'MM', legalOwner: 'ISP' }, duplicateCaseId: null })
  expect(input.stock).not.toHaveProperty('quantityBase')
})
it('rediscovers a saved opening and links its frozen document to independent approval', async () => {
  const fetch = transport(); show('/warehouse/provenance?view=opening')
  fireEvent.click(await screen.findByRole('button', { name: 'Buka OPEN-LEGACY' }))
  const link = await screen.findByRole('link', { name: 'Buka persetujuan saldo awal' })
  expect(link.getAttribute('href')).toBe('/warehouse/approvals?sourceDocumentId=' + id.document)
  expect(screen.getByText(/Nilai pembelian dan biaya asal tidak diketahui/)).toBeTruthy()
  expect(fetch.mock.calls.some(([path]) => path === batch + '/opening/' + id.document)).toBe(true)
})
it('requires an explicit reviewed zero and actual review location before creating an empty opening', async () => {
  const fetch = transport((path, init) => {
    if (path === root) return json(provenanceSummaryFixture('VALIDATING', true))
    if (path === batch + '/finalization') return json(provenanceFinalizationReviewFixture(false, true))
    if (path === batch + '/review') return json(provenanceReviewFixture(true))
    if (path.startsWith(batch + '/opening?')) return json(page([]))
    if (path === batch + '/opening' && init?.method === 'POST') return json(provenanceOpeningFixture(true), 201)
    if (path === batch + '/opening/' + id.document) return json(provenanceOpeningFixture(true))
  })
  show('/warehouse/provenance?view=opening')
  fireEvent.click(await screen.findByRole('button', { name: 'Tinjau hasil pemeriksaan' }))
  await screen.findByRole('option', { name: 'Gudang lama · WH' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi pemeriksaan saldo awal' }), { target: { value: id.source } })
  fireEvent.change(screen.getByLabelText(/Referensi migrasi/), { target: { value: 'Pemeriksaan gudang kosong' } })
  fireEvent.change(screen.getByLabelText(/Alasan pengajuan saldo awal/), { target: { value: 'Tidak ada barang di gudang' } })
  expect(screen.getByRole('button', { name: 'Tinjau usulan saldo awal' })).toHaveProperty('disabled', true)
  fireEvent.click(screen.getByRole('checkbox', { name: /Pemeriksaan menyatakan saldo tersedia nol/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau usulan saldo awal' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Simpan usulan' }))
  await screen.findByRole('link', { name: 'Buka persetujuan saldo awal' })
  const input = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]?.body))
  expect(input).toEqual({ expectedEpoch: 1, expectedReviewHash: migrationHash, reviewLocationId: id.source, expectedReviewLocationRevision: 0,
    migrationReference: 'Pemeriksaan gudang kosong', reason: 'Tidak ada barang di gudang' })
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/warehouse/skus'))).toBe(false)
})
it('blocks opening creation for unresolved cases and keeps post-admission cases read only', async () => {
  const fetch = transport(path => path === batch + '/review' ? json({ ...provenanceReviewFixture(), issues: [{ caseId: id.line, code: 'RESOLUTION_REQUIRED' }] }) : undefined)
  let view = show('/warehouse/provenance?view=opening')
  fireEvent.click(await screen.findByRole('button', { name: 'Tinjau hasil pemeriksaan' }))
  await screen.findByText('Kasus ini masih memerlukan keputusan berbukti.')
  expect(screen.queryByRole('button', { name: 'Tinjau usulan saldo awal' })).toBeNull(); view.unmount()
  transport(path => path === batch + '/finalization' ? json(provenanceFinalizationReviewFixture()) : undefined)
  view = show('/warehouse/provenance?caseId=' + id.line)
  await screen.findByText(/Pemeriksaan ini sudah dibukukan/)
  expect(screen.queryByRole('button', { name: 'Tambah bukti' })).toBeNull()
  expect(screen.queryByRole('combobox', { name: 'Hasil pemeriksaan' })).toBeNull()
  expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
})
it('finalizes only the approved reviewed baseline and displays its durable result after reload', async () => {
  let completed = false
  const fetch = transport((path, init) => {
    if (path === root) return json(provenanceSummaryFixture(completed ? 'ENFORCED' : 'VALIDATING'))
    if (path === batch + '/finalization' && init?.method === 'POST') { completed = true; return json(provenanceFinalizationFixture()) }
    if (path === batch + '/finalization') return json(completed ? { ...provenanceFinalizationFixture(), issues: ['CUTOVER_NOT_VALIDATING'], finalization: provenanceFinalizationFixture() } : provenanceFinalizationReviewFixture())
  })
  show('/warehouse/provenance?view=opening')
  fireEvent.change(await screen.findByLabelText(/Catatan finalisasi/), { target: { value: 'Pemeriksaan independen selesai' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau aktivasi gudang' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Aktifkan gudang' }))
  await screen.findByRole('region', { name: 'Bukti finalisasi gudang' })
  expect(screen.queryByRole('button', { name: 'Tinjau aktivasi gudang' })).toBeNull()
  const input = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]?.body))
  expect(input).toEqual({ expectedEpoch: 1, openingDocumentId: id.document, expectedReviewHash: migrationHash, reason: 'Pemeriksaan independen selesai' })
  expect(screen.getAllByText('82,500 m').length).toBeGreaterThan(0)
})
it('removes current source details and controls when management access is revoked', async () => {
  transport()
  const view = show('/warehouse/provenance?caseId=' + id.line)
  await screen.findByRole('checkbox', { name: 'Berita acara pemeriksaan kabel' })
  access.permissions.clear()
  view.rerender(<MemoryRouter><WarehouseProvenancePage /></MemoryRouter>)
  expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy()
  expect(screen.queryByText('Berita acara pemeriksaan kabel')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Tinjau keputusan' })).toBeNull()
})

it('preserves known base units and refuses fractional or overflowing unverified source quantities', () => {
  const source = provenanceCaseFixture()
  const known = migrationCase({ ...source, sourceSnapshot: { ...source.sourceSnapshot, baseUnit: 'MM', quantityBase: '1001', legacyQuantity: '1' } })
  expect(baselinePreview(known, 'MM')).toEqual({ quantityBase: '1001', baseUnit: 'MM' })
  expect(baselinePreview(known, 'M')).toBeNull()
  const fractional = migrationCase({ ...source, sourceSnapshot: { ...source.sourceSnapshot, legacyQuantity: '82.5' } })
  expect(baselinePreview(fractional, 'M')).toBeNull()
  const tooLarge = migrationCase({ ...source, sourceSnapshot: { ...source.sourceSnapshot, legacyQuantity: '9223372036854775807' } })
  expect(baselinePreview(tooLarge, 'M')).toBeNull()
})
