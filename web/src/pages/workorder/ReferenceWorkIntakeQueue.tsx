import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listWorkIntake, WORK_SOURCE_LABELS } from '@/api/warehouse/referenceWorkIntake'
import { Button, EmptyState, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useAuth } from '@/auth/useAuth'

export function ReferenceWorkIntakeQueue() {
  const { user } = useAuth(), [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const load = useCallback(() => listWorkIntake(search.trim(), page), [search, page])
  const result = useWarehouseQuery(load, `${user?.id}:${user?.tenantId}`)
  return <section className="stack" aria-label="Pekerjaan belum ditugaskan">
    <p>PSB, keluhan pelanggan dan pemeliharaan otomatis menunggu penugasan di sini. Buka pekerjaan untuk memilih jenis dan satu teknisi NE atau FO.</p>
    <div className="spread wrap"><TextField label="Cari antrean" value={search} placeholder="Nomor atau judul pekerjaan" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <Button onClick={result.reload}>Muat ulang antrean</Button></div>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Semua pekerjaan sudah ditugaskan" hint="Pekerjaan baru dari PSB, helpdesk atau pemeliharaan akan muncul di sini." />} columns={[
        { key: 'work', header: 'Pekerjaan', cell: row => <Link to={'/work-orders/' + row.id}>{row.code} · {row.title}</Link> },
        { key: 'source', header: 'Asal pekerjaan', cell: row => WORK_SOURCE_LABELS[row.source] },
        { key: 'scheduled', header: 'Jadwal', cell: row => row.scheduledAt ? <WarehouseTime value={row.scheduledAt} /> : 'Belum dijadwalkan' },
        { key: 'created', header: 'Dibuat', cell: row => <WarehouseTime value={row.createdAt} /> },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
