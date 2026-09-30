import { useState } from 'react'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { RETURN_ORIGINS, RETURN_STATES, type ReturnFilter } from '@/api/warehouse/returns'
import { useCan } from '@/auth/useCan'
import { FilterBar, FilterDateRange, FilterPicker, FilterSearch, FilterSelect, FilterText } from '@/components/organisms/ResourceFilters'
import { useAutoFilter } from '@/hooks/useAutoFilter'
import { locationLabel } from './receiptChoices'
import { returnOriginLabels, returnStateLabels } from './returnPresentation'

const skus = (search: string, page: number) => listSkus({ search, page })
const locations = (search: string, page: number) => listLocations({ search, page })
export function WarehouseReturnFilters({ onApply }: { onApply: (filter: ReturnFilter) => void }) {
  const { can } = useCan()
  const [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [search, setSearch] = useState(''), [serial, setSerial] = useState('')
  const [state, setState] = useState<NonNullable<ReturnFilter['state']> | ''>(''), [origin, setOrigin] = useState<NonNullable<ReturnFilter['origin']> | ''>('')
  const [owner, setOwner] = useState<NonNullable<ReturnFilter['owner']> | ''>(''), [from, setFrom] = useState(''), [until, setUntil] = useState('')
  useAutoFilter({ query: search.trim() || undefined, serial: serial.trim() || undefined, state: state || undefined, origin: origin || undefined,
        owner: owner || undefined, skuId: sku?.id, locationId: location?.id, from: from || undefined, until: until || undefined }, onApply)
  return <FilterBar search={<FilterSearch label="Cari kode atau barang retur" value={search} maxLength={200} onChange={setSearch} />}>
    <FilterSelect caption="Status" label="Status retur" value={state} onChange={value => setState(value as typeof state)}><option value="">Semua status</option>{RETURN_STATES.map(state => <option key={state} value={state}>{returnStateLabels[state]}</option>)}</FilterSelect>
    <FilterSelect caption="Asal" label="Asal retur" value={origin} onChange={value => setOrigin(value as typeof origin)}><option value="">Semua asal</option>{RETURN_ORIGINS.map(origin => <option key={origin} value={origin}>{returnOriginLabels[origin]}</option>)}</FilterSelect>
    {can('inventory.sku.view') && <FilterPicker caption="Barang" label="Barang retur" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} placeholder="Semua" optional />}
    {can('inventory.location.view') && <FilterPicker caption="Lokasi" label="Lokasi retur" load={locations} value={location} onChange={setLocation} name={locationLabel} placeholder="Semua" optional />}
    <FilterText secondary caption="Serial" label="Serial lengkap retur" value={serial} maxLength={128} onChange={setSerial} />
    <FilterSelect secondary caption="Kepemilikan" label="Pemilik barang retur" value={owner} onChange={value => setOwner(value as typeof owner)}><option value="">Semua pemilik</option><option value="ISP">Milik ISP</option><option value="CUSTOMER">Milik pelanggan</option><option value="UNKNOWN">Belum diketahui</option></FilterSelect>
    <FilterDateRange secondary label="Tanggal" fromLabel="Dibuat mulai tanggal" untilLabel="Sampai tanggal" from={from || undefined} until={until || undefined} onChange={(from, until) => { setFrom(from ?? ''); setUntil(until ?? '') }} />
  </FilterBar>
}
