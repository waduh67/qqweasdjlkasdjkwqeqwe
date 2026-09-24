import { useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { MaterialSummary } from '@/api/warehouse/materialModels'
import { materialRequestAction, pickMaterials, type PickInput, type ReservationCommand } from '@/api/warehouse/materials'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import type { ReservationAllocation } from '@/api/warehouse/reservations'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildAllocationCommand, type AllocationDraft } from './materialActions'

export function WarehouseAllocationEditor({ summary, allocations, action, onDone, onClose }: { summary: MaterialSummary; allocations: ReservationAllocation[]; action: 'pick' | 'release'; onDone: () => void; onClose: () => void }) {
  const [drafts, setDrafts] = useState<AllocationDraft[]>(() => allocations.filter(row => row.reservedPickedBase === '0' && BigInt(row.reservedUnpickedBase) > 0n).map(allocation => ({ allocation, quantity: formatBaseQuantity(allocation.reservedUnpickedBase, allocation.baseUnit), scan: '', selected: false })))
  const [reason, setReason] = useState('')
  const [operation, setOperation] = useState<WarehouseCommand<unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)
  function update(id: string, patch: Partial<AllocationDraft>) { setDrafts(current => current.map(row => row.allocation.id === id ? { ...row, ...patch } : row)) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const input = buildAllocationCommand(summary, drafts, action, reason)
      setOperation(action === 'pick' ? pickMaterials(summary.workOrderId, input as PickInput) : materialRequestAction(summary.demandDocumentId!, 'release', input as ReservationCommand)); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa pilihan alokasi.') }
  }
  return <><form className="card stack" onSubmit={prepare} aria-label={action === 'pick' ? 'Siapkan barang' : 'Lepas reservasi'}>
    <h2>{action === 'pick' ? 'Siapkan barang dari reservasi' : 'Lepas reservasi yang belum disiapkan'}</h2>
    <p>{action === 'pick' ? 'Pilih serial / potongan dan jumlah fisik yang disiapkan. Kabel dapat dipotong; batal siapkan tidak menyambung kembali potongannya.' : 'Barang kembali tersedia untuk permintaan lain. Barang yang terikat slip perlu dibatalkan persiapannya melalui slip terlebih dahulu.'}</p>
    {drafts.map(row => <fieldset key={row.allocation.id} className="stack" style={{ minWidth: 0 }}><legend>{row.allocation.skuName ?? 'Barang reservasi'} · {row.allocation.serial ?? row.allocation.lotCode ?? 'Identitas stok'}</legend>
      <p>{row.allocation.locationName ?? 'Lokasi tanpa nama'} · Dicadangkan <WarehouseQuantity value={row.allocation.reservedUnpickedBase} unit={row.allocation.baseUnit} /></p>
      <p className="muted" style={{ overflowWrap: 'anywhere' }}>Potongan / unit: {row.allocation.stockIdentityId}</p>
      <Checkbox label={`Pilih ${row.allocation.serial ?? row.allocation.lotCode ?? row.allocation.skuName ?? row.allocation.stockIdentityId}`} checked={row.selected} onChange={(_, data) => update(row.allocation.id, { selected: data.checked === true })} />
      {row.selected && <><WarehouseQuantityField label={action === 'pick' ? 'Jumlah disiapkan' : 'Jumlah dilepas'} unit={row.allocation.baseUnit} value={row.quantity} onChange={quantity => update(row.allocation.id, { quantity })} />
        {action === 'pick' && row.allocation.serial && <TextField label={`Pindai ${row.allocation.serial}`} value={row.scan} maxLength={128} onChange={(_, data) => update(row.allocation.id, { scan: data.value })} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} hint="Opsional. Jika dipindai, serial harus cocok dengan barang yang dipilih." />}</>}
    </fieldset>)}
    {drafts.length === 0 && <p>Tidak ada reservasi yang dapat dipilih. Muat ulang permintaan atau batalkan persiapan slip yang masih aktif.</p>}
    {action === 'release' && <TextareaField label="Alasan pelepasan reservasi" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary" disabled={!drafts.length}>Tinjau pilihan</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title={action === 'pick' ? 'Konfirmasi persiapan barang' : 'Konfirmasi pelepasan reservasi'} confirmLabel={action === 'pick' ? 'Siapkan pilihan' : 'Lepas pilihan'} command={operation} onDone={onDone} onClose={() => setOperation(null)} onReload={onDone}
      summary={<><p>Revisi permintaan {summary.demandRevision} · Rencana {summary.revisions.planRevision}</p><ul>{drafts.filter(row => row.selected).map(row => <li key={row.allocation.id}>{row.allocation.skuName} · {row.allocation.serial ?? row.allocation.lotCode} · {row.allocation.locationName}: {row.quantity} {row.allocation.baseUnit === 'MM' ? 'm' : 'unit'}</li>)}</ul><p>{action === 'pick' ? 'Barang tetap berada di gudang sampai slip dikirim. Potongan kabel fisik tercatat saat disiapkan.' : reason}</p></>} />}
  </>
}
