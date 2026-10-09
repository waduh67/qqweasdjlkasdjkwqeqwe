import { useId, useState, type FormEvent } from 'react'
import type { WarehouseSku } from '@/api/warehouse/models'
import { referenceSkus } from '@/api/warehouse/reference'
import { listCountLocations, loadCountSnapshot, saveReferenceCount, type CountInput, type CountLocation, type CountSnapshot, type ReferenceCount } from '@/api/warehouse/referenceCounts'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextareaField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { buildReferenceCount } from './referenceCountDraft'

export function ReferenceCountEditor({ onSaved, onClose }: { readonly onSaved: (row: ReferenceCount) => void; readonly onClose: () => void }) {
  const formId = useId(), [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<CountLocation | null>(null)
  const [snapshot, setSnapshot] = useState<CountSnapshot | null>(null), [command, setCommand] = useState<WarehouseCommand<CountSnapshot> | null>(null), [error, setError] = useState<string | null>(null)
  if (snapshot) return <PhysicalCountForm key={snapshot.id} snapshot={snapshot} onSaved={onSaved} onClose={onClose} onReload={() => { setSnapshot(null); setCommand(null) }} />
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!sku || !location) { setError('Pilih barang dan lokasi yang akan dihitung.'); return }
    setCommand(loadCountSnapshot({ skuId: sku.id, locationId: location.id })); setError(null)
  }
  return <ResourceForm title="Opname baru" onClose={onClose} onBack={() => setCommand(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Muat saldo opname</Button></>}
    review={command && <WarehouseCommandDialog embedded title="Muat saldo buku" confirmLabel="Muat saldo" command={command} onDone={setSnapshot} onClose={() => setCommand(null)} onReload={() => setCommand(null)}
      summary={<><p>{sku?.name} · {location?.technicianName ?? location?.name}</p><p>Saldo dan serial saat ini akan menjadi dasar hitungan. Memuat saldo belum mengubah stok.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <WarehousePicker label="Barang opname" load={referenceSkus} value={sku} name={row => row.name + ' · ' + row.code} eligible={row => row.state === 'ACTIVE'} onChange={setSku} />
      <WarehousePicker label="Lokasi opname" load={listCountLocations} value={location} name={row => (row.technicianName ?? row.name) + ' · ' + row.code} onChange={setLocation} />
      <p className="muted">Hitung barang di gudang, rak, atau tangan teknisi dalam akses Anda. Saldo yang berubah setelah dimuat memerlukan hitungan baru.</p>
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
function PhysicalCountForm({ snapshot, onSaved, onClose, onReload }: { readonly snapshot: CountSnapshot; readonly onSaved: (row: ReferenceCount) => void; readonly onClose: () => void; readonly onReload: () => void }) {
  const formId = useId(), [quantity, setQuantity] = useState(''), [serials, setSerials] = useState(''), [reason, setReason] = useState('')
  const [bookPage, setBookPage] = useState(0)
  const [review, setReview] = useState<{ readonly command: WarehouseCommand<ReferenceCount>; readonly input: CountInput } | null>(null), [error, setError] = useState<string | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { const input = buildReferenceCount(snapshot, { quantity, serials, reason }); setReview({ input, command: saveReferenceCount(input) }); setError(null) }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa hasil hitungan fisik.') }
  }
  return <ResourceForm editing title="Catat hasil fisik" onClose={onClose} onBack={() => setReview(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button onClick={onReload}>Pilih dan muat saldo kembali</Button><Button form={formId} type="submit" variant="primary">Tinjau hasil opname</Button></>}
    review={review && <WarehouseCommandDialog embedded title="Simpan hasil opname" confirmLabel="Simpan opname" command={review.command} onDone={onSaved} onClose={() => setReview(null)} onReload={onReload}
      summary={<><p>{snapshot.skuName} · {snapshot.locationName}</p><p>Saldo buku: <WarehouseQuantity value={snapshot.bookBase} unit={snapshot.baseUnit} /></p>
        <p>Hasil fisik: <WarehouseQuantity value={review.input.physicalBase} unit={snapshot.baseUnit} /></p><p>Selisih: <WarehouseQuantity value={(BigInt(review.input.physicalBase) - BigInt(snapshot.bookBase)).toString()} unit={snapshot.baseUnit} /></p>
        <p>{review.input.reason}</p>{snapshot.tracking === 'SERIAL' && <p>{review.input.serials.length} serial fisik dicatat. Penggantian serial tetap membuat penyesuaian walaupun jumlahnya sama.</p>}
        <p>Selisih langsung menyesuaikan stok. Audit tersimpan tidak dapat diubah atau dihapus.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p><strong>{snapshot.skuName}</strong> · {snapshot.locationName}</p><p>Saldo buku: <WarehouseQuantity value={snapshot.bookBase} unit={snapshot.baseUnit} /></p>
      <p className="muted">Dimuat {new Date(snapshot.loadedAt).toLocaleString('id-ID')}. Isi jumlah yang benar-benar ditemukan, termasuk nol.</p>
      <WarehouseQuantityField allowZero label="Jumlah fisik" unit={snapshot.baseUnit} value={quantity} onChange={setQuantity} />
      {snapshot.tracking === 'SERIAL' && <><TextareaField label="Serial fisik" value={serials} maxLength={100000} hint="Satu serial per baris. MAC opsional setelah koma. Biarkan kosong untuk hasil nol." onChange={(_, data) => setSerials(data.value)} />
        <details><summary>Lihat serial pada saldo buku</summary>
          <DataTable presentation="warehouse" rows={snapshot.positions.slice(bookPage * 25, (bookPage + 1) * 25)} rowKey={item => item.balanceId} empty={<p>Tidak ada serial pada saldo buku.</p>} columns={[
            { key: 'serial', header: 'Serial', cell: item => item.serial }, { key: 'mac', header: 'MAC', cell: item => item.mac ?? 'Tidak dicatat' },
          ]} /><WarehousePagination page={bookPage} size={25} total={snapshot.positions.length} onChange={setBookPage} />
        </details></>}
      <TextareaField label="Alasan opname" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
