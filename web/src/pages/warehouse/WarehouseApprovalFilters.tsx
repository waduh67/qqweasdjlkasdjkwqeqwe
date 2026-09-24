import { useState, type FormEvent } from 'react'
import { APPROVAL_STATES, type WarehouseApproval } from '@/api/warehouse/approvals'
import { POLICY_OPERATIONS, type ApprovalFilter } from '@/api/warehouse/approvalReads'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { approvalOperationLabels } from './approvalPresentation'
import { locationLabel } from './receiptChoices'

const locations = (search: string, page: number) => listLocations({ search, page })
const skus = (search: string, page: number) => listSkus({ search, page })
const statuses: Record<WarehouseApproval['status'], string> = { PENDING: 'Menunggu persetujuan', APPROVED: 'Disetujui', REJECTED: 'Ditolak', REWORK_REQUIRED: 'Perlu diperbaiki', EXPIRED: 'Kedaluwarsa', STALE: 'Sumber berubah' }
export function WarehouseApprovalFilters({ onApply }: { onApply: (filter: ApprovalFilter) => void }) {
  const { can } = useCan()
  const [location, setLocation] = useState<WarehouseLocation | null>(null), [sku, setSku] = useState<WarehouseSku | null>(null)
  const [status, setStatus] = useState<WarehouseApproval['status'] | ''>(''), [operation, setOperation] = useState<ApprovalFilter['operation'] | ''>('')
  const [query, setQuery] = useState(''), [serial, setSerial] = useState(''), [from, setFrom] = useState(''), [until, setUntil] = useState(''), [error, setError] = useState('')
  function apply(event: FormEvent) {
    event.preventDefault()
    try {
      if (!!from !== !!until) throw new Error('Isi tanggal awal dan akhir bersama-sama.')
      const start = from ? new Date(`${from}T00:00:00`) : null, end = until ? new Date(`${until}T00:00:00`) : null
      if (end) end.setDate(end.getDate() + 1)
      if (start && end && (start >= end || end.getTime() - start.getTime() > 366 * 86400000)) throw new Error('Rentang tanggal harus berurutan dan paling lama 366 hari.')
      onApply({ status: status || undefined, operation: operation || undefined, query: query.trim() || undefined, serial: serial.trim() || undefined, locationId: location?.id, skuId: sku?.id, from: start?.toISOString(), until: end?.toISOString() }); setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa filter persetujuan.') }
  }
  return <details className="card"><summary>Filter persetujuan</summary><form className="stack" onSubmit={apply}>
    <TextField label="Cari kode dokumen persetujuan" value={query} maxLength={200} onChange={(_, data) => setQuery(data.value)} />
    <SelectField label="Status persetujuan" value={status} onChange={(_, data) => setStatus(data.value as typeof status)}><option value="">Semua status</option>{APPROVAL_STATES.map(status => <option key={status} value={status}>{statuses[status]}</option>)}</SelectField>
    <SelectField label="Jenis persetujuan" value={operation} onChange={(_, data) => setOperation(data.value as typeof operation)}><option value="">Semua jenis</option>{POLICY_OPERATIONS.map(operation => <option key={operation} value={operation}>{approvalOperationLabels[operation]}</option>)}</SelectField>
    <TextField label="Serial lengkap persetujuan" value={serial} maxLength={128} onChange={(_, data) => setSerial(data.value)} />
    {can('inventory.location.view') && <WarehousePicker label="Lokasi persetujuan" load={locations} value={location} onChange={setLocation} name={locationLabel} optional />}
    {can('inventory.sku.view') && <WarehousePicker label="Barang persetujuan" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} optional />}
    <TextField label="Persetujuan diajukan mulai tanggal" type="date" value={from} onChange={(_, data) => setFrom(data.value)} />
    <TextField label="Persetujuan sampai tanggal" type="date" value={until} onChange={(_, data) => setUntil(data.value)} />
    {error && <p className="error" role="alert">{error}</p>}<Button type="submit" variant="primary">Terapkan filter persetujuan</Button>
  </form></details>
}
