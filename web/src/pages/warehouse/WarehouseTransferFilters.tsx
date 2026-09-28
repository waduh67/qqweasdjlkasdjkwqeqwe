import { useState } from 'react'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { TRANSFER_STATES, type TransferFilter, type TransferState } from '@/api/warehouse/transfers'
import { useCan } from '@/auth/useCan'
import { FilterBar, FilterDateRange, FilterPicker, FilterSearch, FilterSelect, FilterText } from '@/components/organisms/ResourceFilters'
import { useAutoFilter } from '@/hooks/useAutoFilter'
import { locationLabel } from './receiptChoices'

const skus = (search: string, page: number) => listSkus({ search, page })
const locations = (search: string, page: number) => listLocations({ search, page })
const stateLabels: Record<TransferState, string> = { EXPIRED: 'Kedaluwarsa', DRAFT: 'Draf', DISPATCHED: 'Dikirim', PART_RECEIVED: 'Sebagian diterima', RECEIVED: 'Diterima', DISCREPANCY: 'Penanganan selisih' }
export function WarehouseTransferFilters({ onApply }: { onApply: (filter: TransferFilter) => void }) {
  const { can } = useCan()
  const [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [search, setSearch] = useState(''), [serial, setSerial] = useState(''), [state, setState] = useState<TransferState | ''>('')
  const [from, setFrom] = useState(''), [until, setUntil] = useState('')
  useAutoFilter({ query: search.trim() || undefined, serial: serial.trim() || undefined, state: state || undefined,
        skuId: sku?.id, locationId: location?.id, from: from || undefined, until: until || undefined }, onApply)
  return <FilterBar search={<FilterSearch label="Cari kode transfer" value={search} maxLength={200} onChange={setSearch} />}>
    <FilterSelect caption="Status" label="Status transfer" value={state} onChange={value => setState(value as typeof state)}><option value="">Semua status</option>{TRANSFER_STATES.map(state => <option key={state} value={state}>{stateLabels[state]}</option>)}</FilterSelect>
    {can('inventory.sku.view') && <FilterPicker caption="Barang" label="Barang pada transfer" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} placeholder="Semua" optional />}
    {can('inventory.location.view') && <FilterPicker caption="Lokasi" label="Lokasi pada transfer" load={locations} value={location} onChange={setLocation} name={locationLabel} placeholder="Semua" optional />}
    <FilterText secondary caption="Serial" label="Serial lengkap transfer" value={serial} maxLength={128} onChange={setSerial} />
    <FilterDateRange secondary label="Tanggal" fromLabel="Transfer dibuat mulai tanggal" untilLabel="Transfer sampai tanggal" from={from || undefined} until={until || undefined} onChange={(from, until) => { setFrom(from ?? ''); setUntil(until ?? '') }} />
  </FilterBar>
}
