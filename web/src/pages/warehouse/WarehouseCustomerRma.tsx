import { useCallback, useState, type FormEvent } from 'react'
import { dispatchRma, getRmaDetails, getRmaWorkOrder, type CustomerRmaHandover, type ReturnDetails, type RmaWorkOrder } from '@/api/warehouse/returns'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { listMaterialWorkOrders, type MaterialWorkOrder } from '@/api/warehouse/workOrders'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { locationLabel, receiptLocations } from './receiptChoices'
import { returnItemLabel, returnLocationLabel } from './returnPresentation'
import { buildRmaDispatch, readyForRma } from './rmaDraft'

export function WarehouseCustomerRma({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), [creating, setCreating] = useState(false)
  if (details.references.rmaHandoverId) return <RmaReceipt id={details.references.rmaHandoverId} />
  if (!readyForRma(details)) return null
  if (creating) return <RmaEditor details={details} onClose={() => setCreating(false)} onDone={reload} />
  const readable = can('workorder.order.view') && can('inventory.location.view')
  return <section className="card stack" aria-label="Pengembalian RMA pelanggan"><h2>Kembalikan perangkat pelanggan</h2>
    <p>Inspeksi dan reset servis sudah selesai. Serahkan perangkat yang sama ke teknisi pada WO perbaikan pelanggan asal.</p>
    <Button variant="primary" disabled={!readable} onClick={() => setCreating(true)}>Siapkan serah-terima RMA</Button>
    {!readable && <p className="muted">Pemilihan WO dan tujuan RMA memerlukan izin lihat work order dan lihat lokasi.</p>}
  </section>
}
function RmaEditor({ details, onClose, onDone }: { details: ReturnDetails; onClose: () => void; onDone: () => void }) {
  const customerId = details.references.assetOrigin!.customerId, [order, setOrder] = useState<MaterialWorkOrder | null>(null)
  const load = useCallback((search: string, page: number) => listMaterialWorkOrders({ customerId, type: 'REPAIR', query: search.trim() || undefined, page }), [customerId])
  return <div className="card stack"><h2>Serah-terima RMA</h2><p>{details.references.code} · {returnItemLabel(details.references.item)}</p>
    <p>Perangkat tetap milik pelanggan. Pengiriman dicatat sebagai transit sampai teknisi mengonfirmasi penerimaan fisiknya.</p>
    <WarehousePicker label="WO perbaikan pelanggan" load={load} value={order} onChange={setOrder} name={row => `${row.code} · ${row.title} · ${row.customerName ?? 'Pelanggan asal'}`}
      eligible={row => row.customerId === customerId && row.type === 'REPAIR' && ['ASSIGNED', 'IN_PROGRESS'].includes(row.status)} />
    {order ? <SelectedOrder key={order.id} details={details} orderId={order.id} onClose={onClose} onDone={onDone} /> : <Button onClick={onClose}>Batal</Button>}
  </div>
}
function SelectedOrder({ details, orderId, onClose, onDone }: { details: ReturnDetails; orderId: string; onClose: () => void; onDone: () => void }) {
  const loader = useCallback(() => getRmaWorkOrder(details.returnCase.id, orderId), [details.returnCase.id, orderId]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{order => <RmaForm details={details} order={order} onClose={onClose} onDone={onDone} />}</WarehouseState>
}
function RmaForm({ details, order, onClose, onDone }: { details: ReturnDetails; order: RmaWorkOrder; onClose: () => void; onDone: () => void }) {
  const { user } = useAuth(), [technician, setTechnician] = useState(''), [transit, setTransit] = useState<WarehouseLocation | null>(null), [field, setField] = useState<WarehouseLocation | null>(null)
  const [serial, setSerial] = useState(''), [evidence, setEvidence] = useState(''), [error, setError] = useState(''), [operation, setOperation] = useState<WarehouseCommand<CustomerRmaHandover> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!user) throw new Error('Muat ulang sesi pengguna sebelum mengirim RMA.')
      setOperation(dispatchRma(details.returnCase.id, buildRmaDispatch(details, order, technician, user.id, transit, field, serial, evidence))); setError('')
    }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa serah-terima RMA.') }
  }
  return <><form className="stack" aria-label="Serah-terima RMA" onSubmit={prepare}>
    <p>{order.code} · Revisi WO {order.revision} · Dari {returnLocationLabel(details, details.returnCase.locationId)}</p>
    <SelectField label="Teknisi penerima RMA" value={technician} required onChange={(_, data) => { setTechnician(data.value); setField(null) }}>
      <option value="">Pilih…</option>{order.technicians.map(row => <option key={row.id} value={row.id} disabled={row.id === user?.id}>{row.name}</option>)}</SelectField>
    {!order.technicians.length && <p role="status">Tidak ada teknisi aktif yang dapat menerima dari pengirim ini. Perbarui penugasan WO lalu muat ulang.</p>}
    <WarehousePicker label="Transit RMA" load={receiptLocations} value={transit} onChange={setTransit} name={locationLabel} eligible={row => row.kind === 'TRANSIT' && !row.issueEligible && row.code !== 'RECEIPT_SOURCE'} />
    <WarehousePicker label="Lokasi teknisi RMA" load={receiptLocations} value={field} onChange={setField} name={locationLabel} eligible={row => row.kind === 'TECHNICIAN' && row.custodianId === technician} />
    <TextField label="Serial fisik RMA" value={serial} required maxLength={128} onChange={(_, data) => setSerial(data.value)}
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation() } }} />
    <TextField label="Referensi bukti RMA" value={evidence} required maxLength={500} onChange={(_, data) => setEvidence(data.value)} />
    {error && <p role="alert" className="error">{error}</p>}<div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button variant="primary" type="submit" disabled={!technician}>Tinjau pengiriman RMA</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title="Konfirmasi pengiriman RMA" confirmLabel="Kirim RMA ke transit" command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
      summary={<><p>{order.code} · Revisi WO {order.revision} · Retur revisi {details.returnCase.revision}</p><p>{returnItemLabel(details.references.item)} · 1 unit · Serial {serial}</p>
        <p>Penerima: {order.technicians.find(row => row.id === technician)?.name}</p><p>{transit && locationLabel(transit)} → {field && locationLabel(field)}</p>
        <p>Bukti: {evidence}</p><p>Barang tetap milik pelanggan dan belum diterima teknisi. Tidak menambah stok tersedia ISP.</p></>} />}
  </>
}
function RmaReceipt({ id }: { id: string }) {
  const loader = useCallback(() => getRmaDetails(id), [id]), result = useWarehouseQuery(loader)
  return <section className="card stack" aria-label="Serah-terima RMA tersimpan"><h2>Serah-terima RMA</h2><WarehouseState {...result}>{details => {
    const view = details.handover
    const name = (id: string) => { const location = details.locations.find(row => row.id === id); return location ? locationLabel(location) : id }
    return <><p>{details.workOrderCode} · {details.workOrderTitle}</p><p><WarehouseStatus status={view.state} /> · Revisi {view.revision} · <WarehouseTime value={view.recordedAt} /></p>
      <p>Serial: {view.serial} · 1 unit · <WarehouseStatus status={view.legalOwner} /></p><p>Pengirim: {details.senderName ?? view.createdBy} · Penerima: {details.technicianName ?? view.technicianId}</p>
      <p>{name(view.sourceLocationId)} → {name(view.transitLocationId)} → {name(view.technicianLocationId)}</p><p>Lokasi pada serah-terima: {name(view.locationId)}</p>
      <p>{view.state === 'RECEIVED' ? 'Teknisi sudah mengonfirmasi penerimaan. Pemasangan kembali mengikuti WO pelanggan asal.' : 'Menunggu konfirmasi penerimaan fisik oleh teknisi yang tercatat. Barang masih dalam perjalanan.'}</p><Button onClick={result.reload}>Muat ulang RMA</Button></>
  }}</WarehouseState></section>
}
