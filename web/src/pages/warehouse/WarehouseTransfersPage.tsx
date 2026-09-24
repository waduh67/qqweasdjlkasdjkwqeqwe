import { useCallback, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { uuid } from '@/api/warehouse/codec'
import { dispatchTransfer, getTransfer, listTransfers, transferHistory, TRANSFER_STATES, type TransferDetails, type TransferState, type WarehouseTransfer } from '@/api/warehouse/transfers'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseTransferEditor } from './WarehouseTransferEditor'
import { WarehouseTransferActions } from './WarehouseTransferActions'
import { transferLineLabel, transferLocationLabel, transferPersonLabel } from './transferPresentation'

const detailPath = (id: string) => `/warehouse/transfers?transferId=${encodeURIComponent(id)}`
const stateLabels: Record<TransferState, string> = { DRAFT: 'Draf', DISPATCHED: 'Dikirim', PART_RECEIVED: 'Sebagian diterima', RECEIVED: 'Diterima', DISCREPANCY: 'Penanganan selisih' }
export function WarehouseTransfersPage() {
  const { can } = useCan(), [params] = useSearchParams(), navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  let id: string | null = null
  try {
    if ([...params.keys()].some(key => key !== 'transferId') || params.getAll('transferId').length > 1) throw new Error()
    if (params.has('transferId')) id = uuid(params.get('transferId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat transfer tidak dikenal.</p><Link to="/warehouse/transfers">Kembali ke daftar transfer</Link></div> }
  if (!can('inventory.transfer.view')) return <WarehouseDenied />
  return <div className="stack warehouse-transfers"><PageHeader title="Transfer" subtitle="Lacak pengiriman antar lokasi, penerimaan fisik, dan sisa dalam perjalanan." />
    {creating ? <WarehouseTransferEditor onSaved={row => { setCreating(false); navigate(detailPath(row.id)) }} onClose={() => setCreating(false)} onReload={() => setCreating(false)} />
      : id ? <><Link to="/warehouse/transfers">Kembali ke daftar transfer</Link><TransferDetail key={id} id={id} /></>
      : <>{can('inventory.transfer.manage') && <Button variant="primary" onClick={() => setCreating(true)}>Buat transfer</Button>}<TransferList /></>}
  </div>
}
function TransferList() {
  const [search, setSearch] = useState(''), [state, setState] = useState<TransferState | ''>(''), [page, setPage] = useState(0)
  const loader = useCallback(() => listTransfers({ page, query: search.trim() || undefined, state: state || undefined }), [page, search, state]), result = useWarehouseQuery(loader)
  return <><div className="row wrap"><TextField label="Cari kode transfer" value={search} maxLength={200} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
    <SelectField label="Status transfer" value={state} onChange={(_, data) => { setState(data.value as TransferState | ''); setPage(0) }}><option value="">Semua status</option>{TRANSFER_STATES.map(state => <option key={state} value={state}>{stateLabels[state]}</option>)}</SelectField><Button onClick={result.reload}>Segarkan transfer</Button></div>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.transfer.id} empty={<EmptyState title="Belum ada transfer dalam cakupan Anda" hint="Buat transfer dari stok fisik yang sudah diterima dan belum terikat pengeluaran WO." />} columns={[
      { key: 'code', header: 'Transfer', cell: row => <Link to={detailPath(row.transfer.id)}>{row.transfer.code}</Link> },
      { key: 'route', header: 'Asal → Tujuan', cell: row => <span>{transferLocationLabel(row, row.transfer.sourceLocationId)} → {transferLocationLabel(row, row.transfer.destinationLocationId)}</span> },
      { key: 'receiver', header: 'Penerima', cell: row => transferPersonLabel(row, row.transfer.receiverId) },
      { key: 'state', header: 'Status', cell: row => <WarehouseStatus status={row.transfer.state} /> },
      { key: 'remaining', header: 'Sisa perjalanan', cell: row => row.transfer.state === 'DRAFT' ? 'Belum dikirim' : <ul>{row.transfer.lines.map(line => <li key={line.id}>{transferLineLabel(row, line.id)}: <WarehouseQuantity value={line.inTransitBase} unit={line.baseUnit} /></li>)}</ul> },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </>
}
function TransferDetail({ id }: { id: string }) {
  const loader = useCallback(() => getTransfer(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{details => <TransferBody details={details} reload={result.reload} />}</WarehouseState>
}
function TransferBody({ details, reload }: { details: TransferDetails; reload: () => void }) {
  const { can } = useCan(), { user } = useAuth(), { transfer } = details
  const [action, setAction] = useState<'receive' | 'discrepancy' | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseTransfer> | null>(null)
  const manage = can('inventory.transfer.manage'), sender = user?.id === transfer.senderId, receiver = user?.id === transfer.receiverId
  const waiting = ['DISPATCHED', 'PART_RECEIVED'].includes(transfer.state)
  if (action) return <WarehouseTransferActions details={details} action={action} onDone={reload} onClose={() => setAction(null)} />
  return <><section className="card stack" aria-label="Detail transfer"><h2 style={{ overflowWrap: 'anywhere' }}>{transfer.code}</h2><p><WarehouseStatus status={transfer.state} /> · Revisi {transfer.revision} · <WarehouseTime value={transfer.recordedAt} /></p>
    <p>{transferLocationLabel(details, transfer.sourceLocationId)} → {transferLocationLabel(details, transfer.transitLocationId)} → {transferLocationLabel(details, transfer.destinationLocationId)}</p>
    <p>Pengirim: <strong>{transferPersonLabel(details, transfer.senderId)}</strong> · Penerima: <strong>{transferPersonLabel(details, transfer.receiverId)}</strong></p><p>{transfer.reason}</p>
    <p>{transfer.state === 'DRAFT' ? 'Draft belum memindahkan atau mencadangkan stok. Pengirim harus memeriksa dan mengirim barang.' : 'Jumlah diterima berasal dari konfirmasi penerima. Sisa dalam perjalanan tetap tercatat sampai diterima atau diselesaikan dengan persetujuan independen.'}</p>
    <div className="row wrap"><Button onClick={reload}>Muat ulang transfer</Button>
      {manage && transfer.state === 'DRAFT' && <Button variant="primary" disabled={!sender} onClick={() => setOperation(dispatchTransfer(transfer.id, transfer.revision))}>Kirim ke transit</Button>}
      {manage && waiting && <><Button variant="primary" disabled={!receiver} onClick={() => setAction('receive')}>Terima transfer</Button><Button disabled={!receiver || !can('inventory.approval.request') || !can('inventory.location.view')} onClick={() => setAction('discrepancy')}>Laporkan selisih</Button></>}
    </div>
    {!manage && <p className="muted">Akses baca saja. Transaksi memerlukan izin kelola transfer.</p>}
    {manage && transfer.state === 'DRAFT' && !sender && <p className="muted">Pengiriman hanya dapat dilakukan pengirim yang tercatat.</p>}
    {manage && waiting && !receiver && <p className="muted">Penerimaan dan pelaporan selisih hanya dapat dilakukan penerima yang tercatat.</p>}
    {manage && waiting && receiver && (!can('inventory.approval.request') || !can('inventory.location.view')) && <p className="muted">Pelaporan selisih memerlukan izin ajukan persetujuan dan lihat lokasi.</p>}
    {transfer.lines.some(line => line.legalOwner === 'CUSTOMER') && <p role="status">Barang milik pelanggan tetap milik pelanggan; penerimaan tidak menjadikannya stok tersedia.</p>}
    {transfer.resolutionDocumentId && <div className="stack"><p style={{ overflowWrap: 'anywhere' }}>Dokumen penanganan selisih: {transfer.resolutionDocumentId}</p><p>Pengirim dan penerima tidak dapat menyetujui selisihnya sendiri. Jumlah selesai di tabel hanya bertambah setelah keputusan dibukukan.</p>
      {can('inventory.approval.view') && <Link to={`/warehouse/approvals?sourceDocumentId=${encodeURIComponent(transfer.resolutionDocumentId)}`}>Buka persetujuan gudang</Link>}</div>}
  </section>
    <DataTable presentation="warehouse" rows={transfer.lines} rowKey={line => line.id} columns={[
      { key: 'item', header: 'Barang', cell: line => <span>{transferLineLabel(details, line.id)}<p className="muted" style={{ overflowWrap: 'anywhere' }}>Identitas asal: {line.stockIdentityId}</p></span> },
      { key: 'quantity', header: transfer.state === 'DRAFT' ? 'Rencana kirim' : 'Dikirim', cell: line => <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> },
      { key: 'received', header: 'Diterima', cell: line => <WarehouseQuantity value={line.receivedBase} unit={line.baseUnit} /> },
      { key: 'transit', header: 'Dalam perjalanan', cell: line => <WarehouseQuantity value={line.inTransitBase} unit={line.baseUnit} /> },
      { key: 'resolved', header: 'Diselesaikan', cell: line => <WarehouseQuantity value={line.resolvedBase} unit={line.baseUnit} /> },
      { key: 'condition', header: 'Kondisi / Pemilik', cell: line => <span><WarehouseStatus status={line.condition} /> · <WarehouseStatus status={line.legalOwner} /></span> },
    ]} />
    <TransferHistory details={details} />
    {operation && <WarehouseCommandDialog title="Kirim transfer ke transit" confirmLabel="Konfirmasi kirim transfer" command={operation} onDone={reload} onReload={reload} onClose={() => setOperation(null)}
      summary={<><p>{transfer.code} · Revisi {transfer.revision}</p><p>{transferLocationLabel(details, transfer.sourceLocationId)} → {transferLocationLabel(details, transfer.transitLocationId)} → {transferLocationLabel(details, transfer.destinationLocationId)}</p>
        <p>Penerima: {transferPersonLabel(details, transfer.receiverId)}</p><ul>{transfer.lines.map(line => <li key={line.id}>{transferLineLabel(details, line.id)}: <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /></li>)}</ul>
        <p>Barang berpindah ke transit. Stok tujuan bertambah setelah penerima mengonfirmasi penerimaan fisik.</p></>} />}
  </>
}
function TransferHistory({ details }: { details: TransferDetails }) {
  const id = details.transfer.id
  const [page, setPage] = useState(0), loader = useCallback(() => transferHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <details className="card"><summary>Riwayat transfer</summary><WarehouseState {...result}>{data => <div className="stack">{data.items.map(row => <section key={row.revision}>
    <h3>Revisi {row.revision} · <WarehouseStatus status={row.state} /></h3><p><WarehouseTime value={row.recordedAt} /></p><ul>{row.lines.map(line => <li key={line.id}>{transferLineLabel(details, line.id)}: diterima <WarehouseQuantity value={line.receivedBase} unit={line.baseUnit} />, dalam perjalanan <WarehouseQuantity value={line.inTransitBase} unit={line.baseUnit} /></li>)}</ul>
  </section>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></div>}</WarehouseState></details>
}
