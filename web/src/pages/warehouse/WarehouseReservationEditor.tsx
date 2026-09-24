import { useCallback, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { MaterialSummary } from '@/api/warehouse/materialModels'
import { materialRequestAction } from '@/api/warehouse/materials'
import { displayUnit, formatBaseQuantity } from '@/api/warehouse/quantity'
import { listPositions, type StockPosition } from '@/api/warehouse/stock'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildReservation, type ReserveDraft } from './materialActions'

type ReservationResult = Awaited<ReturnType<ReturnType<typeof materialRequestAction>['execute']>>
export function WarehouseReservationEditor({ summary, onDone, onClose }: { summary: MaterialSummary; onDone: () => void; onClose: () => void }) {
  const { can } = useCan()
  const planLines = summary.plan?.lines ?? []
  const [drafts, setDrafts] = useState<ReserveDraft[]>(() => summary.lines.filter(line => planLines.some(p => p.id === line.planLineId) && BigInt(line.backorderBase) > 0n).map(line => ({ planLineId: line.planLineId, selected: false, quantity: formatBaseQuantity(line.backorderBase, line.baseUnit), position: null })))
  const [reason, setReason] = useState('')
  const [operation, setOperation] = useState<WarehouseCommand<ReservationResult> | null>(null)
  const [error, setError] = useState<string | null>(null)
  function update(id: string, patch: Partial<ReserveDraft>) { setDrafts(current => current.map(row => row.planLineId === id ? { ...row, ...patch } : row)) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { setOperation(materialRequestAction(summary.demandDocumentId!, 'reserve', buildReservation(summary, drafts, reason, can('inventory.request.override')))); setError(null) }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa pilihan reservasi.') }
  }
  return <><form className="card stack" onSubmit={prepare} aria-label="Reservasi sebagian">
    <h2>Reservasi sebagian / pilih stok</h2><p>Pilih baris dan jumlah yang dicadangkan. Sisa kebutuhan tetap tercatat sebagai kekurangan.</p>
    {drafts.map(row => {
      const line = planLines.find(line => line.id === row.planLineId)!
      return <fieldset key={row.planLineId} className="stack" style={{ minWidth: 0 }}><legend>{line.sku.name}</legend>
        <Checkbox label={`Cadangkan ${line.sku.name}`} checked={row.selected} onChange={(_, data) => update(row.planLineId, { selected: data.checked === true })} />
        {row.selected && <><WarehouseQuantityField label={`Reservasi ${line.sku.name}`} unit={line.sku.baseUnit} value={row.quantity} onChange={quantity => update(row.planLineId, { quantity })} />
          {line.continuousCut && <p>Harus berasal dari satu potongan utuh; sisa reel tidak digabung.</p>}
          {can('inventory.request.override') && can('inventory.item.view') ? <ReservationPosition skuId={line.sku.id} value={row.position} onChange={position => update(row.planLineId, { position })} /> : <p className="muted">Stok dipilih otomatis menurut urutan penerimaan. Pemilihan identitas memerlukan izin override dan lihat stok.</p>}
        </>}
      </fieldset>
    })}
    {drafts.some(row => row.selected && row.position) && <TextareaField label="Alasan pemilihan stok" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">Tinjau reservasi</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title="Cadangkan stok" confirmLabel="Cadangkan pilihan" command={operation} onDone={onDone} onClose={() => setOperation(null)} onReload={onDone}
      summary={<><p>Revisi permintaan {summary.demandRevision}. Stok fisik belum berpindah.</p><ul>{drafts.filter(row => row.selected).map(row => <li key={row.planLineId}>{planLines.find(line => line.id === row.planLineId)?.sku.name}: {row.quantity} {displayUnit(planLines.find(line => line.id === row.planLineId)!.sku.baseUnit)} · {row.position ? positionLabel(row.position) : 'Pilihan otomatis FIFO'}</li>)}</ul>{reason && <p>{reason}</p>}<p>Hasil reservasi dan kekurangan akan dimuat ulang setelah transaksi.</p></>} />}
  </>
}
function positionLabel(row: StockPosition) { return `${row.name} · ${row.serial ?? `Potongan ${row.stockIdentityId.slice(0, 8)}`} · ${row.locationName ?? 'Lokasi tanpa nama'} · ${formatBaseQuantity(row.available.quantityBase, row.available.baseUnit)} ${displayUnit(row.available.baseUnit)}` }
function ReservationPosition({ skuId, value, onChange }: { skuId: string; value: StockPosition | null; onChange: (value: StockPosition | null) => void }) {
  const load = useCallback((serial: string, page: number) => listPositions({ skuId, bucket: 'AVAILABLE', condition: 'SERVICEABLE', serial: serial.trim() || undefined, page }), [skuId])
  return <div className="stack"><WarehousePicker label="Serial atau potongan stok" load={load} value={value} onChange={onChange} optional name={positionLabel}
    eligible={row => row.custodianKind === 'WAREHOUSE' && row.legalOwner === 'ISP' && row.status === 'AVAILABLE' && BigInt(row.available.quantityBase) > 0n} />
    <p className="muted">Kosongkan pilihan untuk FIFO. Pencarian memakai serial lengkap.</p>
    {value && <p style={{ overflowWrap: 'anywhere' }}>Identitas potongan: {value.stockIdentityId} · Tersedia <WarehouseQuantity value={value.available.quantityBase} unit={value.available.baseUnit} /></p>}
  </div>
}
