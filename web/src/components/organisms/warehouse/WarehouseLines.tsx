import type { ReactNode } from 'react'
import type { BaseUnit } from '@/api/warehouse/quantity'
import { DataTable, type Column } from '@/components/organisms/DataTable'
import { WarehouseQuantity } from './WarehouseQuantity'
import { WarehouseStatus } from './WarehouseStatus'

export interface WarehouseLine { id: string; name: string; code?: string; serial?: string | null; locationName?: string; quantityBase: string; baseUnit: BaseUnit; status?: string }
export function WarehouseLines<T extends WarehouseLine>({ rows, extra = [], onOpen, empty }: { rows: T[]; extra?: Column<T>[]; onOpen?: (line: T) => void; empty?: ReactNode }) {
  return <DataTable rows={rows} rowKey={row => row.id} onRowClick={onOpen} empty={empty} columns={[
    { key: 'name', header: 'Barang', cell: row => <div className="stack" style={{ gap: '0.25rem', overflowWrap: 'anywhere' }}><strong>{row.name}</strong><span className="muted">{[row.code, row.serial].filter(Boolean).join(' · ')}</span></div> },
    { key: 'quantity', header: 'Jumlah', align: 'right', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
    { key: 'location', header: 'Lokasi', cell: row => row.locationName ?? '—' },
    { key: 'status', header: 'Status', cell: row => row.status ? <WarehouseStatus status={row.status} /> : '—' },
    ...extra,
  ]} />
}

export interface WarehouseHistoryEntry { id: string; occurredAt: string; revision: number; label: string; actorName?: string; detail?: string }
export function WarehouseTime({ value }: { value: string }) {
  return <time dateTime={value} title={value}>{new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))}</time>
}
export function WarehouseHistory({ entries }: { entries: WarehouseHistoryEntry[] }) {
  return <ol className="stack" aria-label="Riwayat dokumen">{entries.map(entry => <li key={entry.id}>
    <strong>{entry.label}</strong><p><WarehouseTime value={entry.occurredAt} /> · Revisi {entry.revision}{entry.actorName && ` · ${entry.actorName}`}</p>
    {entry.detail && <p className="muted">{entry.detail}</p>}
  </li>)}</ol>
}
