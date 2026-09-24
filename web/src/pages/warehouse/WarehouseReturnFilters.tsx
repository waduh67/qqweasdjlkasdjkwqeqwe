import { useState, type FormEvent } from 'react'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { RETURN_ORIGINS, RETURN_STATES, type ReturnFilter } from '@/api/warehouse/returns'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { locationLabel } from './receiptChoices'
import { returnOriginLabels, returnStateLabels } from './returnPresentation'

const skus = (search: string, page: number) => listSkus({ search, page })
const locations = (search: string, page: number) => listLocations({ search, page })
export function WarehouseReturnFilters({ onApply }: { onApply: (filter: ReturnFilter) => void }) {
  const { can } = useCan()
  const [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [search, setSearch] = useState(''), [serial, setSerial] = useState('')
  const [state, setState] = useState<NonNullable<ReturnFilter['state']> | ''>(''), [origin, setOrigin] = useState<NonNullable<ReturnFilter['origin']> | ''>('')
  const [owner, setOwner] = useState<NonNullable<ReturnFilter['owner']> | ''>(''), [from, setFrom] = useState(''), [until, setUntil] = useState(''), [error, setError] = useState('')
  function apply(event: FormEvent) {
    event.preventDefault()
    try {
      if (!!from !== !!until) throw new Error('Isi tanggal awal dan akhir bersama-sama.')
      const start = from ? new Date(`${from}T00:00:00`) : null, end = until ? new Date(`${until}T00:00:00`) : null
      if (end) end.setDate(end.getDate() + 1)
      if (start && end && (start >= end || end.getTime() - start.getTime() > 366 * 86400000)) throw new Error('Rentang tanggal harus berurutan dan paling lama 366 hari.')
      onApply({ query: search.trim() || undefined, serial: serial.trim() || undefined, state: state || undefined, origin: origin || undefined,
        owner: owner || undefined, skuId: sku?.id, locationId: location?.id, from: start?.toISOString(), until: end?.toISOString() })
      setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa filter retur.') }
  }
  return <details className="card"><summary>Filter retur</summary><form className="stack" onSubmit={apply}>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
      <TextField label="Cari kode atau barang retur" value={search} maxLength={200} onChange={(_, data) => setSearch(data.value)} />
      <TextField label="Serial lengkap retur" value={serial} maxLength={128} onChange={(_, data) => setSerial(data.value)} />
      <SelectField label="Status retur" value={state} onChange={(_, data) => setState(data.value as typeof state)}><option value="">Semua status</option>{RETURN_STATES.map(state => <option key={state} value={state}>{returnStateLabels[state]}</option>)}</SelectField>
      <SelectField label="Asal retur" value={origin} onChange={(_, data) => setOrigin(data.value as typeof origin)}><option value="">Semua asal</option>{RETURN_ORIGINS.map(origin => <option key={origin} value={origin}>{returnOriginLabels[origin]}</option>)}</SelectField>
      <SelectField label="Pemilik barang retur" value={owner} onChange={(_, data) => setOwner(data.value as typeof owner)}><option value="">Semua pemilik</option><option value="ISP">Milik ISP</option><option value="CUSTOMER">Milik pelanggan</option><option value="UNKNOWN">Belum diketahui</option></SelectField>
      {can('inventory.sku.view') && <WarehousePicker label="Barang retur" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} optional />}
      {can('inventory.location.view') && <WarehousePicker label="Lokasi retur" load={locations} value={location} onChange={setLocation} name={locationLabel} optional />}
      <TextField label="Dibuat mulai tanggal" type="date" value={from} onChange={(_, data) => setFrom(data.value)} />
      <TextField label="Sampai tanggal" type="date" value={until} onChange={(_, data) => setUntil(data.value)} />
    </div>{error && <p role="alert" className="error">{error}</p>}<Button type="submit" variant="primary">Terapkan filter retur</Button>
  </form></details>
}
