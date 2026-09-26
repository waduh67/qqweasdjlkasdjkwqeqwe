import { useState, type FormEvent } from 'react'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { TRANSFER_STATES, type TransferFilter, type TransferState } from '@/api/warehouse/transfers'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { locationLabel } from './receiptChoices'

const skus = (search: string, page: number) => listSkus({ search, page })
const locations = (search: string, page: number) => listLocations({ search, page })
const stateLabels: Record<TransferState, string> = { EXPIRED: 'Kedaluwarsa', DRAFT: 'Draf', DISPATCHED: 'Dikirim', PART_RECEIVED: 'Sebagian diterima', RECEIVED: 'Diterima', DISCREPANCY: 'Penanganan selisih' }
export function WarehouseTransferFilters({ onApply }: { onApply: (filter: TransferFilter) => void }) {
  const { can } = useCan()
  const [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [search, setSearch] = useState(''), [serial, setSerial] = useState(''), [state, setState] = useState<TransferState | ''>('')
  const [from, setFrom] = useState(''), [until, setUntil] = useState(''), [error, setError] = useState('')
  function apply(event: FormEvent) {
    event.preventDefault()
    try {
      if (!!from !== !!until) throw new Error('Isi tanggal awal dan akhir bersama-sama.')
      const start = from ? new Date(`${from}T00:00:00`) : null, end = until ? new Date(`${until}T00:00:00`) : null
      if (end) end.setDate(end.getDate() + 1)
      if (start && end && (start >= end || end.getTime() - start.getTime() > 366 * 86400000)) throw new Error('Rentang tanggal harus berurutan dan paling lama 366 hari.')
      onApply({ query: search.trim() || undefined, serial: serial.trim() || undefined, state: state || undefined,
        skuId: sku?.id, locationId: location?.id, from: start?.toISOString(), until: end?.toISOString() })
      setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa filter transfer.') }
  }
  return <details className="card"><summary>Filter transfer</summary><form className="stack" onSubmit={apply}>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
      <TextField label="Cari kode transfer" value={search} maxLength={200} onChange={(_, data) => setSearch(data.value)} />
      <TextField label="Serial lengkap transfer" value={serial} maxLength={128} onChange={(_, data) => setSerial(data.value)} />
      <SelectField label="Status transfer" value={state} onChange={(_, data) => setState(data.value as typeof state)}><option value="">Semua status</option>{TRANSFER_STATES.map(state => <option key={state} value={state}>{stateLabels[state]}</option>)}</SelectField>
      {can('inventory.sku.view') && <WarehousePicker label="Barang pada transfer" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} optional />}
      {can('inventory.location.view') && <WarehousePicker label="Lokasi pada transfer" load={locations} value={location} onChange={setLocation} name={locationLabel} optional />}
      <TextField label="Transfer dibuat mulai tanggal" type="date" value={from} onChange={(_, data) => setFrom(data.value)} />
      <TextField label="Transfer sampai tanggal" type="date" value={until} onChange={(_, data) => setUntil(data.value)} />
    </div><p className="muted">Lokasi mencakup asal, transit, tujuan, dan tujuan penanganan selisih. SKU dan serial harus cocok pada barang yang sama.</p>
    {error && <p role="alert" className="error">{error}</p>}<Button type="submit" variant="primary">Terapkan filter transfer</Button>
  </form></details>
}
