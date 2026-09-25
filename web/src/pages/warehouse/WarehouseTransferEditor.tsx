import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { getLot, listPositions, type StockPosition } from '@/api/warehouse/stock'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { listSetupUsers } from '@/api/warehouse/setup'
import { createTransfer, updateTransfer, type WarehouseTransfer } from '@/api/warehouse/transfers'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { locationLabel, receiptLocations } from './receiptChoices'
import { buildTransferDraft, eligibleTransferPosition, transferLocationKinds, type TransferDraftLine } from './transferDraft'

const emptyLine = (): TransferDraftLine => ({ key: crypto.randomUUID(), position: null, quantity: '' })
type Recipient = { id: string; name: string; status: string }
export interface TransferEditorInitial { transfer: WarehouseTransfer; source: WarehouseLocation; destination: WarehouseLocation;
  transit: WarehouseLocation; receiver: Recipient; rows: TransferDraftLine[] }
export function WarehouseTransferEditor({ initial, onSaved, onClose, onReload }: { initial?: TransferEditorInitial; onSaved: (row: WarehouseTransfer) => void; onClose: () => void; onReload: () => void }) {
  const { can } = useCan(), { user } = useAuth()
  const [source, setSource] = useState<WarehouseLocation | null>(initial?.source ?? null)
  const [destination, setDestination] = useState<WarehouseLocation | null>(initial?.destination ?? null)
  const [transit, setTransit] = useState<WarehouseLocation | null>(initial?.transit ?? null)
  const [receiver, setReceiver] = useState<Recipient | null>(initial?.receiver ?? null)
  const [reason, setReason] = useState(initial?.transfer.reason ?? '')
  const [rows, setRows] = useState<TransferDraftLine[]>(() => initial?.rows ?? [emptyLine()])
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseTransfer> | null>(null)
  if (!can('inventory.transfer.manage') || !user) return <WarehouseDenied />
  if (!can('inventory.item.view') || !can('inventory.location.view')) return <div className="card stack" role="alert"><p>Izin lihat stok dan lokasi diperlukan untuk memilih sumber transfer.</p><Button onClick={onClose}>Kembali</Button></div>
  if (initial && (initial.transfer.state !== 'DRAFT' || initial.transfer.senderId !== user.id)) return <WarehouseDenied />
  const actor = user
  function update(key: string, patch: Partial<TransferDraftLine>) { setRows(current => current.map(row => row.key === key ? { ...row, ...patch } : row)) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (receiver?.status !== 'ACTIVE') throw new Error('Pilih penerima yang aktif.')
      if (initial && !can('iam.user.view') && receiver.id !== actor.id && receiver.id !== initial.receiver.id) throw new Error('Pilih penerima yang diizinkan.')
      const draft = buildTransferDraft(source, destination, transit, receiver.id, actor.id, reason, rows)
      setOperation(initial ? updateTransfer(initial.transfer.id, initial.transfer.revision, draft) : createTransfer(draft)); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa rincian transfer.') }
  }
  return <><form className="stack" aria-label="Draft transfer" onSubmit={prepare}>
    <h2>{initial ? 'Ubah draft transfer' : 'Transfer baru'}</h2><p>Draft belum memindahkan atau mencadangkan stok. Setelah disimpan, pengirim perlu mengirim barang dan penerima mengonfirmasi jumlah fisik yang diterima.</p>
    {initial && <p>Revisi {initial.transfer.revision}. Perubahan sebelumnya tetap tersimpan di riwayat. Barang yang tidak lagi tersedia perlu dipilih kembali.</p>}
    {receiver?.status !== 'ACTIVE' && initial && <p role="status">Penerima tersimpan sudah tidak aktif. Pilih penerima aktif sebelum menyimpan.</p>}
    <WarehousePicker label="Lokasi asal transfer" load={receiptLocations} value={source} name={locationLabel} eligible={row => transferLocationKinds.includes(row.kind)}
      onChange={value => { setSource(value); setRows([emptyLine()]) }} />
    <WarehousePicker label="Lokasi tujuan transfer" load={receiptLocations} value={destination} name={locationLabel} eligible={row => transferLocationKinds.includes(row.kind) && row.id !== source?.id} onChange={setDestination} />
    <WarehousePicker label="Lokasi transit transfer" load={receiptLocations} value={transit} name={locationLabel} eligible={row => row.kind === 'TRANSIT' && !row.issueEligible && row.code !== 'RECEIPT_SOURCE' && row.id !== source?.id && row.id !== destination?.id} onChange={setTransit} />
    {can('iam.user.view') ? <WarehousePicker<Recipient> label="Penerima transfer" load={listSetupUsers} value={receiver} name={row => row.name} eligible={row => row.status === 'ACTIVE'} onChange={setReceiver} />
      : <><SelectField label="Penerima transfer" value={receiver?.id ?? ''} required onChange={(_, data) => setReceiver(!data.value ? null
        : data.value === initial?.receiver.id ? initial.receiver : { id: actor.id, name: actor.name, status: 'ACTIVE' })}>
        <option value="">Pilih…</option><option value={actor.id}>{actor.name} (saya)</option>
        {initial && initial.receiver.id !== actor.id && <option value={initial.receiver.id}>{initial.receiver.name} (tersimpan)</option>}
      </SelectField><p className="muted">Izin lihat pengguna diperlukan untuk memilih penerima lain.</p></>}
    <TextareaField label="Alasan transfer" value={reason} required maxLength={1000} onChange={(_, data) => setReason(data.value)} />
    <p><Link to="/warehouse/catalog">Kelola lokasi gudang dan transit</Link></p>
    {source ? rows.map((row, index) => <TransferLineEditor key={row.key} row={row} number={index + 1} sourceId={source.id} actorId={actor.id} onChange={patch => update(row.key, patch)}
      onRemove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />) : <p>Pilih lokasi asal untuk mencari barang fisik.</p>}
    <div className="row wrap"><Button type="button" disabled={!source || rows.length >= 100} onClick={() => setRows(current => [...current, emptyLine()])}>Tambah barang transfer</Button>
      <Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary" disabled={!source}>Tinjau transfer</Button></div>
    {error && <p className="error" role="alert">{error}</p>}
  </form>
    {operation && <WarehouseCommandDialog title="Simpan draft transfer" confirmLabel="Simpan transfer" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{source && locationLabel(source)} → {transit && locationLabel(transit)} → {destination && locationLabel(destination)}</p><p>Pengirim: {actor.name} · Penerima: {receiver?.name}</p>
        <ul>{rows.map(row => <li key={row.key}>{row.position?.name} · {row.position?.serial ?? row.position?.stockIdentityId}: {row.quantity} {row.position?.physical.baseUnit === 'MM' ? 'm' : 'unit'}</li>)}</ul>
        <p>{reason}</p><p>Stok belum berpindah. Kondisi dan kepemilikan barang tetap mengikuti sumbernya.</p></>} />}
  </>
}

function TransferLineEditor({ row, number, sourceId, actorId, onChange, onRemove }: { row: TransferDraftLine; number: number; sourceId: string; actorId: string; onChange: (value: Partial<TransferDraftLine>) => void; onRemove?: () => void }) {
  const load = useCallback((search: string, page: number) => listPositions({ locationId: sourceId, serial: search.trim() || undefined, page }), [sourceId])
  const stock = row.position
  const label = (position: StockPosition) => `${position.name} · ${position.serial ?? position.stockIdentityId.slice(0, 8)} · ${formatBaseQuantity(position.physical.quantityBase, position.physical.baseUnit)} ${position.physical.baseUnit === 'MM' ? 'm' : 'unit'}`
  return <fieldset className="card stack" style={{ minWidth: 0 }}><legend>Barang transfer {number}</legend>
    <p className="muted">Cari dengan serial lengkap, atau pilih potongan dari daftar stok lokasi asal. Stok yang sudah terikat pengeluaran WO menggunakan alur material WO.</p>
    <WarehousePicker label={`Barang transfer ${number}`} load={load} value={stock} name={label} eligible={row => eligibleTransferPosition(row, actorId)}
      onChange={position => onChange({ position, quantity: position ? formatBaseQuantity(position.physical.quantityBase, position.physical.baseUnit) : '' })} />
    {stock && <><p>{stock.locationName} · <WarehouseStatus status={stock.condition} /> · <WarehouseStatus status={stock.legalOwner} /></p>
      <p>Jumlah fisik: <WarehouseQuantity value={stock.physical.quantityBase} unit={stock.physical.baseUnit} /></p>
      <p style={{ overflowWrap: 'anywhere' }}>Identitas: {stock.stockIdentityId}</p>
      {stock.lotId && <TransferLotName id={stock.lotId} />}
      <WarehouseQuantityField label={`Jumlah transfer ${number}`} unit={stock.physical.baseUnit} value={row.quantity} onChange={quantity => onChange({ quantity })} />
      {stock.legalOwner === 'CUSTOMER' && <p role="status">Barang milik pelanggan tetap milik pelanggan dan tidak menjadi stok tersedia setelah transfer.</p>}
    </>}
    {onRemove && <Button type="button" onClick={onRemove}>Hapus barang {number}</Button>}
  </fieldset>
}
function TransferLotName({ id }: { id: string }) {
  const loader = useCallback(() => getLot(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{lot => <p>Lot / reel: {lot.code}</p>}</WarehouseState>
}
