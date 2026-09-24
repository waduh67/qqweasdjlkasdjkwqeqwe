import { useCallback, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { MaterialFieldContext } from '@/api/warehouse/materialExecution'
import { appendMaterialRework, getMaterialReworkBasis, type MaterialReworkInput } from '@/api/warehouse/materialReview'
import { quantityFromInput } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { emptyMaterialRow, type MaterialDraftRow, type MaterialSkuChoice } from '@/pages/warehouse/materialDraft'
import { receiptSkus } from '@/pages/warehouse/receiptChoices'

export function WorkOrderMaterialRework({ context, onDone, onClose }: { context: MaterialFieldContext; onDone: () => void; onClose: () => void }) {
  const loader = useCallback(() => getMaterialReworkBasis(context.workOrderId), [context.workOrderId]), result = useWarehouseQuery(loader)
  return <section className="card stack" aria-label="Tambahan rencana pengerjaan ulang"><Button onClick={onClose}>Tutup pengerjaan ulang</Button><WarehouseState {...result}>{basis =>
    basis.previousPlanId !== context.plan?.id || basis.expectedUsageRevision !== context.useRevision ? <div role="alert"><p>Rencana atau pemakaian sudah berubah. Muat ulang sebelum menyusun tambahan.</p><Button onClick={onDone}>Muat ulang material</Button></div> :
      <ReworkForm context={context} basis={basis} onDone={onDone} />}</WarehouseState></section>
}
function ReworkForm({ context, basis, onDone }: { context: MaterialFieldContext; basis: Awaited<ReturnType<typeof getMaterialReworkBasis>>; onDone: () => void }) {
  const [rows, setRows] = useState([emptyMaterialRow()]), [reason, setReason] = useState(''), [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<{ input: MaterialReworkInput; command: WarehouseCommand<unknown> } | null>(null)
  function update(key: string, patch: Partial<MaterialDraftRow>) { setRows(previous => previous.map(row => row.key === key ? { ...row, ...patch } : row)) }
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (!reason.trim() || reason.trim().length > 1000) throw new Error('Isi alasan pengerjaan ulang, maksimal 1000 karakter.')
      const seen = new Set<string>()
      const deltas = rows.map(row => {
        if (!row.sku || seen.has(row.sku.id)) throw new Error('Pilih barang tambahan yang berbeda pada setiap baris.')
        seen.add(row.sku.id)
        return { skuId: row.sku.id, quantityBase: quantityFromInput(row.quantity, row.sku.baseUnit), baseUnit: row.sku.baseUnit, continuousCut: row.sku.baseUnit === 'MM' && row.continuousCut }
      })
      const input = { ...basis, reason: reason.trim(), deltas }
      setReview({ input, command: appendMaterialRework(context.workOrderId, input) }); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa kebutuhan tambahan.') }
  }
  return <><form className="stack" onSubmit={submit}><h3>Kebutuhan tambahan setelah penolakan QA</h3>
    <p>Rencana {basis.expectedRevision} dan pemakaian {basis.expectedUsageRevision} tetap tersimpan. Isi kebutuhan tambahan; jumlah lama tidak dikurangi atau diganti.</p>
    <p>{basis.previousEvidenceRevision === basis.evidenceRevision ? 'Bukti masih sama dengan pengajuan sebelumnya.' : 'Bukti pengerjaan sudah berubah dari pengajuan sebelumnya.'} <a href="#work-order-evidence">Tinjau bukti pengerjaan</a> sebelum menyimpan.</p>
    {rows.map((row, index) => <fieldset key={row.key} className="stack" style={{ minWidth: 0 }}><legend>Kebutuhan tambahan {index + 1}</legend>
      <WarehousePicker<MaterialSkuChoice> label={`Barang tambahan ${index + 1}`} load={receiptSkus} value={row.sku} name={sku => `${sku.name} · ${sku.code}`} onChange={sku => update(row.key, { sku, quantity: '', continuousCut: false })} />
      {row.sku && <><WarehouseQuantityField label={`Jumlah tambahan ${index + 1}`} unit={row.sku.baseUnit} value={row.quantity} onChange={quantity => update(row.key, { quantity })} />
        {row.sku.baseUnit === 'MM' && <Checkbox label={`Tambahan ${index + 1} harus satu potongan utuh`} checked={row.continuousCut} onChange={(_, value) => update(row.key, { continuousCut: value.checked === true })} />}</>}
      {rows.length > 1 && <Button onClick={() => setRows(previous => previous.filter(item => item.key !== row.key))}>Hapus tambahan {index + 1}</Button>}
    </fieldset>)}
    <Button disabled={rows.length >= 100} onClick={() => setRows(previous => [...previous, emptyMaterialRow()])}>Tambah jenis barang</Button>
    <TextareaField label="Alasan kebutuhan tambahan" required maxLength={1000} value={reason} onChange={(_, value) => setReason(value.value)} />
    {error && <p role="alert">{error}</p>}<Button variant="primary" type="submit">Tinjau tambahan rencana</Button>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi tambahan rencana" command={review.command} confirmLabel="Simpan kebutuhan tambahan" onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>Rencana {basis.expectedRevision} → {basis.expectedRevision + 1} · WO revisi {basis.workOrderRevision} · Pemakaian sebelumnya {basis.expectedUsageRevision}</p>
    <p>{review.input.reason}</p><ul>{review.input.deltas.map((line, index) => <li key={line.skuId}>{rows[index].sku?.name}: tambah <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /></li>)}</ul>
    <p>Rencana tambahan langsung diajukan untuk proses permintaan gudang. Pemakaian lama dan bukti pengajuan sebelumnya tetap terhubung.</p>
  </>} />}</>
}
