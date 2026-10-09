import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getReferenceCount, listReferenceCounts } from '@/api/warehouse/referenceCounts'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceCountEditor } from './ReferenceCountEditor'

export function ReferenceCountsPage() {
  const [params, setParams] = useSearchParams(), id = params.get('id'), { can } = useCan(), [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const load = useCallback(() => listReferenceCounts(page), [page]), result = useWarehouseQuery(load)
  return <div className="stack"><PageHeader title="Stock Opname" subtitle="Bandingkan saldo buku dengan hitungan fisik dan simpan penyesuaian yang dapat ditelusuri."
    actions={!id && can('warehouse.count.manage') ? <Button variant="primary" onClick={() => setCreating(true)}>Opname baru</Button> : undefined} />
    {id ? <ReferenceCountDetail key={id} id={id} onBack={() => setParams({})} /> : <>
      <Button onClick={result.reload}>Muat ulang opname</Button>
      <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Belum ada opname" hint="Pilih barang dan lokasi, muat saldo buku, lalu catat hasil hitungan fisik." />} columns={[
          { key: 'material', header: 'Material', cell: row => row.snapshot.skuName, description: row => row.snapshot.locationName },
          { key: 'book', header: 'Saldo buku', cell: row => <WarehouseQuantity value={row.snapshot.bookBase} unit={row.snapshot.baseUnit} /> },
          { key: 'physical', header: 'Hasil fisik', cell: row => <WarehouseQuantity value={row.physicalBase} unit={row.snapshot.baseUnit} /> },
          { key: 'difference', header: 'Selisih', cell: row => <WarehouseQuantity value={row.differenceBase} unit={row.snapshot.baseUnit} /> },
          { key: 'actor', header: 'Dicatat oleh', cell: row => row.actorName },
          { key: 'time', header: 'Waktu', cell: row => new Date(row.recordedAt).toLocaleString('id-ID') },
          { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => setParams({ id: row.id })}>Lihat opname {row.id.slice(-8)}</Button> },
        ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    </>}
    {creating && can('warehouse.count.manage') && <ReferenceCountEditor onClose={() => setCreating(false)} onSaved={row => { setCreating(false); result.reload(); setParams({ id: row.id }) }} />}
  </div>
}
function ReferenceCountDetail({ id, onBack }: { readonly id: string; readonly onBack: () => void }) {
  const load = useCallback(() => getReferenceCount(id), [id]), result = useWarehouseQuery(load), [page, setPage] = useState(0)
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang detail</Button></div>
    <WarehouseState {...result}>{row => <>
      <section className="card stack" aria-label="Audit opname"><h2>Opname {row.id.slice(-8)}</h2><p><strong>{row.snapshot.skuName}</strong> · {row.snapshot.locationName}</p>
        <p>Saldo buku: <WarehouseQuantity value={row.snapshot.bookBase} unit={row.snapshot.baseUnit} /></p><p>Hasil fisik: <WarehouseQuantity value={row.physicalBase} unit={row.snapshot.baseUnit} /></p>
        <p>Selisih: <WarehouseQuantity value={row.differenceBase} unit={row.snapshot.baseUnit} /></p><p>{row.reason}</p>
        <p>{row.actorName} · {new Date(row.recordedAt).toLocaleString('id-ID')}</p><p className="muted">Audit tersimpan. {row.movementIds.length} dokumen penyesuaian stok dicatat. Audit ini tidak dapat diubah atau dihapus.</p>
      </section>
      {row.snapshot.tracking === 'SERIAL' && <section className="stack" aria-label="Serial hasil fisik"><h2>Serial hasil fisik</h2>
        <DataTable presentation="warehouse" rows={row.serials.slice(page * 25, (page + 1) * 25)} rowKey={item => item.serial} empty={<EmptyState title="Tidak ditemukan perangkat fisik" />} columns={[
          { key: 'serial', header: 'Serial', cell: item => item.serial }, { key: 'mac', header: 'MAC', cell: item => item.mac ?? 'Tidak dicatat' },
        ]} /><WarehousePagination page={page} size={25} total={row.serials.length} onChange={setPage} /></section>}
    </>}</WarehouseState>
  </>
}
