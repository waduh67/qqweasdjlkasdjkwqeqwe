import { useCallback, useState } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { Link, useSearchParams } from 'react-router-dom'
import { getLoan, getLoanIssue, listLoans, loanDecision, loanIssue, loanReturn, loanReview, CLIENT_LABELS, type Loan } from '@/api/warehouse/clientOperations'
import { listReferencePositions } from '@/api/warehouse/referenceStock'
import type { ReferencePosition } from '@/api/warehouse/reference'
import { quantityFromInput, formatBaseQuantity, displayUnit } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { Choice, ClientCommandForm, ClientCreateEditor, ClientList, ClientTimeline } from './WarehouseClientShared'

type LoanLine = Loan['lines'][number]
type LoanStock = LoanLine['stocks'][number]
export function WarehouseLoansPage() {
  const [params, setParams] = useSearchParams(), [creating, setCreating] = useState(false), { can } = useCan(), id = params.get('id')
  return <div className="stack">{id ? <LoanDetail key={id} id={id} onBack={() => setParams({})} /> : <ClientList title="Pinjaman client" subtitle="Persetujuan, penyerahan, dan pengembalian barang yang dipinjam client." load={listLoans} onOpen={id => setParams({ id })} create={can('warehouse.loan.manage') && <Button variant="primary" onClick={() => setCreating(true)}>Ajukan pinjaman</Button>} columns={[{ key: 'borrower', header: 'Peminjam', cell: row => row.borrowerName, description: row => row.contact }, { key: 'date', header: 'Dipinjam', cell: row => row.loanDate }, { key: 'due', header: 'Batas kembali', cell: row => row.dueDate ?? '—' }]} />}
    {creating && <ClientCreateEditor kind="LOAN" onClose={() => setCreating(false)} onSaved={row => { setCreating(false); setParams({ id: row.id }) }} />}
  </div>
}
function LoanDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const result = useWarehouseQuery(useCallback(() => getLoan(id), [id])), { can } = useCan(), manage = can('warehouse.loan.manage')
  const [action, setAction] = useState<'REVIEW' | 'APPROVE' | 'REJECT' | 'ISSUE' | 'PRINT' | null>(null), [returning, setReturning] = useState<{ line: LoanLine; stock: LoanStock } | null>(null)
  const done = () => { setAction(null); setReturning(null); result.reload() }
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang</Button></div><WarehouseState {...result}>{({ document: row, timeline }) => <>
    <div className="card stack"><h2>Pinjaman {row.id.slice(-8)} · {row.borrowerName}</h2><p>{CLIENT_LABELS[row.state]} · {row.warehouseName}</p><p>{row.contact} · {row.loanDate} · Batas kembali: {row.dueDate ?? 'Tidak ditentukan'}</p><p>{row.notes}</p>
      <div className="row wrap">{row.state === 'SUBMITTED' && manage && can('warehouse.request.review') && <><Button variant="primary" onClick={() => setAction('REVIEW')}>Tinjau pinjaman</Button><Button onClick={() => setAction('REJECT')}>Tolak</Button></>}
        {row.state === 'MANAGER_REVIEW' && can('warehouse.request.approve') && <><Button variant="primary" onClick={() => setAction('APPROVE')}>Setujui pinjaman</Button><Button onClick={() => setAction('REJECT')}>Tolak</Button></>}
        {row.state === 'APPROVED' && manage && <Button variant="primary" onClick={() => setAction('ISSUE')}>Serahkan barang</Button>}
        {['ACTIVE', 'PARTIALLY_RETURNED', 'RETURNED'].includes(row.state) && <Button onClick={() => setAction('PRINT')}>Berita acara penyerahan</Button>}
      </div>
    </div>
    <DataTable presentation="warehouse" rows={row.lines} rowKey={line => line.id} columns={[{ key: 'name', header: 'Barang', cell: line => line.skuName }, { key: 'requested', header: 'Diminta', cell: line => <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> }, { key: 'approved', header: 'Disetujui', cell: line => <WarehouseQuantity value={line.approvedBase} unit={line.baseUnit} /> }, { key: 'stock', header: 'Barang yang dipinjam', cell: line => line.stocks.map(stock => <div key={stock.stockIdentityId}><p>{stock.serial ?? 'Stok ' + stock.stockIdentityId.slice(-8)} · Kembali <WarehouseQuantity value={stock.returnedBase} unit={line.baseUnit} /> dari <WarehouseQuantity value={stock.quantityBase} unit={line.baseUnit} /></p>{manage && BigInt(stock.returnedBase) < BigInt(stock.quantityBase) && <Button onClick={() => setReturning({ line, stock })}>Terima kembali {stock.serial ?? line.skuName}</Button>}</div>) }]} />
    <Link to="/warehouse/warranties">Lihat klaim garansi vendor</Link><ClientTimeline events={timeline} />
    {action === 'REVIEW' && <LoanReview row={row} onClose={() => setAction(null)} onDone={done} />}
    {(action === 'APPROVE' || action === 'REJECT') && <LoanDecision row={row} approved={action === 'APPROVE'} onClose={() => setAction(null)} onDone={done} />}
    {action === 'ISSUE' && <LoanIssue row={row} onClose={() => setAction(null)} onDone={done} />}
    {returning && <LoanReturn row={row} {...returning} onClose={() => setReturning(null)} onDone={done} />}
    {action === 'PRINT' && <LoanPrint id={row.id} onClose={() => setAction(null)} />}
  </>}</WarehouseState></>
}
function LoanReview({ row, onClose, onDone }: { row: Loan; onClose: () => void; onDone: () => void }) {
  const [amounts, setAmounts] = useState(() => row.lines.map(line => formatBaseQuantity(line.quantityBase, line.baseUnit))), [notes, setNotes] = useState('')
  return <ClientCommandForm title="Tinjau jumlah pinjaman" onClose={onClose} onDone={onDone} prepare={() => loanReview(row, row.lines.map((line, i) => { const approvedBase = quantityFromInput(amounts[i], line.baseUnit, true); if (BigInt(approvedBase) > BigInt(line.quantityBase)) throw new Error('Jumlah disetujui melebihi pengajuan.'); return { lineId: line.id, approvedBase } }), notes)} summary={<><p>{row.borrowerName}</p>{row.lines.map((line, i) => <p key={line.id}>{line.skuName}: {amounts[i]} {displayUnit(line.baseUnit)}</p>)}<p>{notes}</p><p>{row.requiresManagerApproval ? 'Dilanjutkan ke keputusan Manager.' : 'Pinjaman langsung disetujui setelah tinjauan.'}</p></>}>
    {row.lines.map((line, i) => <WarehouseQuantityField key={line.id} label={'Disetujui: ' + line.skuName} unit={line.baseUnit} value={amounts[i]} onChange={v => setAmounts(amounts => amounts.map((a, j) => j === i ? v : a))} />)}<TextareaField label="Catatan tinjauan" maxLength={1000} value={notes} onChange={(_, d) => setNotes(d.value)} />
  </ClientCommandForm>
}
function LoanDecision({ row, approved, onClose, onDone }: { row: Loan; approved: boolean; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('')
  return <ClientCommandForm title={approved ? 'Setujui pinjaman' : 'Tolak pinjaman'} onClose={onClose} onDone={onDone} prepare={() => { if (!approved && !reason.trim()) throw new Error('Isi alasan penolakan.'); return loanDecision(row, approved, reason) }} summary={<><p>{row.borrowerName}</p><p>{approved ? 'Setujui' : 'Tolak'} · {reason}</p></>}><TextareaField label={approved ? 'Catatan keputusan' : 'Alasan penolakan'} required={!approved} maxLength={1000} value={reason} onChange={(_, d) => setReason(d.value)} /></ClientCommandForm>
}
interface Selected { key: string; position: ReferencePosition | null; quantity: string }
const emptySelection = (): Selected => ({ key: crypto.randomUUID(), position: null, quantity: '' })
function LoanIssue({ row, onClose, onDone }: { row: Loan; onClose: () => void; onDone: () => void }) {
  const [selections, setSelections] = useState<Record<string, Selected[]>>(() => Object.fromEntries(row.lines.filter(l => BigInt(l.approvedBase) > 0n).map(l => [l.id, [emptySelection()]]))), [evidence, setEvidence] = useState('')
  return <ClientCommandForm title="Serahkan barang pinjaman" onClose={onClose} onDone={onDone} prepare={() => {
    const ids = new Set<string>()
    const lines = row.lines.filter(l => BigInt(l.approvedBase) > 0n).map(line => {
      const stocks = selections[line.id].map(s => { if (!s.position) throw new Error('Pilih semua stok penyerahan.'); const quantityBase = quantityFromInput(s.quantity, line.baseUnit)
        if (ids.has(s.position.stockIdentityId) || BigInt(quantityBase) > BigInt(s.position.quantityBase)) throw new Error('Stok dipilih berulang atau jumlah melebihi saldo.'); ids.add(s.position.stockIdentityId)
        return { stockIdentityId: s.position.stockIdentityId, quantityBase } })
      if (stocks.reduce((sum, s) => sum + BigInt(s.quantityBase), 0n) !== BigInt(line.approvedBase)) throw new Error('Jumlah stok harus sama dengan jumlah disetujui untuk ' + line.skuName)
      return { lineId: line.id, stocks }
    })
    if (!evidence.trim()) throw new Error('Isi bukti penyerahan.'); return loanIssue(row, lines, evidence)
  }} summary={<><p>{row.borrowerName} · {row.warehouseName}</p>{row.lines.map(line => <div key={line.id}>{(selections[line.id] ?? []).map(s => <p key={s.key}>{line.skuName} · {s.position?.serial ?? s.position?.stockIdentityId.slice(-8)} · {s.quantity} {displayUnit(line.baseUnit)}</p>)}</div>)}<p>{evidence}</p></>}>
    {row.lines.filter(l => BigInt(l.approvedBase) > 0n).map(line => <fieldset key={line.id} className="card stack"><legend>{line.skuName} · <WarehouseQuantity value={line.approvedBase} unit={line.baseUnit} /></legend>
      {selections[line.id].map(s => <LoanStockChoice key={s.key} row={row} line={line} selection={s} onChange={patch => setSelections(all => ({ ...all, [line.id]: all[line.id].map(item => item.key === s.key ? { ...item, ...patch } : item) }))} onRemove={selections[line.id].length > 1 ? () => setSelections(all => ({ ...all, [line.id]: all[line.id].filter(item => item.key !== s.key) })) : undefined} />)}
      <Button type="button" onClick={() => setSelections(all => ({ ...all, [line.id]: [...all[line.id], emptySelection()] }))}>Tambah pilihan stok {line.skuName}</Button>
    </fieldset>)}<TextareaField label="Bukti / catatan penyerahan" required maxLength={1000} value={evidence} onChange={(_, d) => setEvidence(d.value)} />
  </ClientCommandForm>
}
function LoanStockChoice({ row, line, selection, onChange, onRemove }: { row: Loan; line: LoanLine; selection: Selected; onChange: (patch: Partial<Selected>) => void; onRemove?: () => void }) {
  const load = useCallback(async (search: string, page: number) => { const result = await listReferencePositions(line.skuId, { search, page, locationId: row.warehouseId, holderKind: 'WAREHOUSE', availableOnly: true }); return { ...result, items: result.items.map(p => ({ ...p, id: p.stockIdentityId })) } }, [line.skuId, row.warehouseId])
  return <div className="stack"><WarehousePicker label={'Stok ' + line.skuName} load={load} value={selection.position ? { ...selection.position, id: selection.position.stockIdentityId } : null} name={p => (p.serial ?? p.stockIdentityId.slice(-8)) + ' · ' + formatBaseQuantity(p.quantityBase, p.baseUnit) + ' ' + displayUnit(p.baseUnit)} onChange={position => onChange({ position, quantity: position?.tracking === 'SERIAL' ? '1' : '' })} />
    {selection.position && <WarehouseQuantityField label={'Jumlah stok ' + (selection.position.serial ?? selection.position.stockIdentityId.slice(-8))} unit={line.baseUnit} disabled={line.tracking === 'SERIAL'} value={selection.quantity} onChange={quantity => onChange({ quantity })} />}{onRemove && <Button type="button" onClick={onRemove}>Hapus pilihan</Button>}
  </div>
}
function LoanReturn({ row, line, stock, onClose, onDone }: { row: Loan; line: LoanLine; stock: LoanStock; onClose: () => void; onDone: () => void }) {
  const remaining = (BigInt(stock.quantityBase) - BigInt(stock.returnedBase)).toString(), [quantity, setQuantity] = useState(formatBaseQuantity(remaining, line.baseUnit)), [condition, setCondition] = useState('SERVICEABLE'), [serial, setSerial] = useState(''), [evidence, setEvidence] = useState('')
  return <ClientCommandForm title={'Terima kembali ' + line.skuName} onClose={onClose} onDone={onDone} prepare={() => { const quantityBase = quantityFromInput(quantity, line.baseUnit)
    if (BigInt(quantityBase) > BigInt(remaining)) throw new Error('Jumlah melebihi barang yang masih dipinjam.')
    if (line.tracking === 'SERIAL' && serial.trim().toUpperCase() !== stock.serial?.trim().toUpperCase()) throw new Error('Serial fisik harus sesuai barang pinjaman.')
    if (!evidence.trim()) throw new Error('Isi bukti pengembalian.'); return loanReturn(row, [{ lineId: line.id, stockIdentityId: stock.stockIdentityId, quantityBase, condition: condition as 'SERVICEABLE' | 'DAMAGED', ...(serial ? { observedSerial: serial } : {}) }], evidence)
  }} summary={<><p>{row.borrowerName} · {line.skuName} · {stock.serial} · {quantity} {displayUnit(line.baseUnit)}</p><p>{condition === 'DAMAGED' ? 'Rusak: masuk karantina dan dibuat klaim garansi vendor.' : 'Baik: kembali ke stok tersedia.'}</p><p>{evidence}</p></>}>
    <p>Masih dipinjam: <WarehouseQuantity value={remaining} unit={line.baseUnit} /></p><WarehouseQuantityField label="Jumlah kembali" unit={line.baseUnit} disabled={line.tracking === 'SERIAL'} value={quantity} onChange={setQuantity} />
    <Choice label="Kondisi kembali" value={condition} onChange={setCondition} values={[[ 'SERVICEABLE', 'Baik / layak pakai' ], [ 'DAMAGED', 'Rusak / klaim garansi' ]]} />
    {line.tracking === 'SERIAL' && <TextField label="Serial yang dibaca dari perangkat" required value={serial} onChange={(_, d) => setSerial(d.value)} />}
    <TextareaField label="Bukti / kondisi pengembalian" required maxLength={1000} value={evidence} onChange={(_, d) => setEvidence(d.value)} />
  </ClientCommandForm>
}
function LoanPrint({ id, onClose }: { id: string; onClose: () => void }) {
  const result = useWarehouseQuery(useCallback(() => getLoanIssue(id), [id])), [printable, setPrintable] = useState<Loan | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  async function print() { setBusy(true); setError(''); try { const fresh = await getLoanIssue(id); flushSync(() => setPrintable(fresh)); window.print() } catch (e) { setError(e instanceof Error ? e.message : 'Dokumen tidak dapat dicetak.') } finally { setPrintable(null); setBusy(false) } }
  return <section className="card stack"><Button onClick={onClose}>Tutup berita acara</Button><WarehouseState {...result}>{row => <><LoanPrintContent row={row} /><Button disabled={busy} onClick={() => void print()}>Cetak berita acara</Button></>}</WarehouseState>{error && <p role="alert" className="error">{error}</p>}{printable && createPortal(<div className="warehouse-issue-print"><LoanPrintContent row={printable} /></div>, document.body)}</section>
}
function LoanPrintContent({ row }: { row: Loan }) { return <div className="stack"><h2>Berita acara penyerahan barang pinjaman</h2><p>Nomor: {row.id} · Tanggal penyerahan: {new Date(row.updatedAt).toLocaleString('id-ID')}</p><p>Peminjam: {row.borrowerName} · {row.contact}</p><p>Gudang: {row.warehouseName} · Batas kembali: {row.dueDate ?? 'Tidak ditentukan'}</p>{row.lines.map(line => <div key={line.id}><strong>{line.skuName}</strong>{line.stocks.map(stock => <p key={stock.stockIdentityId}>{stock.serial ?? stock.stockIdentityId} · <WarehouseQuantity value={stock.quantityBase} unit={line.baseUnit} /></p>)}</div>)}<p>{row.notes}</p><p>{row.issueEvidence}</p><div className="row" style={{ marginTop: 48, justifyContent: 'space-between' }}><p>Diserahkan oleh<br /><br /><br />{row.issuedBy || '____________________'}</p><p>Diterima oleh<br /><br /><br />{row.borrowerName}</p></div></div> }
