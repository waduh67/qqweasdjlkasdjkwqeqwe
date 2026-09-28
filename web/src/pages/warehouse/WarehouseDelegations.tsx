import { FilterBar, FilterSelect } from '@/components/organisms/ResourceFilters'
import { WarehouseListActions } from '@/components/organisms/warehouse/WarehouseListActions'
import { useCallback, useState } from 'react'
import { DELEGATION_STATES, listDelegations, revokeDelegation, type WarehouseDelegation } from '@/api/warehouse/settings'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseDelegationForm } from './WarehouseDelegationForm'
import { approvalOperationLabels } from './approvalPresentation'
import { locationLabel } from './receiptChoices'

const labels = { ACTIVE: 'Aktif', EXPIRED: 'Kedaluwarsa', REVOKED: 'Dicabut' }
export function WarehouseDelegations() {
  const [open, setOpen] = useState(false)
  return <section className="card stack" aria-label="Delegasi pemeriksa"><Button onClick={() => setOpen(value => !value)}>{open ? 'Tutup delegasi pemeriksa' : 'Kelola delegasi pemeriksa'}</Button>{open && <Delegations />}</section>
}
function Delegations() {
  const { can } = useCan(), [state, setState] = useState<typeof DELEGATION_STATES[number] | ''>('ACTIVE'), [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const [operation, setOperation] = useState<{ command: WarehouseCommand<WarehouseDelegation>; description: string } | null>(null)
  const loader = useCallback(() => listDelegations({ page, state: state || undefined }), [page, state]), result = useWarehouseQuery(loader)
  function refresh() { setCreating(false); setOperation(null); result.reload() }
  return <div className="stack"><h2>Delegasi pemeriksa</h2>
    <div className="resource-list-controls"><WarehouseListActions onRefresh={refresh} create={can('inventory.approval.manage') ? { label: 'Tambah delegasi', onClick: () => setCreating(true) } : undefined} />
    <FilterBar>
    <FilterSelect caption="Status" label="Status delegasi" value={state} onChange={value => { setState(value as typeof state); setPage(0) }}><option value="">Semua status</option>{DELEGATION_STATES.map(value => <option key={value} value={value}>{labels[value]}</option>)}</FilterSelect></FilterBar></div>

    {creating && can('inventory.approval.manage') && <WarehouseDelegationForm onDone={refresh} onClose={() => setCreating(false)} />}
    <WarehouseState {...result}>{data => <><DataTable rowActions={row => can('inventory.approval.manage') && !row.delegation.revokedAt ? [{ key: 'action', label: <>Cabut delegasi</>, onClick: () => setOperation({ command: revokeDelegation(row.delegation), description: `${row.approver?.name ?? 'Pemeriksa asal'} → ${row.delegate?.name ?? 'Penerima delegasi'} · ${locationLabel(row.location)} · Revisi ${row.delegation.revision}` }) }] : []} presentation="warehouse" rows={data.items} rowKey={row => row.delegation.id} empty={<EmptyState title="Tidak ada delegasi sesuai filter" hint="Pemeriksa dapat bertindak sesuai kebijakan dan kewenangannya sendiri." />} columns={[
      { key: 'people', header: 'Pemeriksa asal → pengganti', cell: row => <>{row.approver?.name ?? 'Nama tidak tersedia'} → {row.delegate?.name ?? 'Nama tidak tersedia'}<span>{' · '}{row.delegation.sourceRoleId ? `Melalui role ${row.sourceRole?.name ?? 'yang namanya tidak tersedia'}` : 'Penunjukan pengguna langsung'}</span></> },
      { key: 'scope', header: 'Lokasi / persetujuan', cell: row => <>{locationLabel(row.location)}{' · '}{approvalOperationLabels[row.delegation.operation]}</> },
      { key: 'until', header: 'Berlaku sampai', cell: row => <WarehouseTime value={row.delegation.validUntil} /> },
      { key: 'state', header: 'Status / revisi', cell: row => <>{labels[row.state]} · Revisi {row.delegation.revision}{row.delegation.revokedAt && <span>{' · '}Dicabut <WarehouseTime value={row.delegation.revokedAt} /></span>}</> },

    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    {operation && <WarehouseCommandDialog title="Konfirmasi pencabutan delegasi" confirmLabel="Konfirmasi cabut" command={operation.command} onDone={refresh} onReload={refresh} onClose={() => setOperation(null)} summary={<><p>{operation.description}</p><p>Kewenangan dari delegasi ini dihentikan. Keputusan yang sudah tercatat tetap menjadi riwayat.</p></>} />}
  </div>
}
