import { useState } from 'react'
import { APPROVAL_STATES, type WarehouseApproval } from '@/api/warehouse/approvals'
import { POLICY_OPERATIONS, type ApprovalFilter } from '@/api/warehouse/approvalReads'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import { useCan } from '@/auth/useCan'
import { FilterBar, FilterDateRange, FilterPicker, FilterSearch, FilterSelect, FilterText } from '@/components/organisms/ResourceFilters'
import { useAutoFilter } from '@/hooks/useAutoFilter'
import { approvalOperationLabels } from './approvalPresentation'
import { locationLabel } from './receiptChoices'

const locations = (search: string, page: number) => listLocations({ search, page })
const skus = (search: string, page: number) => listSkus({ search, page })
const statuses: Record<WarehouseApproval['status'], string> = { PENDING: 'Menunggu persetujuan', APPROVED: 'Disetujui', REJECTED: 'Ditolak', REWORK_REQUIRED: 'Perlu diperbaiki', EXPIRED: 'Kedaluwarsa', STALE: 'Sumber berubah' }
export function WarehouseApprovalFilters({ onApply }: { onApply: (filter: ApprovalFilter) => void }) {
  const { can } = useCan()
  const [location, setLocation] = useState<WarehouseLocation | null>(null), [sku, setSku] = useState<WarehouseSku | null>(null)
  const [status, setStatus] = useState<WarehouseApproval['status'] | ''>(''), [operation, setOperation] = useState<NonNullable<ApprovalFilter['operation']> | ''>('')
  const [query, setQuery] = useState(''), [serial, setSerial] = useState(''), [from, setFrom] = useState(''), [until, setUntil] = useState('')
  useAutoFilter({ status: status || undefined, operation: operation || undefined, query: query.trim() || undefined, serial: serial.trim() || undefined, locationId: location?.id, skuId: sku?.id, from: from || undefined, until: until || undefined }, onApply)
  return <FilterBar search={<FilterSearch label="Cari kode dokumen persetujuan" value={query} maxLength={200} onChange={setQuery} />}>
    <FilterSelect caption="Status" label="Status persetujuan" value={status} onChange={value => setStatus(value as typeof status)}><option value="">Semua status</option>{APPROVAL_STATES.map(status => <option key={status} value={status}>{statuses[status]}</option>)}</FilterSelect>
    {can('inventory.location.view') && <FilterPicker caption="Lokasi" label="Lokasi persetujuan" load={locations} value={location} onChange={setLocation} name={locationLabel} placeholder="Semua" optional />}
    {can('inventory.sku.view') && <FilterPicker caption="Barang" label="Barang persetujuan" load={skus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}`} placeholder="Semua" optional />}
    <FilterSelect secondary caption="Jenis" label="Jenis persetujuan" value={operation} onChange={value => setOperation(value as typeof operation)}><option value="">Semua jenis</option>{POLICY_OPERATIONS.map(operation => <option key={operation} value={operation}>{approvalOperationLabels[operation]}</option>)}</FilterSelect>
    <FilterText secondary caption="Serial" label="Serial lengkap persetujuan" value={serial} maxLength={128} onChange={setSerial} />
    <FilterDateRange secondary label="Tanggal" fromLabel="Persetujuan diajukan mulai tanggal" untilLabel="Persetujuan sampai tanggal" from={from || undefined} until={until || undefined} onChange={(from, until) => { setFrom(from ?? ''); setUntil(until ?? '') }} />
  </FilterBar>
}
