import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { receiptFixture, receiptIds } from '@/test/warehouseReceiptFixture'
import { transferDetailsFixture, transferFixture, transferPositionFixture } from '@/test/warehouseTransferFixture'
import { WarehouseArchivePage } from './WarehouseArchivePage'

const access = vi.hoisted(() => ({ permissions: new Set<string>() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (value: string) => access.permissions.has(value), hasPermission: (value: string) => access.permissions.has(value) }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, totalElements = items.length) => ({ items, page: number, size: 25, totalElements })
function show(path = '/warehouse/archive') { return render(<MemoryRouter initialEntries={[path]}><WarehouseArchivePage /></MemoryRouter>) }
beforeEach(() => { access.permissions.clear(); tokenStore.clear() })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('denies reference grants and old write-only grants before reading the archive', () => {
  for (const permission of ['warehouse.stock.view', 'inventory.receipt.manage', 'inventory.transfer.manage']) access.permissions.add(permission)
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  show()
  expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy()
  expect(fetch).not.toHaveBeenCalled()
})

it.each(['section=unknown', 'section=receipts&id=invalid', 'section=receipts&new=1', 'section=stock&segment=' + receiptIds.line, 'section=receipts&id=' + receiptIds.document + '&id=' + receiptIds.document])('rejects malformed addresses before reading data: %s', address => {
  access.permissions.add('inventory.receipt.view'); access.permissions.add('inventory.item.view')
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  show('/warehouse/archive?' + address)
  expect(screen.getByText('Alamat arsip tidak dikenal.')).toBeTruthy()
  expect(fetch).not.toHaveBeenCalled()
})

it('keeps receipt history readable without exposing writers even with all old permissions', async () => {
  for (const permission of ['inventory.receipt.view', 'inventory.receipt.manage', 'inventory.sku.view', 'inventory.location.view']) access.permissions.add(permission)
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/history')) return response([{ operationId: receiptIds.piece, revision: 4, action: 'PUTAWAY', recordedAt: '2026-09-24T17:00:00Z' }])
    if (path.includes('/attachments?')) return response(page([]))
    return response(receiptFixture())
  }); vi.stubGlobal('fetch', fetch)
  show('/warehouse/archive?section=receipts&id=' + receiptIds.document)
  await screen.findByText('SJ-001')
  await screen.findByText('Barang ditempatkan')
  expect(screen.getByText('1000,000 m')).toBeTruthy()
  expect(screen.queryByRole('button', { name: /Terima barang|Tambah bukti|Ubah draft/ })).toBeNull()
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/v1/warehouse/receipts/'))).toBe(true)
  expect([...document.querySelectorAll('a')].every(link => link.getAttribute('href')?.startsWith('/warehouse/archive'))).toBe(true)
})

it('paginates receipt lists through the server with only the receipt read grant', async () => {
  access.permissions.add('inventory.receipt.view')
  const fetch = vi.fn(async (path: string) => response(page(Array.from({ length: path.includes('page=1') ? 1 : 25 }, (_, index) => ({ ...receiptFixture(), id: '00000000-0000-4000-8000-' + String(index + (path.includes('page=1') ? 26 : 1)).padStart(12, '0'), externalReference: path.includes('page=1') ? 'SJ-LAST' : 'SJ-' + index })), path.includes('page=1') ? 1 : 0, 26)))
  vi.stubGlobal('fetch', fetch); show()
  await screen.findByRole('link', { name: 'SJ-0' })
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('link', { name: 'SJ-LAST' })
  expect(fetch.mock.calls.at(-1)?.[0]).toContain('page=1&size=25')
  expect(screen.queryByRole('tab', { name: 'Transfer lama' })).toBeNull()
})

it('keeps transfer details read-only and displays exact stored quantities and named participants', async () => {
  access.permissions.add('inventory.transfer.view'); access.permissions.add('inventory.transfer.manage')
  const transfer = transferFixture()
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('/history/page?') ? response(page([transfer])) : response(transferDetailsFixture(transfer))))
  show('/warehouse/archive?section=transfers&id=' + transfer.id)
  await screen.findByRole('heading', { name: 'TR-001' })
  expect(screen.getByText('Pengirim: Petugas asal · Penerima: Petugas tujuan')).toBeTruthy()
  expect(screen.queryByRole('button', { name: /Kirim ke transit|Ubah draft transfer/ })).toBeNull()
  const detail = screen.getByRole('region', { name: 'Detail transfer lama' })
  expect(within(detail).getByText(/Rak A · BIN-A/)).toBeTruthy()
  await within(screen.getByRole('region', { name: 'Riwayat transfer lama' })).findByText('100,000 m')
  expect(screen.getAllByText('100,000 m').length).toBe(2)
})

it('keeps stock lineage links inside archive and does not read a forbidden section', async () => {
  access.permissions.add('inventory.item.view')
  const position = transferPositionFixture()
  const fetch = vi.fn(async (path: string) => path.includes('/history?') ? response(page([])) : response(position))
  vi.stubGlobal('fetch', fetch)
  const view = show('/warehouse/archive?section=stock&position=' + position.id)
  await screen.findByRole('heading', { name: 'Kabel drop' })
  expect(screen.getByRole('link', { name: 'Telusuri lot / reel asal' }).getAttribute('href')).toBe('/warehouse/archive?section=stock&lot=' + position.lotId)
  view.unmount(); fetch.mockClear()
  show('/warehouse/archive?section=receipts&id=' + receiptIds.document)
  expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy()
  expect(fetch).not.toHaveBeenCalled()
})
