import { useState } from 'react'
import { COUNT_STATES, type CountFilter, type WarehouseCount } from '@/api/warehouse/counts'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { useCan } from '@/auth/useCan'
import { FilterBar, FilterDateRange, FilterPicker, FilterSearch, FilterSelect, FilterText } from '@/components/organisms/ResourceFilters'
import { useAutoFilter } from '@/hooks/useAutoFilter'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import { locationLabel } from './receiptChoices'
import { countStateLabels } from './countPresentation'

const locations = (search: string, page: number) => listLocations({ search, page })
const skus = (search: string, page: number) => listSkus({ search, page })
export function WarehouseCountFilters({ onApply }: { onApply: (filter: CountFilter) => void }) {
  const { can } = useCan()
  const [location, setLocation] = useState<WarehouseLocation | null>(null), [sku, setSku] = useState<WarehouseSku | null>(null)
  const [state, setState] = useState<WarehouseCount['state'] | ''>(''), [query, setQuery] = useState(''), [serial, setSerial] = useState('')
  const [from, setFrom] = useState(''), [until, setUntil] = useState('')
  useAutoFilter({ locationId: location?.id, skuId: sku?.id, state: state || undefined, query: query.trim() || undefined, serial: serial.trim() || undefined, from: from || undefined, until: until || undefined }, onApply)
  return <FilterBar search={<FilterSearch label="Cari kode stock opname" value={query} maxLength={200} onChange={setQuery} />}>
    <FilterSelect caption="Status" label="Status stock opname" value={state} onChange={value => setState(value as typeof state)}><option value="">Semua status</option>{COUNT_STATES.map(state => <option key={state} value={state}>{countStateLabels[state]}</option>)}</FilterSelect>
    {can('inventory.location.view') && <FilterPicker caption="Lokasi" label="Lokasi pada stock opname" load={locations} value={location} onChange={setLocation} name={locationLabel} placeholder="Semua" optional />}
    {can('inventory.sku.view') && <FilterPicker caption="Barang" label="Barang pada stock opname" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} placeholder="Semua" optional />}
    <FilterText secondary caption="Serial" label="Serial lengkap stock opname" value={serial} maxLength={128} onChange={setSerial} />
    <FilterDateRange secondary label="Tanggal" fromLabel="Stock opname dibuat mulai tanggal" untilLabel="Stock opname sampai tanggal" from={from || undefined} until={until || undefined} onChange={(from, until) => { setFrom(from ?? ''); setUntil(until ?? '') }} />
  </FilterBar>
}
