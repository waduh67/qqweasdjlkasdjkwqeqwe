import { useCallback, useState } from 'react'
import { DELEGATION_STATES, listDelegations, revokeDelegation, type WarehouseDelegation } from '@/api/warehouse/settings'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField } from '@/components/atoms'
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
  return <div className="stack"><h2>Delegasi pemeriksa</h2><p>Hanya delegasi pada lokasi yang dapat Anda akses saat ini.</p>
    <SelectField label="Status delegasi" value={state} onChange={(_, value) => { setState(value.value as typeof state); setPage(0) }}><option value="">Semua status</option>{DELEGATION_STATES.map(value => <option key={value} value={value}>{labels[value]}</option>)}</SelectField>
    <div className="row wrap"><Button onClick={refresh}>Muat ulang delegasi</Button>{can('inventory.approval.manage') && <Button onClick={() => setCreating(true)}>Tambah delegasi</Button>}</div>
    {creating && can('inventory.approval.manage') && <WarehouseDelegationForm onDone={refresh} onClose={() => setCreating(false)} />}
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.delegation.id} empty={<EmptyState title="Tidak ada delegasi sesuai filter" hint="Pemeriksa dapat bertindak sesuai kebijakan dan kewenangannya sendiri." />} columns={[
      { key: 'people', header: 'Pemeriksa asal → pengganti', cell: row => <>{row.approver?.name ?? 'Nama tidak tersedia'} → {row.delegate?.name ?? 'Nama tidak tersedia'}<p>{row.delegation.sourceRoleId ? `Melalui role ${row.sourceRole?.name ?? 'yang namanya tidak tersedia'}` : 'Penunjukan pengguna langsung'}</p></> },
      { key: 'scope', header: 'Lokasi / persetujuan', cell: row => <>{locationLabel(row.location)}<br />{approvalOperationLabels[row.delegation.operation]}</> },
      { key: 'until', header: 'Berlaku sampai', cell: row => <WarehouseTime value={row.delegation.validUntil} /> },
      { key: 'state', header: 'Status / revisi', cell: row => <>{labels[row.state]} · Revisi {row.delegation.revision}{row.delegation.revokedAt && <p>Dicabut <WarehouseTime value={row.delegation.revokedAt} /></p>}</> },
      { key: 'action', header: 'Tindakan', cell: row => can('inventory.approval.manage') && !row.delegation.revokedAt ? <Button onClick={() => setOperation({ command: revokeDelegation(row.delegation), description: `${row.approver?.name ?? 'Pemeriksa asal'} → ${row.delegate?.name ?? 'Penerima delegasi'} · ${locationLabel(row.location)} · Revisi ${row.delegation.revision}` })}>Cabut delegasi</Button> : 'Baca saja' },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    {operation && <WarehouseCommandDialog title="Konfirmasi pencabutan delegasi" confirmLabel="Konfirmasi cabut" command={operation.command} onDone={refresh} onReload={refresh} onClose={() => setOperation(null)} summary={<><p>{operation.description}</p><p>Kewenangan dari delegasi ini dihentikan. Keputusan yang sudah tercatat tetap menjadi riwayat.</p></>} />}
  </div>
}
