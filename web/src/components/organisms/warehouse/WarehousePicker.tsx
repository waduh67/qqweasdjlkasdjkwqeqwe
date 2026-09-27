import { useCallback, useEffect, useState } from 'react'
import { Combobox, Field, Option, Spinner } from '@fluentui/react-components'
import type { WarehousePage } from '@/api/warehouse/codec'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { warehouseError } from '@/api/warehouse/errors'

/** Search and selection share one control; result paging retains the selected reference. */
export function WarehousePicker<T extends { id: string }>({ label, load, value, onChange, name, optional = false, placeholder, disabled = false, searchable = true, eligible = () => true }: {
  label: string; load: (search: string, page: number) => Promise<WarehousePage<T>>; value: T | null; onChange: (value: T | null) => void;
  name: (value: T) => string; optional?: boolean; placeholder?: string; disabled?: boolean; searchable?: boolean; eligible?: (value: T) => boolean
}) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search.trim()); setPage(0) }, search ? 200 : 0)
    return () => window.clearTimeout(timer)
  }, [search])
  const loader = useCallback(() => load(query, page), [load, query, page])
  const { state, reload } = useWarehouseQuery(loader)
  const rows = state.status === 'ready' ? state.data.items : []
  const choices = value && !rows.some(row => row.id === value.id) ? [value, ...rows] : rows
  const loading = state.status === 'loading' || search.trim() !== query
  return <Field className="app-field warehouse-picker" label={label} required={!optional}>
    <Combobox size="small" className="app-control app-control-compact" aria-label={label}
      open={open} disabled={disabled} value={open && searchable ? search : value ? name(value) : ''}
      selectedOptions={value ? [value.id] : []} placeholder={placeholder ?? 'Pilih…'}
      input={{ readOnly: !searchable, autoComplete: 'off' }}
      onOpenChange={(_, data) => { setOpen(data.open) }}
      onChange={event => { setSearch(event.target.value); setOpen(true) }}
      onOptionSelect={(_, data) => {
        if (data.optionValue === '__next' || data.optionValue === '__previous' || data.optionValue === '__retry') {
          if (data.optionValue === '__retry') reload()
          else setPage(current => current + (data.optionValue === '__next' ? 1 : -1))
          queueMicrotask(() => setOpen(true))
          return
        }
        if (data.optionValue === '__clear' && optional) onChange(null)
        else {
          const selected = choices.find(row => row.id === data.optionValue)
          if (!selected || !eligible(selected)) return
          onChange(selected)
        }
        setOpen(false); setSearch('')
      }}>
      {optional && <Option value="__clear" data-value="">{placeholder ?? 'Tidak dipilih'}</Option>}
      {choices.map(row => <Option key={row.id} data-value={row.id} value={row.id} text={name(row)} disabled={!eligible(row) || loading}>{name(row)}</Option>)}
      {loading && <Option disabled value="__loading" text="Memuat pilihan"><Spinner size="tiny" /> Memuat…</Option>}
      {state.status === 'error' && <Option value="__retry" text="Muat ulang pilihan">{warehouseError(state.error)} — Muat ulang</Option>}
      {state.status === 'ready' && !loading && rows.length === 0 && <Option disabled value="__empty">Tidak ada hasil</Option>}
      {state.status === 'ready' && !loading && page > 0 && <Option value="__previous">Pilihan sebelumnya</Option>}
      {state.status === 'ready' && !loading && (page + 1) * state.data.size < state.data.totalElements && <Option value="__next">Pilihan berikutnya</Option>}
    </Combobox>
  </Field>
}
