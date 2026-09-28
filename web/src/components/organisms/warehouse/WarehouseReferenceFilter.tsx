import { useCallback } from 'react'
import type { WarehousePage } from '@/api/warehouse/codec'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { FilterPicker, FilterToken } from '../ResourceFilters'

export function WarehouseReferenceFilter<T extends { id: string }>({ label, valueId, canLookup, get, load, name, onChange }: {
  label: string; valueId?: string; canLookup: boolean; get: (id: string) => Promise<T>; load: (query: string, page: number) => Promise<WarehousePage<T>>;
  name: (value: T) => string; onChange: (id: string) => void
}) {
  const loader = useCallback(() => valueId && canLookup ? get(valueId) : Promise.resolve(null), [valueId, canLookup, get])
  const result = useWarehouseQuery(loader)
  if (!canLookup || result.state.status !== 'ready') return valueId ? <FilterToken label={label} value={valueId} onClear={() => onChange('')} /> : null
  return <FilterPicker label={label} value={result.state.data} load={load} name={name} onChange={value => onChange(value?.id ?? '')} optional />
}
