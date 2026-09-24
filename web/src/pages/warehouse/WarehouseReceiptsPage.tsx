import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getReceipt, getReceiptHistory, listReceipts, RECEIPT_STATES, receiveReceipt, type WarehouseReceipt } from '@/api/warehouse/receipts'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { formatBaseQuantity, displayUnit } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseHistory, WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseReceiptEditor } from './WarehouseReceiptEditor'
import { WarehouseReceiptActions } from './WarehouseReceiptActions'
import { receiptCandidates } from './receiptActions'
import { WarehouseReceiptEvidence } from './WarehouseReceiptEvidence'
import { receiptLink, saveReceiptFile } from './receiptFiles'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'

const stateLabels: Record<WarehouseReceipt['state'], string> = { DRAFT: 'Draft', RECEIVED_IN_INSPECTION: 'Dalam pemeriksaan', PUTAWAY: 'Selesai ditempatkan', CLOSED: 'Ditutup' }

export function WarehouseReceiptsPage() {
  const [params, setParams] = useSearchParams()
  const [fresh, setFresh] = useState(0)
  const id = params.get('id')
  const newDraft = params.get('new') === '1'
  return <div className="stack"><PageHeader title="Penerimaan Barang" subtitle="Catat barang pemasok, periksa kondisi, lalu tempatkan barang layak ke bin." />
    {newDraft ? <WarehouseReceiptEditor key={fresh} onClose={() => setParams({})} onReload={() => setFresh(value => value + 1)} onSaved={row => setParams({ id: row.id })} />
      : id ? <ReceiptDetail id={id} /> : <ReceiptList onNew={() => setParams({ new: '1' })} />}
  </div>
}

function ReceiptList({ onNew }: { onNew: () => void }) {
  const { can } = useCan()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const [serial, setSerial] = useState('')
  const [sourceEditor, setSourceEditor] = useState(false)
  const loader = useCallback(() => listReceipts({ page, status: status ? status as WarehouseReceipt['state'] : undefined, serial: serial.trim() || undefined }), [page, status, serial])
  const result = useWarehouseQuery(loader)
  const canDraft = can('inventory.receipt.manage') && can('inventory.sku.view') && can('inventory.location.view')
  return <>
    <details className="card"><summary>Persiapan penerimaan</summary><div className="stack"><p>Siapkan pemasok, barang, lokasi transit batas penerimaan, karantina, dan bin tujuan. Pastikan akun Anda memiliki akses ke seluruh lokasi tersebut.</p>
      <Link to="/warehouse/catalog">Buka katalog dan lokasi</Link>{can('inventory.location.manage') && can('inventory.location.view') && <Button onClick={() => setSourceEditor(true)}>Siapkan batas penerimaan</Button>}
      <p className="muted">Gunakan batas penerimaan yang sudah tersedia. Tombol ini menyiapkan lokasi baru berkode RECEIPT_SOURCE jika belum ada.</p></div></details>
    <div className="row wrap">{can('inventory.receipt.manage') && <Button variant="primary" disabled={!canDraft} onClick={onNew}>Buat penerimaan</Button>}
      <SelectField label="Status penerimaan" value={status} onChange={(_, data) => { setStatus(data.value); setPage(0) }}><option value="">Semua status</option>{RECEIPT_STATES.map(state => <option key={state} value={state}>{stateLabels[state]}</option>)}</SelectField>
      <TextField label="Serial barang" value={serial} maxLength={128} onChange={(_, data) => { setSerial(data.value); setPage(0) }} hint="Cari serial lengkap." /><Button onClick={result.reload}>Segarkan</Button>
    </div>
    {!can('inventory.receipt.manage') && <p className="muted">Akses baca saja. Izin kelola penerimaan diperlukan untuk mencatat barang dan pemeriksaan.</p>}
    {can('inventory.receipt.manage') && !canDraft && <p className="muted">Pembuatan draft memerlukan izin lihat barang dan lokasi untuk memilih sumber yang benar.</p>}
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada penerimaan dalam cakupan Anda" hint="Siapkan sumber lalu buat draft untuk mencatat barang dari pemasok." />} columns={[
        { key: 'reference', header: 'Referensi', cell: row => <Link to={receiptLink(row.id)}>{row.externalReference}</Link> },
        { key: 'supplier', header: 'Pemasok', cell: row => row.supplierName },
        { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.state} /> },
        { key: 'revision', header: 'Revisi', cell: row => row.revision },
        { key: 'date', header: 'Dibuat', cell: row => <WarehouseTime value={row.createdAt} /> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {sourceEditor && <WarehouseLocationEditor row={null} readOnly={false} preset={{ code: 'RECEIPT_SOURCE', name: 'Penerimaan pemasok', kind: 'TRANSIT', issueEligible: false }} onClose={() => setSourceEditor(false)} onSaved={() => setSourceEditor(false)} onReload={() => setSourceEditor(false)} />}
  </>
}

function ReceiptDetail({ id }: { id: string }) {
  const loader = useCallback(() => getReceipt(id), [id])
  const result = useWarehouseQuery(loader)
  return <><Link to="/warehouse/receipts">Kembali ke daftar penerimaan</Link><WarehouseState {...result}>{receipt => <ReceiptBody key={`${receipt.id}:${receipt.revision}`} receipt={receipt} reload={result.reload} />}</WarehouseState></>
}

function ReceiptBody({ receipt, reload }: { receipt: WarehouseReceipt; reload: () => void }) {
  const { can } = useCan()
  const [edit, setEdit] = useState(false)
  const [action, setAction] = useState<'inspect' | 'putaway' | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseReceipt> | null>(null)
  const manage = can('inventory.receipt.manage')
  if (edit) return <WarehouseReceiptEditor receipt={receipt} onClose={() => setEdit(false)} onSaved={reload} onReload={reload} />
  function saveReference() {
    const lines = receipt.lines.map(line => `${line.skuName} (${line.skuCode}) ${line.serial ?? line.lotCode ?? ''}: ${formatBaseQuantity(line.quantityBase, line.baseUnit)} ${displayUnit(line.baseUnit)}`)
    saveReceiptFile(new Blob([['Penerimaan barang', receipt.externalReference, `Pemasok: ${receipt.supplierName}`, `Status: ${stateLabels[receipt.state]} · Revisi ${receipt.revision}`, `Dibuat: ${receipt.createdAt}`, ...lines, `${window.location.origin}${receiptLink(receipt.id)}`].join('\n')], { type: 'text/plain;charset=utf-8' }), `penerimaan-${receipt.id}-r${receipt.revision}.txt`)
  }
  return <>
    <section className="card stack" aria-label="Detail penerimaan"><h2>{receipt.externalReference}</h2><p>{receipt.supplierName} · <WarehouseStatus status={receipt.state} /> · Revisi {receipt.revision}</p>
      <p>{receipt.sourceLocationName ?? 'Batas penerimaan'} → {receipt.inspectionLocationName ?? 'Lokasi pemeriksaan'}</p>
      <p className="muted"><WarehouseTime value={receipt.createdAt} /> · Referensi audit: {receipt.id}</p>
      <p>{receipt.state === 'DRAFT' ? 'Draft belum menambah stok.' : receipt.state === 'RECEIVED_IN_INSPECTION' ? 'Barang sudah diterima. Bagian yang lolos pemeriksaan masih karantina sampai ditempatkan.' : 'Penerimaan telah selesai; lihat jumlah diterima, ditolak dan ditempatkan di bawah.'}</p>
      <div className="row wrap"><Button onClick={reload}>Muat ulang</Button><Button onClick={saveReference}>Simpan referensi</Button>
        {manage && receipt.state === 'DRAFT' && <><Button disabled={!can('inventory.cost.view') || !receipt.costVisible || !can('inventory.sku.view') || !can('inventory.location.view')} onClick={() => setEdit(true)}>Ubah draft</Button>
          <Button variant="primary" onClick={() => setOperation(receiveReceipt(receipt.id, receipt.revision))}>Terima barang</Button></>}
        {manage && receipt.state === 'RECEIVED_IN_INSPECTION' && <><Button disabled={!receipt.lines.some(line => receiptCandidates(receipt, line, 'inspect').length)} onClick={() => setAction('inspect')}>Periksa barang</Button>
          <Button variant="primary" disabled={!can('inventory.location.view') || !receipt.lines.some(line => receiptCandidates(receipt, line, 'putaway').length)} onClick={() => setAction('putaway')}>Tempatkan ke bin</Button></>}
      </div>
      {manage && receipt.state === 'DRAFT' && (!can('inventory.cost.view') || !receipt.costVisible) && <p className="muted">Ubah draft memerlukan akses rincian biaya agar biaya tersimpan tidak terhapus. Terima barang dan unggah bukti tetap tersedia.</p>}
      {!manage && <p className="muted">Akses baca saja. Izin kelola penerimaan diperlukan untuk memproses dokumen.</p>}
      {manage && receipt.state === 'RECEIVED_IN_INSPECTION' && !can('inventory.location.view') && <p className="muted">Izin lihat lokasi diperlukan untuk memilih bin tujuan.</p>}
    </section>
    <DataTable presentation="warehouse" rows={receipt.lines} rowKey={line => line.id} columns={[
      { key: 'name', header: 'Barang', cell: line => <span>{line.skuName}<br /><span className="muted">{line.skuCode} · {line.serial ?? line.lotCode}</span></span> },
      { key: 'actual', header: 'Jumlah aktual', cell: line => <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> },
      { key: 'accepted', header: 'Diterima inspeksi', cell: line => <WarehouseQuantity value={line.acceptedBase} unit={line.baseUnit} /> },
      { key: 'rejected', header: 'Ditolak', cell: line => <WarehouseQuantity value={line.rejectedBase} unit={line.baseUnit} /> },
      { key: 'putaway', header: 'Ditempatkan', cell: line => <WarehouseQuantity value={line.putawayBase} unit={line.baseUnit} /> },
    ]} />
    <WarehouseReceiptEvidence receipt={receipt} onChanged={reload} />
    {receipt.inspections.length > 0 && <details className="card"><summary>Hasil pemeriksaan tersimpan</summary><ul>{receipt.inspections.map(row => <li key={row.id}>{receipt.lines.find(line => line.id === row.lineId)?.skuName ?? 'Barang penerimaan'}: diterima <WarehouseQuantity value={row.acceptedBase} unit={row.baseUnit} />, ditolak <WarehouseQuantity value={row.rejectedBase} unit={row.baseUnit} /> · {row.reason}</li>)}</ul></details>}
    <ReceiptHistory id={receipt.id} />
    {action && <WarehouseReceiptActions receipt={receipt} mode={action} onClose={() => setAction(null)} onChanged={reload} />}
    {operation && <WarehouseCommandDialog title="Terima barang ke karantina" confirmLabel="Konfirmasi penerimaan" command={operation} onClose={() => setOperation(null)} onDone={reload} onReload={reload}
      summary={<><p>{receipt.externalReference} · {receipt.supplierName} · Revisi {receipt.revision}</p><p>Tujuan: {receipt.inspectionLocationName ?? 'Lokasi pemeriksaan'}</p>
        <ul>{receipt.lines.map(line => <li key={line.id}>{line.skuName} · {line.serial ?? line.lotCode}: <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /></li>)}</ul>
        <p>Jumlah fisik di karantina bertambah sesuai daftar ini. Stok tersedia belum bertambah.</p></>} />}
  </>
}

function ReceiptHistory({ id }: { id: string }) {
  const loader = useCallback(() => getReceiptHistory(id), [id])
  const result = useWarehouseQuery(loader)
  const labels: Record<string, string> = { CREATE: 'Draft dibuat', UPDATE: 'Draft diubah', RECEIVE: 'Barang diterima', INSPECT: 'Pemeriksaan dicatat', PUTAWAY: 'Barang ditempatkan', ATTACHMENT: 'Bukti diunggah' }
  return <details className="card"><summary>Riwayat penerimaan</summary><WarehouseState {...result}>{rows => <WarehouseHistory entries={rows.map(row => ({ id: row.operationId, occurredAt: row.recordedAt, revision: row.revision, label: labels[row.action] ?? row.action }))} />}</WarehouseState></details>
}
