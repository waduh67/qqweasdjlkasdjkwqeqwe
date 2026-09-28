import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { FilterBar, FilterDateRange, FilterSearch, FilterSelect, FilterText } from './ResourceFilters'
import { filterControl, openFilter } from '@/test/filterControl'
import { selectControl } from '@/test/selectControl'

it('applies a selection immediately and removes only its own filter', async () => {
  function List() {
    const [status, setStatus] = useState('ACTIVE'), [owner, setOwner] = useState('')
    return <FilterBar>
      <FilterSelect label="Status" value={status} onChange={setStatus}><option value="">Semua</option><option value="ACTIVE">Aktif</option></FilterSelect>
      <FilterSelect secondary label="Pemilik" value={owner} onChange={setOwner}><option value="">Semua</option><option value="ISP">ISP</option></FilterSelect>
    </FilterBar>
  }
  render(<List />)
  await selectControl(await filterControl('Pemilik'), { target: { value: 'ISP' } })
  expect(screen.getByRole('button', { name: 'Filter Pemilik' }).textContent).toContain('ISP')
  expect(screen.queryByRole('combobox', { name: 'Pemilik' })).toBeNull()
  await userEvent.click(screen.getByRole('button', { name: 'Hapus Pemilik' }))
  expect(screen.queryByRole('button', { name: 'Filter Pemilik' })).toBeNull()
  expect(screen.getByRole('button', { name: 'Filter Status' }).textContent).toContain('Aktif')
  await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Tambah filter' })))
})

it('retains a typed serial when its popover is closed before the debounce fires', async () => {
  const changed = vi.fn()
  function List() { const [value, setValue] = useState(''); return <FilterBar><FilterText secondary label="Serial" value={value} onChange={next => { changed(next); setValue(next) }} /></FilterBar> }
  render(<List />)
  await openFilter('Serial')
  const input = screen.getByRole('textbox', { name: 'Serial' })
  input.focus(); fireEvent.change(input, { target: { value: 'ONU-001' } })
  fireEvent.keyDown(input, { key: 'Escape' })
  expect(screen.queryByRole('textbox', { name: 'Serial' })).toBeNull()
  await waitFor(() => expect(changed).toHaveBeenCalledExactlyOnceWith('ONU-001'))
  expect(screen.getByRole('button', { name: 'Filter Serial' }).textContent).toContain('ONU-001')
})

it('cancels pending search when browser navigation restores the same applied value', async () => {
  const changed = vi.fn()
  const view = render(<FilterSearch label="Cari" value="" onChange={changed} />)
  const input = screen.getByRole('textbox', { name: 'Cari' })
  input.focus(); fireEvent.change(input, { target: { value: 'pending' } })
  view.rerender(<FilterSearch label="Cari" value="" resetKey="previous-entry" onChange={changed} />)
  expect(input).toHaveProperty('value', '')
  await act(() => new Promise(resolve => setTimeout(resolve, 300)))
  expect(changed).not.toHaveBeenCalled()
  expect(document.activeElement).toBe(input)
})

it('publishes a complete valid date interval atomically and includes the last selected day', async () => {
  const changed = vi.fn()
  render(<FilterBar><FilterDateRange label="Tanggal" onChange={changed} /></FilterBar>)
  await openFilter('Tanggal')
  fireEvent.change(screen.getByLabelText('Mulai tanggal'), { target: { value: '2026-09-10' } })
  expect(changed).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Sampai tanggal'), { target: { value: '2026-09-09' } })
  expect(screen.getByRole('alert')).toBeTruthy(); expect(changed).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Sampai tanggal'), { target: { value: '2026-09-10' } })
  expect(changed).toHaveBeenCalledExactlyOnceWith(new Date('2026-09-10T00:00:00').toISOString(), new Date('2026-09-11T00:00:00').toISOString())
})

it('updates an open date editor when navigation restores another interval', async () => {
  const changed = vi.fn(), iso = (date: string) => new Date(`${date}T00:00:00`).toISOString()
  const view = render(<FilterDateRange label="Periode" from={iso('2026-09-01')} until={iso('2026-09-11')} onChange={changed} />)
  await openFilter('Periode')
  view.rerender(<FilterDateRange label="Periode" from={iso('2026-08-01')} until={iso('2026-08-11')} resetKey="previous-entry" onChange={changed} />)
  expect(screen.getByLabelText('Mulai tanggal')).toHaveProperty('value', '2026-08-01')
  expect(screen.getByLabelText('Sampai tanggal')).toHaveProperty('value', '2026-08-10')
  fireEvent.change(screen.getByLabelText('Sampai tanggal'), { target: { value: '2026-08-12' } })
  expect(changed).toHaveBeenCalledExactlyOnceWith(iso('2026-08-01'), iso('2026-08-13'))
})
