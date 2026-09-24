import { useRef, useState, type FormEvent } from 'react'
import { acknowledgeMyMaterial, getMyMaterialContext, getMyMaterialIssue, type MyMaterialContext, type MyMaterialIssue } from '@/api/warehouse/myMaterials'
import { warehouseError } from '@/api/warehouse/errors'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { MaterialScanner } from '@/components/organisms/warehouse/MaterialScanner'
import { myReceiptInput } from './myMaterialDraft'

export function MyMaterialReceipt({ context, issue, actor, online, onDone, onClose }: {
  context: MyMaterialContext; issue: MyMaterialIssue; actor: string; online: boolean; onDone: () => void; onClose: () => void
}) {
  const [lineId, setLineId] = useState(''), [serial, setSerial] = useState<string | null>(null), [accepted, setAccepted] = useState('')
  const [missing, setMissing] = useState('0'), [rejected, setRejected] = useState('0'), [reason, setReason] = useState(''), [reference, setReference] = useState('')
  const [error, setError] = useState<string | null>(null), [busy, setBusy] = useState(false), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null), active = useRef(false)
  const source = issue.lines.find(line => line.id === lineId), pending = issue.lines.filter(line => line.remainingBase !== '0')
  function scan(raw: string) {
    const matches = pending.filter(line => line.serial === raw.trim().toUpperCase())
    if (matches.length !== 1) { setSerial(null); setError('Serial tidak cocok dengan barang yang menunggu penerimaan pada dokumen ini.'); return }
    setLineId(matches[0].id); setSerial(matches[0].serial); setAccepted(''); setError(null)
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!online || active.current) return
    try {
      myReceiptInput(context, issue, actor, lineId, accepted, missing, rejected, reason, reference, serial)
      active.current = true; setBusy(true); setError(null)
      const [freshContext, freshIssue] = await Promise.all([getMyMaterialContext(context.id), getMyMaterialIssue(context.id, issue.id)])
      if (freshIssue.revision !== issue.revision || freshContext.workOrderRevision !== context.workOrderRevision) throw new Error('Pengiriman atau akses berubah. Muat ulang dokumen sebelum meninjau lagi.')
      setReview(acknowledgeMyMaterial(context.id, myReceiptInput(freshContext, freshIssue, actor, lineId, accepted, missing, rejected, reason, reference, serial)))
    } catch (caught) { setError(caught instanceof Error ? caught.message : warehouseError(caught)) }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label="Penerimaan material saya" onSubmit={event => void submit(event)}>
    <h3>Terima {issue.code}</h3><p>Pengirim: {issue.sender.name} · Penerima: {issue.receiver.name} · Revisi {issue.revision}</p>
    <p>Draf di tab ini. Stok baru menjadi milik tanggung jawab Anda setelah penerimaan dikonfirmasi server.</p>
    {pending.some(line => line.serial) && <MaterialScanner onScan={scan} disabled={busy || !!review} />}
    <SelectField label="Barang yang diterima" required value={lineId} disabled={busy || !!review} onChange={(_, data) => { setLineId(data.value); setSerial(null); setAccepted('') }}>
      <option value="">Pilih…</option>{pending.map(line => <option key={line.id} value={line.id}>{line.sku.name} · {line.serial ?? line.lotCode ?? line.sku.code}</option>)}
    </SelectField>
    {source && <><p>Belum diterima: <WarehouseQuantity value={source.remainingBase} unit={source.baseUnit} /></p>
      {serial && <p role="status">Serial cocok: {serial}</p>}
      <WarehouseQuantityField label="Jumlah diterima" unit={source.baseUnit} value={accepted} onChange={setAccepted} disabled={busy || !!review} />
      <WarehouseQuantityField label="Jumlah kurang" unit={source.baseUnit} value={missing} onChange={setMissing} allowZero disabled={busy || !!review} />
      <WarehouseQuantityField label="Jumlah ditolak" unit={source.baseUnit} value={rejected} onChange={setRejected} allowZero disabled={busy || !!review} />
      <p>Jumlah kurang atau ditolak tetap tercatat dalam perjalanan untuk ditindaklanjuti petugas. Penerimaan ini memerlukan jumlah diterima lebih dari nol.</p></>}
    <TextareaField label="Alasan selisih" value={reason} maxLength={1000} disabled={busy || !!review} onChange={(_, data) => setReason(data.value)} />
    <TextField label="Referensi bukti penerimaan" required value={reference} maxLength={500} disabled={busy || !!review} onChange={(_, data) => setReference(data.value)} />
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button disabled={busy || !!review} onClick={onClose}>Batal</Button><Button disabled={busy || !!review || !online} onClick={onDone}>Muat ulang dokumen</Button><Button type="submit" variant="primary" disabled={busy || !!review || !online}>{busy ? 'Memeriksa pengiriman…' : 'Tinjau penerimaan'}</Button></div>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi penerimaan material" command={review} disabled={!online} confirmLabel="Terima material" onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{issue.code} · Revisi {issue.revision} · {source?.sku.name} · {source?.serial ?? source?.lotCode}</p>
    <p>Diterima {accepted}, kurang {missing}, ditolak {rejected} {source?.baseUnit === 'MM' ? 'm' : 'unit'}.</p><p>Bukti: {reference}</p>
  </>} />}</>
}
