import { useState, type FormEvent } from 'react'
import { COUNT_STATES, type CountFilter, type WarehouseCount } from '@/api/warehouse/counts'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import { locationLabel } from './receiptChoices'
import { countStateLabels } from './countPresentation'

const locations = (search: string, page: number) => listLocations({ search, page })
const skus = (search: string, page: number) => listSkus({ search, page })
export function WarehouseCountFilters({ onApply }: { onApply: (filter: CountFilter) => void }) {
  const { can } = useCan()
  const [location, setLocation] = useState<WarehouseLocation | null>(null), [sku, setSku] = useState<WarehouseSku | null>(null)
  const [state, setState] = useState<WarehouseCount['state'] | ''>(''), [query, setQuery] = useState(''), [serial, setSerial] = useState('')
  const [from, setFrom] = useState(''), [until, setUntil] = useState(''), [error, setError] = useState('')
  function apply(event: FormEvent) {
    event.preventDefault()
    try {
      if (!!from !== !!until) throw new Error('Isi tanggal awal dan akhir bersama-sama.')
      const start = from ? new Date(`${from}T00:00:00`) : null, end = until ? new Date(`${until}T00:00:00`) : null
      if (end) end.setDate(end.getDate() + 1)
      if (start && end && (start >= end || end.getTime() - start.getTime() > 366 * 86400000)) throw new Error('Rentang tanggal harus berurutan dan paling lama 366 hari.')
      onApply({ locationId: location?.id, skuId: sku?.id, state: state || undefined, query: query.trim() || undefined, serial: serial.trim() || undefined, from: start?.toISOString(), until: end?.toISOString() }); setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa filter stock opname.') }
  }
  return <details className="card"><summary>Filter stock opname</summary><form className="stack" onSubmit={apply}>
    <TextField label="Cari kode stock opname" value={query} maxLength={200} onChange={(_, data) => setQuery(data.value)} />
    <SelectField label="Status stock opname" value={state} onChange={(_, data) => setState(data.value as typeof state)}><option value="">Semua status</option>{COUNT_STATES.map(state => <option key={state} value={state}>{countStateLabels[state]}</option>)}</SelectField>
    <TextField label="Serial lengkap stock opname" value={serial} maxLength={128} onChange={(_, data) => setSerial(data.value)} />
    {can('inventory.location.view') && <WarehousePicker label="Lokasi pada stock opname" load={locations} value={location} onChange={setLocation} name={locationLabel} optional />}
    {can('inventory.sku.view') && <WarehousePicker label="Barang pada stock opname" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} optional />}
    <TextField label="Stock opname dibuat mulai tanggal" type="date" value={from} onChange={(_, data) => setFrom(data.value)} />
    <TextField label="Stock opname sampai tanggal" type="date" value={until} onChange={(_, data) => setUntil(data.value)} />
    {error && <p className="error" role="alert">{error}</p>}<Button type="submit" variant="primary">Terapkan filter stock opname</Button>
  </form></details>
}
