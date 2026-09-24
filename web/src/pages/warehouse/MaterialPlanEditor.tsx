import { useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { MaterialPlan, MaterialSummary } from '@/api/warehouse/materialModels'
import { saveMaterialPlan, type MaterialPlanInput } from '@/api/warehouse/materials'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildMaterialPlan, emptyMaterialRow, rowsFromMaterialPlan, type MaterialDraftRow, type MaterialSkuChoice } from './materialDraft'
import { receiptSkus } from './receiptChoices'

/** The caller supplies an authorized, freshly read work-order context and remounts after reload. */
export function MaterialPlanEditor({ summary, onSaved, onClose, onReload }: { summary: MaterialSummary; onSaved: () => void; onClose: () => void; onReload: () => void }) {
  const { can } = useCan()
  const [mode, setMode] = useState<MaterialPlanInput['materialMode']>(summary.plan?.materialMode ?? 'MATERIAL_REQUIRED')
  const [reason, setReason] = useState(summary.plan?.reason ?? '')
  const [rows, setRows] = useState<MaterialDraftRow[]>(() => summary.plan?.lines.length ? rowsFromMaterialPlan(summary.plan.lines) : [emptyMaterialRow()])
  const [review, setReview] = useState<{ input: MaterialPlanInput; command: WarehouseCommand<MaterialPlan> } | null>(null)
  const [error, setError] = useState<string | null>(null)
  function update(key: string, patch: Partial<MaterialDraftRow>) { setRows(current => current.map(row => row.key === key ? { ...row, ...patch } : row)) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const input = buildMaterialPlan(summary, mode, reason, rows, can('inventory.request.override'))
      setReview({ input, command: saveMaterialPlan(summary.workOrderId, input) }); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa rencana material.') }
  }
  return <>
    <form className="card stack" onSubmit={prepare} aria-label="Rencana material">
      <h2>{summary.plan ? 'Revisi rencana material' : 'Rencana material baru'}</h2>
      <p className="muted">Revisi rencana {summary.revisions.planRevision} · Menyimpan rencana belum mencadangkan stok.</p>
      <SelectField label="Kebutuhan material" value={mode} onChange={(_, data) => setMode(data.value as MaterialPlanInput['materialMode'])}>
        <option value="MATERIAL_REQUIRED">Membutuhkan material</option><option value="NONE">Tanpa material</option>
      </SelectField>
      <TextareaField label={mode === 'NONE' ? 'Alasan tanpa material' : 'Catatan rencana'} required={mode === 'NONE'} value={reason} maxLength={1000} onChange={(_, data) => setReason(data.value)} />
      {mode === 'MATERIAL_REQUIRED' && (can('inventory.sku.view') ? <>
        {summary.template && !summary.plan && <div className="stack"><p>Template {summary.template.workType} tersedia. Salin dan tinjau jumlah sebelum menyimpan.</p><Button type="button" onClick={() => setRows(rowsFromMaterialPlan(summary.template!.lines, false))}>Salin template</Button></div>}
        {rows.map((row, index) => <fieldset key={row.key} className="stack" style={{ minWidth: 0 }}><legend>Material {index + 1}</legend>
          <WarehousePicker<MaterialSkuChoice> label={`Material ${index + 1}`} load={receiptSkus} value={row.sku} name={sku => `${sku.name} · ${sku.code}`}
            eligible={sku => !row.previous || sku.id === row.previous.sku.id || (can('inventory.request.override') && sku.tracking === row.previous.sku.tracking && sku.baseUnit === row.previous.sku.baseUnit)}
            onChange={sku => update(row.key, { sku, substitutionReason: '', ...(sku?.baseUnit !== row.sku?.baseUnit ? { quantity: '', continuousCut: false } : {}) })} />
          {row.sku && <><WarehouseQuantityField label={`Kebutuhan material ${index + 1}`} unit={row.sku.baseUnit} value={row.quantity} onChange={quantity => update(row.key, { quantity })} />
            {row.sku.baseUnit === 'MM' && <Checkbox label="Harus satu potongan utuh" checked={row.continuousCut} onChange={(_, data) => update(row.key, { continuousCut: data.checked === true })} />}
            {row.previous && row.previous.sku.id !== row.sku.id && <div className="stack"><p>Mengganti <strong>{row.previous.sku.name}</strong> dengan <strong>{row.sku.name}</strong>.</p><TextareaField label={`Alasan substitusi material ${index + 1}`} required maxLength={1000} value={row.substitutionReason} onChange={(_, data) => update(row.key, { substitutionReason: data.value })} /></div>}
          </>}
          {rows.length > 1 && <Button type="button" onClick={() => setRows(current => current.filter(item => item.key !== row.key))}>Hapus material {index + 1}</Button>}
        </fieldset>)}
        <Button type="button" disabled={rows.length >= 100} onClick={() => setRows(current => [...current, emptyMaterialRow()])}>Tambah material</Button>
      </> : <p role="alert">Izin lihat barang diperlukan untuk menyusun baris material.</p>)}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary" disabled={mode === 'MATERIAL_REQUIRED' && !can('inventory.sku.view')}>Tinjau rencana</Button></div>
    </form>
    {review && <WarehouseCommandDialog title="Simpan rencana material" confirmLabel="Simpan rencana" command={review.command} onDone={onSaved} onClose={() => setReview(null)} onReload={onReload}
      summary={<><p>Revisi rencana {review.input.expectedRevision} → {review.input.expectedRevision + 1}</p>{review.input.materialMode === 'NONE' ? <p>Tanpa material: {review.input.reason}</p> : <ul>{review.input.lines?.map((line, index) => <li key={line.skuId}><strong>{rows[index].sku?.name}</strong>: <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} />{line.continuousCut && ' · satu potongan utuh'}{line.substitution && <p>Substitusi {rows[index].previous?.sku.name}: {line.substitution.reason}</p>}</li>)}</ul>}<p>Setelah disimpan, ajukan permintaan untuk mulai mencadangkan stok.</p></>} />}
  </>
}
