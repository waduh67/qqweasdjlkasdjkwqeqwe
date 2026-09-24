import { useCallback, useState } from 'react'
import type { WarehousePage } from '@/api/warehouse/codec'
import { Button, SelectField, TextField } from '@/components/atoms'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { warehouseError } from '@/api/warehouse/errors'

/** Bounded directory selection. Searching a new page never drops the selected reference. */
export function WarehousePicker<T extends { id: string }>({ label, load, value, onChange, name, optional = false, disabled = false, searchable = true, eligible = () => true }: {
  label: string; load: (search: string, page: number) => Promise<WarehousePage<T>>; value: T | null; onChange: (value: T | null) => void;
  name: (value: T) => string; optional?: boolean; disabled?: boolean; searchable?: boolean; eligible?: (value: T) => boolean
}) {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const loader = useCallback(() => load(search, page), [load, search, page])
  const { state, reload } = useWarehouseQuery(loader)
  const rows = state.status === 'ready' ? state.data.items : []
  const choices = value && !rows.some(row => row.id === value.id) ? [value, ...rows] : rows
  return <div className="stack" style={{ gap: '0.35rem' }}>
    {searchable && <TextField label={`Cari ${label.toLowerCase()}`} value={search} maxLength={200} disabled={disabled} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />}
    <SelectField label={label} value={value?.id ?? ''} disabled={disabled || state.status !== 'ready'} required={!optional}
      onChange={(_, data) => onChange(choices.find(row => row.id === data.value) ?? null)}>
      <option value="">{optional ? 'Tidak dipilih' : 'Pilih…'}</option>
      {choices.map(row => <option key={row.id} value={row.id} disabled={!eligible(row)}>{name(row)}{!eligible(row) ? ' — tidak memenuhi syarat' : ''}</option>)}
    </SelectField>
    {state.status === 'loading' && <span className="muted" role="status">Memuat pilihan…</span>}
    {state.status === 'error' && <div role="alert"><p className="error">{warehouseError(state.error)}</p><Button disabled={disabled} onClick={reload}>Muat ulang pilihan</Button></div>}
    {state.status === 'ready' && state.data.items.length === 0 && <span className="muted">Tidak ada pilihan yang cocok dalam cakupan Anda.</span>}
    {state.status === 'ready' && (state.data.totalElements > state.data.size || page > 0) && <div className="row wrap">
      <Button disabled={disabled || page === 0} onClick={() => setPage(page - 1)} aria-label={`Pilihan ${label.toLowerCase()} sebelumnya`}>Sebelumnya</Button>
      <span className="muted">Halaman {page + 1}</span>
      <Button disabled={disabled || (page + 1) * state.data.size >= state.data.totalElements} onClick={() => setPage(page + 1)} aria-label={`Pilihan ${label.toLowerCase()} berikutnya`}>Berikutnya</Button>
    </div>}
  </div>
}
