import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getReferenceMovement, listReferenceMovements, referenceMovementLines, type ReferenceMovementKind } from '@/api/warehouse/referenceMovements'
import { displayUnit, formatBaseQuantity } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceReceiptEditor } from './ReferenceReceiptEditor'
import { ReferenceTransferEditor } from './ReferenceTransferEditor'

export function ReferenceReceiptsPage() { return <ReferenceMovementsPage kind="RECEIPT" /> }
export function ReferenceTransfersPage() { return <ReferenceMovementsPage kind="TRANSFER" /> }
function ReferenceMovementsPage({ kind }: { readonly kind: ReferenceMovementKind }) {
  const [params, setParams] = useSearchParams(), id = params.get('id'), { can } = useCan()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const load = useCallback(() => listReferenceMovements(kind, search.trim(), page), [kind, search, page]), result = useWarehouseQuery(load)
  const title = kind === 'RECEIPT' ? 'Penerimaan' : 'Transfer', Editor = kind === 'RECEIPT' ? ReferenceReceiptEditor : ReferenceTransferEditor
  return <div className="stack"><PageHeader title={title} subtitle={kind === 'RECEIPT' ? 'Barang datang langsung menjadi stok tersedia di gudang tujuan.' : 'Pindahkan stok tersedia antar gudang dengan jumlah yang tepat.'}
    actions={can('warehouse.stock.manage') && !id ? <Button variant="primary" onClick={() => setCreating(true)}>{title} baru</Button> : undefined} />
    {id ? <ReferenceMovementDetail key={id} id={id} kind={kind} onBack={() => setParams({})} /> : <>
      <TextField label={'Cari ' + title.toLowerCase()} value={search} maxLength={200} placeholder="Kode, referensi, atau catatan" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <Button onClick={result.reload}>Muat ulang {title.toLowerCase()}</Button>
      <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title={'Belum ada ' + title.toLowerCase()} hint={kind === 'RECEIPT' ? 'Catat barang datang untuk menambah stok gudang.' : 'Transfer tersimpan akan muncul dalam akses gudang Anda.'} />}
        columns={[
          { key: 'code', header: 'Dokumen', cell: row => row.code, description: row => row.reference },
          { key: 'route', header: 'Gudang', cell: row => row.sourceWarehouseName ? row.sourceWarehouseName + ' → ' + row.warehouseName : row.warehouseName },
          { key: 'state', header: 'Status', cell: () => kind === 'RECEIPT' ? 'Stok diterima' : 'Stok dipindahkan' },
          { key: 'actor', header: 'Dicatat oleh', cell: row => row.actorName },
          { key: 'time', header: 'Waktu', cell: row => new Date(row.recordedAt).toLocaleString('id-ID') },
          { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => setParams({ id: row.id })}>Lihat {row.code}</Button> },
        ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    </>}
    {creating && <Editor onClose={() => setCreating(false)} onSaved={row => { setCreating(false); result.reload(); setParams({ id: row.id }) }} />}
  </div>
}

function ReferenceMovementDetail({ id, kind, onBack }: { readonly id: string; readonly kind: ReferenceMovementKind; readonly onBack: () => void }) {
  const load = useCallback(() => getReferenceMovement(id), [id]), result = useWarehouseQuery(load)
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang detail</Button></div>
    <WarehouseState {...result}>{document => document.kind !== kind ? <p role="alert">Dokumen ini bukan {kind === 'RECEIPT' ? 'penerimaan' : 'transfer'}. Kembali ke daftar yang sesuai.</p> : <>
      <section className="card stack" aria-label="Dokumen tersimpan"><h2>{document.code}</h2>
        <p>{document.sourceWarehouseName && document.sourceWarehouseName + ' → '}{document.warehouseName} · Revisi {document.revision}</p>
        <p>{document.actorName} · {new Date(document.recordedAt).toLocaleString('id-ID')}</p>
        {kind === 'RECEIPT' && <p>Pemasok: {document.supplierName ?? 'Tidak diisi'} · Referensi: {document.reference ?? 'Tidak diisi'}</p>}
        {document.notes && <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{document.notes}</p>}
        <p>{kind === 'RECEIPT' ? 'Stok sudah diterima dan tersedia.' : 'Stok sudah berpindah ke gudang tujuan.'}</p>
      </section><ReferenceMovementLines key={document.id + ':' + document.revision} id={document.id} costVisible={document.costVisible} />
    </>}</WarehouseState>
  </>
}
function ReferenceMovementLines({ id, costVisible }: { readonly id: string; readonly costVisible: boolean }) {
  const [page, setPage] = useState(0), load = useCallback(() => referenceMovementLines(id, page), [id, page]), result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Barang tersimpan"><h2>Barang dalam dokumen</h2>
    {!costVisible && <p className="muted">Rincian biaya memerlukan izin lihat biaya.</p>}
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} columns={[
      { key: 'sku', header: 'Barang', cell: row => row.skuName, description: row => row.skuCode },
      { key: 'quantity', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
      { key: 'serial', header: 'Serial / MAC', cell: row => row.serial ?? 'Tanpa serial', description: row => row.mac },
      { key: 'lot', header: 'Lot / reel', cell: row => row.lotCode ?? 'Tanpa lot' },
      { key: 'conversion', header: 'Kemasan asal', cell: row => row.conversion ? row.conversion.packageQuantity + ' kemasan · total ' + formatBaseQuantity((BigInt(row.conversion.numerator) * BigInt(row.conversion.packageQuantity) / BigInt(row.conversion.denominator)).toString(), row.baseUnit) + ' ' + displayUnit(row.baseUnit) : 'Tidak dicatat' },
      ...(costVisible ? [{ key: 'cost', header: 'Total biaya input', cell: (row: typeof data.items[number]) => row.cost ? row.cost.totalMinor + ' ' + row.cost.currency + ' (satuan terkecil)' : 'Tidak dicatat',
        description: (row: typeof data.items[number]) => row.cost ? 'Untuk kelompok ' + formatBaseQuantity(row.cost.costBasisQuantityBase, row.baseUnit) + ' ' + displayUnit(row.baseUnit) + '; bukan biaya tiap baris.' : null }] : []),
    ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </section>
}
