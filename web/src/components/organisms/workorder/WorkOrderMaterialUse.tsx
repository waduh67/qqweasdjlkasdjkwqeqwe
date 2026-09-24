import { useCallback, useRef, useState, type FormEvent } from 'react'
import { appendMaterialUse, getMaterialCustody, reportMaterialUse, type MaterialFieldContext } from '@/api/warehouse/materialExecution'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { emptyUseDraft, eligibleUseSource, materialUseInput, type MaterialUseDraft } from './materialUseDraft'

export function WorkOrderMaterialUse({ context, onDone, onClose, online = true, prepare, evidenceHref = '#work-order-evidence' }: {
  context: MaterialFieldContext; onDone: () => void; onClose: () => void; online?: boolean;
  prepare?: (rows: MaterialUseDraft[]) => Promise<void>; evidenceHref?: string | null
}) {
  const [rows, setRows] = useState([emptyUseDraft()]), [evidence, setEvidence] = useState(''), [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false), active = useRef(false)
  const [error, setError] = useState<string | null>(null), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null)
  const load = useCallback((_: string, page: number) => getMaterialCustody(context.workOrderId, page), [context.workOrderId])
  const none = context.plan?.materialMode === 'NONE', correction = context.useRevision > 0
  function update(key: string, patch: Partial<MaterialUseDraft>) { setRows(previous => previous.map(row => row.key === key ? { ...row, ...patch } : row)) }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!online || active.current) return
    try {
      const input = materialUseInput(context, rows, evidence, reason)
      active.current = true; setBusy(true); setError(null)
      await prepare?.(rows)
      setReview('lines' in input ? reportMaterialUse(context.workOrderId, input) : appendMaterialUse(context.workOrderId, input))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa pemakaian material.') }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label="Catatan pemakaian material" onSubmit={event => void submit(event)}>
    <fieldset disabled={busy || !!review} className="stack" style={{ minWidth: 0, border: 0, padding: 0, margin: 0 }}><h3>{none ? 'Deklarasi tanpa material' : correction ? 'Tambahan pemakaian terukur' : 'Pemakaian material terukur'}</h3>
    <p>Rencana {context.plan?.planRevision} · Pemakaian revisi {context.useRevision}. Isi jumlah yang benar-benar digunakan. Catatan yang diterima server tidak dapat ditimpa.</p>
    {none ? <p>Rencana tanpa material: {context.plan?.reason}</p> : <>{rows.map((row, index) => <fieldset key={row.key} className="stack" style={{ minWidth: 0 }}><legend>Pemakaian {index + 1}</legend>
      <WarehousePicker label={`Barang diterima ${index + 1}`} searchable={false} load={load} value={row.source} onChange={source => update(row.key, { source, quantity: '' })} eligible={source => eligibleUseSource(context, source)} name={source => `${source.sku.name} · ${source.serial ?? source.lotCode ?? source.issueCode} · ${source.issueCode}`} />
      {row.source && <><p>Di tangan Anda: <WarehouseQuantity value={row.source.quantityBase} unit={row.source.baseUnit} /> · {row.source.location.name ?? row.source.location.code}</p><WarehouseQuantityField label={`Jumlah dipakai ${index + 1}`} unit={row.source.baseUnit} value={row.quantity} onChange={quantity => update(row.key, { quantity })} /></>}
      {rows.length > 1 && <Button onClick={() => setRows(previous => previous.filter(item => item.key !== row.key))}>Hapus pemakaian {index + 1}</Button>}
    </fieldset>)}{!correction && <Button disabled={rows.length >= 100} onClick={() => setRows(previous => [...previous, emptyUseDraft()])}>Tambah barang dipakai</Button>}
    <p>Perangkat berserial dipasang melalui aset pelanggan. Sisa barang tetap menjadi tanggung jawab pemegangnya sampai dikembalikan atau diserahterimakan.</p></>}
    <TextField label="Referensi bukti pemakaian" required maxLength={500} value={evidence} onChange={(_, value) => setEvidence(value.value)} hint="Nomor catatan pengukuran atau keterangan bukti pada WO ini." />
    {evidenceHref && <a href={evidenceHref}>Lihat atau unggah bukti pengerjaan</a>}
    <TextareaField label={none ? 'Alasan tidak memakai material' : correction ? 'Alasan tambahan pemakaian' : 'Catatan pemakaian'} required={none || correction} maxLength={1000} value={reason} onChange={(_, value) => setReason(value.value)} />
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button onClick={onClose}>Batal</Button><Button type="submit" variant="primary" disabled={!online}>{busy ? 'Memeriksa sumber…' : 'Tinjau pemakaian'}</Button></div></fieldset>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi pemakaian material" command={review} disabled={!online} confirmLabel="Catat pemakaian" onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>WO revisi {context.workOrderRevision} · Rencana {context.plan?.planRevision} · Pemakaian revisi {context.useRevision}</p>
    {none ? <p>Tanpa material: {reason}</p> : <ul>{rows.map(row => <li key={row.key}>{row.source?.sku.name} · {row.quantity} {row.source?.baseUnit === 'MM' ? 'm' : 'unit'} · {row.source?.serial ?? row.source?.lotCode}</li>)}</ul>}
    <p>Bukti: {evidence}</p><p>Jumlah ini dicatat sebagai pemakaian baru. Persetujuan QA berikutnya memakai catatan ini tanpa mengurangi stok lagi.</p>
  </>} />}</>
}
