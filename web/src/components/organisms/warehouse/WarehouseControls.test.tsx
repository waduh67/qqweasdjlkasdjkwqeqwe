import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ApiError, tokenStore } from '@/api/client'
import { command } from '@/api/warehouse/transport'
import { integer } from '@/api/warehouse/codec'
import { WarehouseCommandDialog } from './WarehouseCommandDialog'
import { WarehouseSerialLookup } from './WarehouseSerialLookup'
import { WarehouseQuantityField } from './WarehouseQuantity'

beforeEach(() => {
  // jsdom has no native dialog top layer; browser tests exercise the actual implementation.
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } },
    close: { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } },
  })
  tokenStore.clear()
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })

it('retains the captured command after a lost reply and blocks dismiss and double submission', async () => {
  let resolve: (value: number) => void = () => {}
  const first = { key: 'first', path: '/api/v1/warehouse/skus', body: '{}', execute: vi.fn().mockRejectedValueOnce(new TypeError('lost')).mockImplementation(() => new Promise<number>(done => { resolve = done })) }
  const onDone = vi.fn(), onClose = vi.fn()
  const props = { title: 'Simpan barang', summary: <p>Kabel</p>, onDone, onClose }
  const rendered = render(<WarehouseCommandDialog {...props} command={first} />)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan' }))
  await screen.findByText('Hasil transaksi belum terkonfirmasi. Coba lagi dengan transaksi yang sama sebelum mengubah isinya.')
  fireEvent.click(screen.getByRole('button', { name: 'Tutup' }))
  expect(onClose).not.toHaveBeenCalled()
  const another = { ...first, key: 'another', execute: vi.fn() }
  rendered.rerender(<WarehouseCommandDialog {...props} command={another} />)
  fireEvent.click(screen.getByRole('button', { name: 'Coba transaksi yang sama' }))
  fireEvent.click(screen.getByRole('button', { name: 'Memproses…' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tutup' }))
  expect(first.execute).toHaveBeenCalledTimes(2)
  expect(another.execute).not.toHaveBeenCalled()
  expect(onClose).not.toHaveBeenCalled()
  resolve(1)
  await waitFor(() => expect(onDone).toHaveBeenCalledWith(1))
})

it('a stale revision asks for a reload instead of posting a newly invented revision', async () => {
  const execute = vi.fn().mockRejectedValue(new ApiError(409, 'stale', undefined, 'STALE_REVISION'))
  const onReload = vi.fn()
  render(<WarehouseCommandDialog title="Simpan" summary="Kabel" command={{ key: 'same', body: '{}', path: '/api/v1/warehouse/skus', execute }} onDone={vi.fn()} onClose={vi.fn()} onReload={onReload} />)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  expect(onReload).toHaveBeenCalledOnce()
  expect(execute).toHaveBeenCalledOnce()
  expect(screen.queryByRole('button', { name: 'Coba transaksi yang sama' })).toBeNull()
})

it('scanner Enter resolves a real-shaped identity candidate without submitting its surrounding form', async () => {
  const id = '6a62ed81-1fe1-4ed9-8c11-8417c5f65275'
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ assetId: id, skuId: id, locationId: id, serial: 'ONU-1', mac: null, legacyUnresolved: false }), { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const onSelect = vi.fn(), onSubmit = vi.fn()
  render(<form onSubmit={onSubmit}><WarehouseSerialLookup onSelect={onSelect} /></form>)
  const input = screen.getByLabelText('Serial atau MAC')
  fireEvent.change(input, { target: { value: ' onu-1 ' } })
  fireEvent.keyDown(input, { key: 'Enter' })
  await waitFor(() => expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ serial: 'ONU-1', assetId: id })))
  expect(fetch).toHaveBeenCalledOnce()
  expect(fetch.mock.calls[0][0]).toBe('/api/v1/warehouse/assets/lookup?value=onu-1')
  expect(fetch.mock.calls[0][1].method ?? 'GET').toBe('GET')
  expect(onSubmit).not.toHaveBeenCalled()
})

it('quantity entry preserves excess precision as invalid input and never rounds it into a valid posting', () => {
  const onChange = vi.fn()
  render(<WarehouseQuantityField value="" unit="MM" onChange={onChange} />)
  const input = screen.getByRole('textbox', { name: 'Jumlah (m)' })
  fireEvent.change(input, { target: { value: '82,5001' } })
  expect(onChange).toHaveBeenLastCalledWith('82,5001', null)
  fireEvent.change(input, { target: { value: '82,500' } })
  expect(onChange).toHaveBeenLastCalledWith('82,500', '82500')
})

it('a malformed successful response keeps retry identity instead of confirming a stock change', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{"revision":"unknown"}', { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const onDone = vi.fn()
  render(<WarehouseCommandDialog title="Simpan" summary="Kabel" command={command('/api/v1/warehouse/skus', 'POST', {}, integer)} onDone={onDone} onClose={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan' }))
  await screen.findByRole('button', { name: 'Coba transaksi yang sama' })
  expect(onDone).not.toHaveBeenCalled()
})
