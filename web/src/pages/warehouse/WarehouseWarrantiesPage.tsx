import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getWarranty, listWarranties, warrantyDispatch, warrantyReceive, warrantyInspect, CLIENT_LABELS, type Warranty } from '@/api/warehouse/clientOperations'
import type { WarehouseSupplier } from '@/api/warehouse/models'
import { referenceSuppliers } from '@/api/warehouse/reference'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { Choice, ClientCommandForm, ClientList, ClientTimeline } from './WarehouseClientShared'

export function WarehouseWarrantiesPage() {
  const [params, setParams] = useSearchParams(), id = params.get('id')
  return <div className="stack">{id ? <WarrantyDetail key={id} id={id} onBack={() => setParams({})} /> : <ClientList title="Garansi vendor" subtitle="Klaim dibuat dari retur rusak, dilanjutkan ke perbaikan atau penggantian pemasok." load={listWarranties} onOpen={id => setParams({ id })} columns={[{ key: 'sku', header: 'Barang', cell: row => row.skuName, description: row => row.serial ?? row.stockIdentityId.slice(-8) }, { key: 'borrower', header: 'Dari client', cell: row => row.borrowerName }, { key: 'supplier', header: 'Vendor klaim', cell: row => row.supplierName ?? 'Belum dikirim', description: row => row.vendorReference || undefined }]} />}</div>
}
function WarrantyDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const result = useWarehouseQuery(useCallback(() => getWarranty(id), [id])), { can } = useCan(), [action, setAction] = useState<'DISPATCH' | 'RECEIVE' | 'INSPECT' | null>(null)
  const done = () => { setAction(null); result.reload() }
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang</Button></div><WarehouseState {...result}>{({ document: row, timeline }) => <>
    <div className="card stack"><h2>Klaim {row.id.slice(-8)} · {row.skuName}</h2><p>{CLIENT_LABELS[row.state]} · {row.warehouseName} · <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /></p><p>Client: {row.borrowerName} · Serial: {row.serial ?? 'Tanpa serial'}</p><p>Vendor: {row.supplierName ?? 'Belum dipilih'} · Referensi: {row.vendorReference || '—'}</p>{row.result && <p>Hasil vendor: {({ REPAIRED: 'Diperbaiki', UNREPAIRED: 'Belum diperbaiki', REJECTED: 'Klaim ditolak', REPLACED: 'Diganti perangkat baru' } as Record<string, string>)[row.result]}</p>}<p>{row.evidence}</p>
      <Link to={'/warehouse/loans?id=' + row.loanId}>Buka pinjaman asal</Link><div className="row wrap">{can('warehouse.warranty.manage') && <>{row.state === 'MASUK' && !row.returnedAt && <Button variant="primary" onClick={() => setAction('DISPATCH')}>Kirim ke vendor</Button>}{row.state === 'DIKIRIM' && <Button variant="primary" onClick={() => setAction('RECEIVE')}>Terima hasil vendor</Button>}{row.state === 'MASUK' && row.returnedAt && <Button variant="primary" onClick={() => setAction('INSPECT')}>Periksa kelayakan</Button>}</>}</div>
    </div><ClientTimeline events={timeline} />
    {action === 'DISPATCH' && <WarrantyDispatch row={row} onClose={() => setAction(null)} onDone={done} />}
    {action === 'RECEIVE' && <WarrantyReceipt row={row} onClose={() => setAction(null)} onDone={done} />}
    {action === 'INSPECT' && <WarrantyInspection row={row} onClose={() => setAction(null)} onDone={done} />}
  </>}</WarehouseState></>
}
function WarrantyDispatch({ row, onClose, onDone }: { row: Warranty; onClose: () => void; onDone: () => void }) {
  const [supplier, setSupplier] = useState<WarehouseSupplier | null>(null), [reference, setReference] = useState(''), [evidence, setEvidence] = useState('')
  return <ClientCommandForm title="Kirim klaim ke vendor" onClose={onClose} onDone={onDone} prepare={() => { if (!supplier || !reference.trim() || !evidence.trim()) throw new Error('Lengkapi vendor, referensi, dan bukti kirim.'); return warrantyDispatch(row, { supplierId: supplier.id, vendorReference: reference, evidence }) }} summary={<><p>{row.skuName} · {row.serial} · {supplier?.name}</p><p>{reference} · {evidence}</p></>}>
    <WarehousePicker label="Vendor / pemasok" load={referenceSuppliers} value={supplier} name={r => r.name} eligible={r => r.state === 'ACTIVE'} onChange={setSupplier} />
    <TextField label="Nomor klaim / surat jalan" required maxLength={500} value={reference} onChange={(_, d) => setReference(d.value)} /><TextareaField label="Bukti / catatan pengiriman" required maxLength={1000} value={evidence} onChange={(_, d) => setEvidence(d.value)} />
  </ClientCommandForm>
}
function WarrantyReceipt({ row, onClose, onDone }: { row: Warranty; onClose: () => void; onDone: () => void }) {
  const [result, setResult] = useState('REPAIRED'), [serial, setSerial] = useState(''), [mac, setMac] = useState(''), [evidence, setEvidence] = useState('')
  return <ClientCommandForm title="Terima hasil vendor" onClose={onClose} onDone={onDone} prepare={() => { if (!evidence.trim()) throw new Error('Isi bukti penerimaan.'); if (row.tracking === 'SERIAL' && !serial.trim()) throw new Error('Isi serial perangkat yang diterima.'); return warrantyReceive(row, { result, evidence, ...(result === 'REPLACED' ? { replacementSerial: serial, replacementMac: mac || undefined } : row.tracking === 'SERIAL' ? { observedSerial: serial } : {}) }) }} summary={<><p>{row.skuName} · {result} · Serial: {serial}</p><p>{evidence}</p><p>Barang tetap di karantina sampai selesai pemeriksaan kelayakan.</p></>}>
    <Choice label="Hasil vendor" value={result} onChange={v => { setResult(v); setSerial(''); setMac('') }} values={[[ 'REPAIRED', 'Diperbaiki' ], [ 'UNREPAIRED', 'Belum diperbaiki' ], [ 'REJECTED', 'Klaim ditolak' ], ...(row.tracking === 'SERIAL' ? [[ 'REPLACED', 'Diganti perangkat baru' ] as [string, string]] : [])]} />
    {row.tracking === 'SERIAL' && <TextField label={result === 'REPLACED' ? 'Serial perangkat pengganti' : 'Serial fisik yang diterima'} required maxLength={200} value={serial} onChange={(_, d) => setSerial(d.value)} />}
    {result === 'REPLACED' && <TextField label="MAC pengganti (opsional)" value={mac} onChange={(_, d) => setMac(d.value)} />}
    <TextareaField label="Bukti / hasil vendor" required maxLength={1000} value={evidence} onChange={(_, d) => setEvidence(d.value)} />
  </ClientCommandForm>
}
function WarrantyInspection({ row, onClose, onDone }: { row: Warranty; onClose: () => void; onDone: () => void }) {
  const [serviceable, setServiceable] = useState('true'), [reset, setReset] = useState('false'), [evidence, setEvidence] = useState('')
  return <ClientCommandForm title="Periksa hasil garansi" onClose={onClose} onDone={onDone} prepare={() => { if (!evidence.trim()) throw new Error('Isi hasil pemeriksaan.'); if (serviceable === 'true' && row.tracking === 'SERIAL' && reset !== 'true') throw new Error('Konfirmasi reset perangkat sebelum mengembalikan ke stok.'); return warrantyInspect(row, { serviceable: serviceable === 'true', resetConfirmed: reset === 'true', evidence }) }} summary={<><p>{row.skuName} · {row.serial}</p><p>{serviceable === 'true' ? 'Layak: dikembalikan ke stok tersedia.' : 'Belum layak: tetap di karantina.'}</p><p>{evidence}</p></>}>
    <Choice label="Kelayakan barang" value={serviceable} onChange={setServiceable} values={[[ 'true', 'Layak pakai' ], [ 'false', 'Tidak layak / tetap karantina' ]]} />
    {row.tracking === 'SERIAL' && <Choice label="Reset perangkat" value={reset} onChange={setReset} values={[[ 'false', 'Belum dikonfirmasi' ], [ 'true', 'Reset selesai dan telah diperiksa' ]]} />}
    <TextareaField label="Bukti / hasil pemeriksaan" required maxLength={1000} value={evidence} onChange={(_, d) => setEvidence(d.value)} />
  </ClientCommandForm>
}
