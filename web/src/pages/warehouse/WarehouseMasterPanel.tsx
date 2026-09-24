import { useCallback, useState, type ReactNode } from 'react'
import type { WarehousePage } from '@/api/warehouse/codec'
import type { MasterFilter } from '@/api/warehouse/masters'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { DataTable, type Column } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useToast } from '@/system'

interface Master { id: string; code: string; name: string | null; state: 'ACTIVE' | 'ARCHIVED'; revision: number }
export function WarehouseMasterPanel<T extends Master>({ title, load, archive, canManage, columns = [], editor, emptyHint }: {
  title: string; load: (filter: MasterFilter) => Promise<WarehousePage<T>>; archive: (id: string, revision: number) => WarehouseCommand<T>;
  canManage: boolean; columns?: Column<T>[]; emptyHint: string;
  editor: (row: T | null, readOnly: boolean, onClose: () => void, onSaved: () => void, onReload: () => void) => ReactNode
}) {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE')
  const [editing, setEditing] = useState<T | 'new' | null>(null)
  const [archiving, setArchiving] = useState<{ row: T; command: WarehouseCommand<T> } | null>(null)
  const loader = useCallback(() => load({ search, page, state: status }), [load, search, page, status])
  const result = useWarehouseQuery(loader)
  const toast = useToast()
  function saved() { setEditing(null); setArchiving(null); result.reload(); toast.success(`${title} berhasil disimpan`) }
  return <div className="stack">
    <div className="spread wrap"><h2>{title}</h2>{canManage ? <Button variant="primary" onClick={() => setEditing('new')}>Tambah {title.toLowerCase()}</Button> : <span className="muted">Akses baca saja. Minta izin kelola untuk mengubah data.</span>}</div>
    <div className="row wrap" style={{ alignItems: 'end' }}>
      <TextField label={`Cari ${title.toLowerCase()}`} value={search} maxLength={200} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <SelectField label="Status master" value={status} onChange={(_, data) => { setStatus(data.value as typeof status); setPage(0) }}><option value="ACTIVE">Aktif</option><option value="ARCHIVED">Diarsipkan</option></SelectField>
      <Button onClick={result.reload}>Segarkan</Button>
    </div>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} onRowClick={row => setEditing(row)} empty={<EmptyState title={`Tidak ada ${title.toLowerCase()} yang cocok`} hint={canManage ? emptyHint : 'Tidak ada data sesuai pencarian dan cakupan akses Anda.'} />} columns={[
        { key: 'name', header: 'Nama', cell: row => <span>{row.name ?? row.code}<br /><span className="muted">{row.code}</span></span> },
        ...columns,
        { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.state} /> },
        { key: 'revision', header: 'Revisi', cell: row => row.revision },
      ]} rowActions={row => [
        { key: 'edit', label: canManage && row.state === 'ACTIVE' ? 'Ubah' : 'Lihat', onClick: () => setEditing(row) },
        ...(canManage && row.state === 'ACTIVE' ? [{ key: 'archive', label: 'Arsipkan', onClick: () => setArchiving({ row, command: archive(row.id, row.revision) }) }] : []),
      ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {editing !== null && editor(editing === 'new' ? null : editing, !canManage || (editing !== 'new' && editing.state === 'ARCHIVED'), () => setEditing(null), saved, () => { setEditing(null); result.reload() })}
    {archiving && <WarehouseCommandDialog title={`Arsipkan ${title.toLowerCase()}`} confirmLabel="Arsipkan" command={archiving.command}
      summary={<><p><strong>{archiving.row.name ?? archiving.row.code}</strong> · {archiving.row.code} · Revisi {archiving.row.revision}</p><p>Data yang diarsipkan tidak dapat digunakan untuk transaksi baru. Stok dan referensi yang masih terkait dapat menghalangi pengarsipan.</p></>}
      onDone={saved} onClose={() => setArchiving(null)} onReload={() => { setArchiving(null); result.reload() }} />}
  </div>
}
