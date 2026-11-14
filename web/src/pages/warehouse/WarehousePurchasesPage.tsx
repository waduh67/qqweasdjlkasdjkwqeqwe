import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getPurchase, listPurchases, purchaseCancel, purchaseReceive, CLIENT_LABELS, type Purchase } from '@/api/warehouse/clientOperations'
import { getReferenceRequest } from '@/api/warehouse/referenceRequests'
import { getReferenceSku } from '@/api/warehouse/referenceCatalog'
import { quantityFromInput, formatBaseQuantity } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ClientCommandForm, ClientCreateEditor, ClientList, ClientTimeline } from './WarehouseClientShared'

export function WarehousePurchasesPage() {
  const [params, setParams] = useSearchParams(), [creating, setCreating] = useState(false), { can } = useCan(), writable = can('warehouse.request.receive')
  const id = params.get('id'), requestId = params.get('requestId')
  const close = () => { setCreating(false); setParams({}) }
  const saved = (row: Purchase) => { setCreating(false); setParams({ id: row.id }) }
  return <div className="stack">{id ? <PurchaseDetail key={id} id={id} onBack={close} /> : <ClientList title="Pembelian gudang" subtitle="Pesanan pemasok dan penerimaan barang bertahap." load={listPurchases} onOpen={id => setParams({ id })} create={writable && <Button variant="primary" onClick={() => setCreating(true)}>Catat pembelian</Button>} columns={[{ key: 'reference', header: 'Pesanan', cell: row => row.reference }, { key: 'supplier', header: 'Pemasok', cell: row => row.supplierName }, { key: 'date', header: 'Tanggal', cell: row => row.purchaseDate }]} />}
    {(creating || requestId) && writable && (requestId ? <LinkedPurchase requestId={requestId} onClose={close} onSaved={saved} /> : <ClientCreateEditor kind="PURCHASE" onClose={close} onSaved={row => saved(row as Purchase)} />)}
  </div>
}
function LinkedPurchase({ requestId, onClose, onSaved }: { requestId: string; onClose: () => void; onSaved: (row: Purchase) => void }) {
  const result = useWarehouseQuery(useCallback(() => getReferenceRequest(requestId), [requestId]))
  return <WarehouseState {...result}>{data => <ClientCreateEditor kind="PURCHASE" initialRequest={data.request} onClose={onClose} onSaved={row => onSaved(row as Purchase)} />}</WarehouseState>
}
function PurchaseDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const result = useWarehouseQuery(useCallback(() => getPurchase(id), [id])), { can } = useCan(), [action, setAction] = useState<'RECEIVE' | 'CANCEL' | null>(null), [lineId, setLineId] = useState('')
  const done = () => { setAction(null); result.reload() }
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang</Button></div><WarehouseState {...result}>{({ document: row, timeline }) => <>
    <div className="card stack"><h2>{row.reference}</h2><p>{CLIENT_LABELS[row.state]} · {row.supplierName} · {row.warehouseName} · {row.purchaseDate}</p><p>{row.notes}</p>{row.requestId && <Link to={'/warehouse/requests?id=' + row.requestId}>Buka request pengadaan</Link>}{can('warehouse.request.receive') && ['ORDERED', 'PARTIALLY_RECEIVED'].includes(row.state) && <Button onClick={() => setAction('CANCEL')}>Batalkan sisa pesanan</Button>}</div>
    <DataTable presentation="warehouse" rows={row.lines} rowKey={line => line.id} columns={[{ key: 'sku', header: 'Barang', cell: line => line.skuName }, { key: 'ordered', header: 'Dipesan', cell: line => <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> }, { key: 'received', header: 'Diterima', cell: line => <WarehouseQuantity value={line.receivedBase} unit={line.baseUnit} /> }, { key: 'price', header: 'Harga satuan', cell: line => line.unitPriceMinor ? 'Rp ' + BigInt(line.unitPriceMinor).toLocaleString('id-ID') : '—' }, { key: 'action', header: 'Tindakan', cell: line => can('warehouse.request.receive') && ['ORDERED', 'PARTIALLY_RECEIVED'].includes(row.state) && BigInt(line.receivedBase) < BigInt(line.quantityBase) && <Button onClick={() => { setLineId(line.id); setAction('RECEIVE') }}>Terima {line.skuName}</Button> }]} />
    <ClientTimeline events={timeline} />
    {action === 'RECEIVE' && <PurchaseReceipt key={row.revision + lineId} row={row} lineId={lineId} onClose={() => setAction(null)} onDone={done} />}
    {action === 'CANCEL' && <PurchaseCancellation row={row} onClose={() => setAction(null)} onDone={done} />}
  </>}</WarehouseState></>
}
function PurchaseCancellation({ row, onClose, onDone }: { row: Purchase; onClose: () => void; onDone: () => void }) {
  const [notes, setNotes] = useState('')
  return <ClientCommandForm title="Batalkan sisa pembelian" prepare={() => { if (!notes.trim()) throw new Error('Isi alasan pembatalan.'); return purchaseCancel(row, notes) }} onClose={onClose} onDone={onDone} summary={<><p>{row.reference}</p><p>{notes}</p><p>Barang yang sudah diterima tetap tercatat dalam stok.</p></>}><TextareaField label="Alasan pembatalan" required maxLength={1000} value={notes} onChange={(_, d) => setNotes(d.value)} /></ClientCommandForm>
}
function PurchaseReceipt({ row, lineId, onClose, onDone }: { row: Purchase; lineId: string; onClose: () => void; onDone: () => void }) {
  const line = row.lines.find(l => l.id === lineId)!, remaining = (BigInt(line.quantityBase) - BigInt(line.receivedBase)).toString()
  const [quantity, setQuantity] = useState(formatBaseQuantity(remaining, line.baseUnit)), [serials, setSerials] = useState(''), [lot, setLot] = useState(''), [notes, setNotes] = useState('')
  const sku = useWarehouseQuery(useCallback(() => getReferenceSku(line.skuId), [line.skuId]))
  return <ClientCommandForm title={'Terima ' + line.skuName} onClose={onClose} onDone={onDone} prepare={() => {
    if (sku.state.status !== 'ready') throw new Error('Tunggu data barang.')
    const quantityBase = quantityFromInput(quantity, line.baseUnit)
    if (BigInt(quantityBase) > BigInt(remaining)) throw new Error('Jumlah melebihi sisa pesanan.')
    const values = serials.split('\n').map(s => s.trim()).filter(Boolean).map(s => { const [serial, mac] = s.split(',').map(v => v.trim()); return { serial, ...(mac ? { mac } : {}) } })
    if (sku.state.data.tracking === 'SERIAL' && BigInt(values.length) !== BigInt(quantityBase)) throw new Error('Satu serial untuk setiap perangkat.')
    return purchaseReceive(row, { lineId, quantityBase, serials: values, ...(lot ? { lotCode: lot } : {}), notes })
  }} summary={<><p>{row.reference} · {line.skuName} · {quantity} {line.baseUnit === 'MM' ? 'm' : 'unit'}</p><pre>{serials}</pre><p>{notes}</p></>}>
    <p>Sisa: <WarehouseQuantity value={remaining} unit={line.baseUnit} /></p><WarehouseQuantityField label="Jumlah diterima" unit={line.baseUnit} value={quantity} onChange={setQuantity} />
    <WarehouseState {...sku}>{s => s.tracking === 'SERIAL' ? <TextareaField label="Serial perangkat, satu per baris (serial, MAC opsional)" required value={serials} onChange={(_, d) => setSerials(d.value)} /> : <TextField label="Kode lot (opsional)" value={lot} maxLength={100} onChange={(_, d) => setLot(d.value)} />}</WarehouseState>
    <TextareaField label="Catatan penerimaan" maxLength={1000} value={notes} onChange={(_, d) => setNotes(d.value)} />
  </ClientCommandForm>
}
