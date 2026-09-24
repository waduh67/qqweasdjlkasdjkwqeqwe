import { useCallback, useState } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { Checkbox } from '@fluentui/react-components'
import type { IssueRow, IssueSlip } from '@/api/warehouse/issueModels'
import type { MaterialSummary } from '@/api/warehouse/materialModels'
import { getIssueSlip, listIssues, transitionIssue } from '@/api/warehouse/materials'
import { warehouseError } from '@/api/warehouse/errors'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextareaField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function WarehouseIssuePanel({ summary, onChanged, active }: { summary: MaterialSummary; onChanged: () => void; active: boolean }) {
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<IssueRow | null>(null)
  const loader = useCallback(() => listIssues(summary.workOrderId, { page }), [summary.workOrderId, page])
  const result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Slip pengeluaran"><h2>Slip pengeluaran</h2>
    <p>Jumlah diterima berasal dari konfirmasi penerimaan teknisi. Pengiriman saja belum membuktikan barang diterima.</p>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada slip dalam cakupan Anda" hint="Siapkan barang dari reservasi untuk membuat slip pengeluaran." />} columns={[
        { key: 'code', header: 'Slip', cell: row => <Button variant="subtle" onClick={() => setSelected(row)}>{row.code}</Button> },
        { key: 'state', header: 'Status saat ini', cell: row => <><WarehouseStatus status={row.unpicked ? 'UNPICKED' : row.state} /><p className="muted">Revisi {row.revision} · Rencana {row.planRevision}</p></> },
        { key: 'receiver', header: 'Penerima', cell: row => row.receiver.name },
        { key: 'lines', header: 'Jumlah per barang', cell: row => <ul>{row.lines.map(line => <li key={line.issueLineId}><strong>{line.sku.name}</strong> · {line.serial ?? line.lotCode}<br />Disiapkan <WarehouseQuantity value={line.pickedBase} unit={line.baseUnit} /><br />Dikirim <WarehouseQuantity value={line.dispatchedBase} unit={line.baseUnit} /><br />Diterima <WarehouseQuantity value={line.acceptedBase} unit={line.baseUnit} /></li>)}</ul> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={next => { setSelected(null); setPage(next) }} />
    </>}</WarehouseState>
    {selected && <IssueDetail key={selected.id} row={selected} summary={summary} active={active} onClose={() => setSelected(null)} onChanged={onChanged} />}
  </section>
}
function IssueDetail({ row, summary, active, onClose, onChanged }: { row: IssueRow; summary: MaterialSummary; active: boolean; onClose: () => void; onChanged: () => void }) {
  const loader = useCallback(() => getIssueSlip(row.workOrderId, row.issueId), [row.workOrderId, row.issueId])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{slip => <IssueActions row={row} slip={slip} summary={summary} active={active} onClose={onClose} onChanged={onChanged} />}</WarehouseState>
}
function IssueActions({ row, slip, summary, active, onClose, onChanged }: { row: IssueRow; slip: IssueSlip; summary: MaterialSummary; active: boolean; onClose: () => void; onChanged: () => void }) {
  const { can } = useCan()
  const [reason, setReason] = useState('')
  const [receiverConfirmed, setReceiverConfirmed] = useState(false)
  const [partialConfirmed, setPartialConfirmed] = useState(false)
  const [operation, setOperation] = useState<{ action: 'dispatch' | 'unpick'; command: WarehouseCommand<IssueSlip> } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [printing, setPrinting] = useState(false)
  const [printable, setPrintable] = useState<IssueSlip | null>(null)
  const writable = can('inventory.issue.manage') && can('inventory.request.manage') && (!slip.lines.some(line => line.substitution) || can('inventory.request.override'))
  const picked = !row.unpicked && row.state === 'PICKED' && slip.state === 'PICKED' && row.revision === slip.revision && slip.planRevision === summary.revisions.planRevision
  const partial = summary.lines.filter(line => summary.plan?.lines.some(plan => plan.id === line.planLineId)).some(line =>
    BigInt(line.requestedBase) - BigInt(line.issuedBase) !== slip.lines.filter(item => item.planLineId === line.planLineId).reduce((sum, item) => sum + BigInt(item.quantityBase), 0n))
  function prepare(action: 'dispatch' | 'unpick') {
    if (!reason.trim() || reason.trim().length > 1000) { setError('Isi alasan atau catatan serah kirim, maksimal 1000 karakter.'); return }
    if (summary.demandRevision === null || !picked || !writable || !active) { setError('Slip berubah atau tidak dapat diproses. Muat ulang permintaan.'); return }
    if (action === 'dispatch' && (!receiverConfirmed || (partial && !partialConfirmed))) { setError('Konfirmasi penerima dan pengiriman sebagian terlebih dahulu.'); return }
    setOperation({ action, command: transitionIssue(summary.workOrderId, action, { issueId: slip.issueId, expectedRevision: row.revision, workOrderRevision: summary.revisions.workOrderRevision, planRevision: summary.revisions.planRevision, demandRevision: summary.demandRevision, partial: action === 'dispatch' && partial, reason: reason.trim() }) }); setError(null)
  }
  async function printSlip() {
    setPrinting(true); setError(null)
    try {
      const current = await getIssueSlip(slip.workOrderId, slip.issueId)
      flushSync(() => setPrintable(current)); window.print()
    } catch (caught) { setError(warehouseError(caught)) }
    finally { setPrinting(false); setPrintable(null) }
  }
  return <section className="card stack" aria-label="Detail slip pengeluaran">
    <SlipContent slip={slip} />
    <p>Status dokumen saat ini: <WarehouseStatus status={row.unpicked ? 'UNPICKED' : row.state} /> · Revisi {row.revision}. Slip mencatat kejadian pada revisi {slip.revision}.</p>
    <div className="row wrap"><Button onClick={onClose}>Tutup slip</Button><Button disabled={printing} onClick={() => void printSlip()}>{printing ? 'Menyiapkan cetakan…' : 'Cetak slip'}</Button></div>
    {picked && writable && active && <div className="stack">
      <TextareaField label="Catatan pengiriman / pembatalan" value={reason} maxLength={1000} required onChange={(_, data) => setReason(data.value)} />
      <Checkbox label={`Konfirmasi penerima: ${slip.receiver.name}`} checked={receiverConfirmed} onChange={(_, data) => setReceiverConfirmed(data.checked === true)} />
      {partial && <Checkbox label="Kirim sebagian; sisa kebutuhan masih harus dipenuhi" checked={partialConfirmed} onChange={(_, data) => setPartialConfirmed(data.checked === true)} />}
      <p>Pengiriman memindahkan barang dari gudang ke transit WO. Teknisi masih harus mengonfirmasi penerimaan fisik.</p>
      <div className="row wrap"><Button onClick={() => prepare('unpick')}>Batal siapkan</Button><Button variant="primary" disabled={!receiverConfirmed || (partial && !partialConfirmed)} onClick={() => prepare('dispatch')}>Kirim barang</Button></div>
    </div>}
    {picked && (!writable || !active) && <p className="muted">Pemrosesan slip memerlukan WO aktif serta izin kelola pengeluaran dan permintaan; barang substitusi juga memerlukan izin override.</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {operation && <WarehouseCommandDialog title={operation.action === 'dispatch' ? 'Konfirmasi kirim barang' : 'Batalkan persiapan slip'} confirmLabel={operation.action === 'dispatch' ? 'Konfirmasi kirim' : 'Konfirmasi batal siapkan'} command={operation.command} onClose={() => setOperation(null)} onDone={onChanged} onReload={onChanged}
      summary={<><p>{slip.code} · Penerima: <strong>{slip.receiver.name}</strong></p><SlipLines slip={slip} /><p>{reason}</p><p>{operation.action === 'dispatch' ? `${partial ? 'Pengiriman sebagian. ' : ''}Stok gudang berkurang, stok transit bertambah. Jumlah diterima teknisi belum berubah.` : 'Reservasi kembali belum disiapkan. Potongan kabel yang sudah dibuat tetap tercatat sebagai potongan terpisah.'}</p></>} />}
    {printable && createPortal(<div className="warehouse-issue-print"><SlipContent slip={printable} /></div>, document.body)}
  </section>
}
function SlipLines({ slip }: { slip: IssueSlip }) {
  return <ul>{slip.lines.map(line => <li key={line.id}><strong>{line.sku.name} · {line.sku.code}</strong><br />{line.serial ?? line.lotCode} · {line.locationName} · <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} />
    {line.substitution && <p>Pengganti {line.originalSku?.name ?? 'barang rencana asal'}: {line.substitution.reason}</p>}
    <p className="muted" style={{ overflowWrap: 'anywhere' }}>Identitas barang: {line.dimension.stockIdentityId}</p>
  </li>)}</ul>
}
function SlipContent({ slip }: { slip: IssueSlip }) {
  return <><h3 style={{ overflowWrap: 'anywhere' }}>{slip.code}</h3><p>WO {slip.workOrderCode} · Rencana {slip.planRevision} · Slip revisi {slip.revision} · <WarehouseStatus status={slip.state} /></p>
    {slip.customerLabelSnapshot && <p>Pelanggan: {slip.customerLabelSnapshot}</p>}<p>Pengirim: <strong>{slip.sender.name}</strong> · Penerima: <strong>{slip.receiver.name}</strong></p><p><WarehouseTime value={slip.recordedAt} /></p><SlipLines slip={slip} />
    <p>Slip ini mencatat persiapan atau pengiriman. Konfirmasi penerimaan teknisi dicatat terpisah.</p></>
}
