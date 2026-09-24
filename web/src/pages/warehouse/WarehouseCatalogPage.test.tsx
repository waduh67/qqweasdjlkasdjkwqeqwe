import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseSkuEditor } from './WarehouseSkuEditor'
import { WarehouseSupplierEditor } from './WarehouseSupplierEditor'

const id = '797b131a-ddaf-46e4-90a0-e20c6ef3c5ea'
const existingSku = { id, code: 'KABEL', name: 'Kabel drop', revision: 4, state: 'ACTIVE' as const, tracking: 'LOT' as const, baseUnit: 'MM' as const,
  category: null, model: null, allowedOwnershipModes: ['LOAN', 'SALE'] as ('LOAN' | 'SALE')[], inspectionRequired: true, minimumQuantityBase: '82500' }
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } },
    close: { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } },
  })
  tokenStore.clear()
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })

it('reviews a cable master in metres then creates its exact MM minimum through the current API', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ ...existingSku, revision: 0 }))
  vi.stubGlobal('fetch', fetch)
  const onSaved = vi.fn()
  render(<WarehouseSkuEditor row={null} readOnly={false} onClose={vi.fn()} onSaved={onSaved} onReload={vi.fn()} />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Kode barang' }), { target: { value: 'kabel' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Nama barang' }), { target: { value: 'Kabel drop' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Pelacakan' }), { target: { value: 'LOT' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Satuan' }), { target: { value: 'MM' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Stok minimum (m)' }), { target: { value: '82,500' } })
  fireEvent.submit(document.querySelector('form')!)
  expect(fetch).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Simpan barang' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  expect(fetch.mock.calls[0][0]).toBe('/api/v1/warehouse/skus')
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ code: 'KABEL', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', category: null, model: null,
    allowedOwnershipModes: ['LOAN', 'SALE'], inspectionRequired: true, minimumQuantityBase: '82500' })
})

it('keeps an editable supplier draft after server validation rejects duplicate data', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ code: 'SOURCE_NOT_VERIFIED', message: 'SOURCE_NOT_VERIFIED' }, 409))
  vi.stubGlobal('fetch', fetch)
  const onSaved = vi.fn()
  render(<WarehouseSupplierEditor row={null} readOnly={false} onClose={vi.fn()} onSaved={onSaved} onReload={vi.fn()} />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Kode pemasok' }), { target: { value: 'DUP' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Nama pemasok' }), { target: { value: 'Pemasok kabel' } })
  fireEvent.submit(document.querySelector('form')!)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan pemasok' }))
  await screen.findByText('Sumber belum memenuhi syarat atau kode/serial sudah digunakan. Periksa barang, status dan referensinya.')
  fireEvent.click(screen.getByRole('button', { name: 'Kembali' }))
  expect((screen.getByRole('textbox', { name: 'Nama pemasok' }) as HTMLInputElement).value).toBe('Pemasok kabel')
  expect(onSaved).not.toHaveBeenCalled()
  expect(fetch).toHaveBeenCalledOnce()
})

it('renders an archived or read-only master without an actionable save path', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  render(<WarehouseSkuEditor row={{ ...existingSku, state: 'ARCHIVED' }} readOnly onClose={vi.fn()} onSaved={vi.fn()} onReload={vi.fn()} />)
  expect((screen.getByRole('textbox', { name: 'Nama barang' }) as HTMLInputElement).disabled).toBe(true)
  expect(screen.queryByRole('button', { name: 'Tinjau perubahan' })).toBeNull()
  fireEvent.submit(document.querySelector('form')!)
  expect(fetch).not.toHaveBeenCalled()
})

it('a stale master sends its displayed revision and requires explicit reload instead of overwriting another edit', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409))
  vi.stubGlobal('fetch', fetch)
  const onReload = vi.fn(), onSaved = vi.fn()
  render(<WarehouseSkuEditor row={existingSku} readOnly={false} onClose={vi.fn()} onSaved={onSaved} onReload={onReload} />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Nama barang' }), { target: { value: 'Nama baru' } })
  fireEvent.submit(document.querySelector('form')!)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan barang' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  expect(JSON.parse(fetch.mock.calls[0][1].body).expectedRevision).toBe(4)
  expect(onReload).toHaveBeenCalledOnce()
  expect(onSaved).not.toHaveBeenCalled()
  expect(fetch).toHaveBeenCalledOnce()
})

it('searching a directory retains the selected named reference and exposes subsequent pages', async () => {
  const selected = { id, name: 'Gudang utama' }
  const onChange = vi.fn()
  const load = vi.fn().mockResolvedValue({ items: [{ id: 'other', name: 'Gudang barat' }], page: 0, size: 1, totalElements: 2 })
  render(<WarehousePicker label="Lokasi" load={load} value={selected} onChange={onChange} name={row => row.name} />)
  await screen.findByRole('option', { name: 'Gudang barat' })
  fireEvent.change(screen.getByRole('textbox', { name: 'Cari lokasi' }), { target: { value: 'barat' } })
  await waitFor(() => expect(load).toHaveBeenLastCalledWith('barat', 0))
  await waitFor(() => expect((screen.getByRole('combobox', { name: 'Lokasi' }) as HTMLSelectElement).disabled).toBe(false))
  expect((screen.getByRole('combobox', { name: 'Lokasi' }) as HTMLSelectElement).value).toBe(id)
  expect(onChange).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Pilihan lokasi berikutnya' }))
  await waitFor(() => expect(load).toHaveBeenLastCalledWith('barat', 1))
})
