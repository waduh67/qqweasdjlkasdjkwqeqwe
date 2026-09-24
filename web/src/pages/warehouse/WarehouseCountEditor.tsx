import { useCallback, useState, type FormEvent } from 'react'
import { countCounters, countPositions, createCount, type CountPerson, type CountPosition, type WarehouseCount } from '@/api/warehouse/counts'
import type { WarehouseLocation } from '@/api/warehouse/models'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { locationLabel, receiptLocations } from './receiptChoices'
import { countItemLabel, countPersonLabel } from './countPresentation'

type Row = { key: string; position: CountPosition | null; counter: CountPerson | null }
const empty = (): Row => ({ key: crypto.randomUUID(), position: null, counter: null })
export function WarehouseCountEditor({ onSaved, onClose }: { onSaved: (count: WarehouseCount) => void; onClose: () => void }) {
  const { can } = useCan()
  const [location, setLocation] = useState<WarehouseLocation | null>(null), [reason, setReason] = useState('')
  const [rows, setRows] = useState<Row[]>(() => [empty()]), [error, setError] = useState('')
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseCount> | null>(null)
  if (!can('inventory.count.manage')) return <WarehouseDenied />
  if (!can('inventory.location.view')) return <div className="card stack" role="alert"><p>Izin lihat lokasi diperlukan untuk memilih lokasi stock opname.</p><Button onClick={onClose}>Kembali</Button></div>
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!location || !reason.trim() || reason.trim().length > 500) throw new Error('Pilih lokasi dan isi alasan stock opname.')
      if (rows.some(row => !row.position || !row.counter || row.position.location.id !== location.id)) throw new Error('Pilih posisi barang dan penghitung pada setiap baris.')
      if (new Set(rows.map(row => row.position?.id)).size !== rows.length) throw new Error('Satu posisi barang hanya boleh ditugaskan sekali.')
      setOperation(createCount({ locationId: location.id, partialLocation: true, reason: reason.trim(), entries: rows.map(row => ({ balanceId: row.position!.id, counterId: row.counter!.id })) })); setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa isian stock opname.') }
  }
  return <><form className="stack" aria-label="Draft stock opname" onSubmit={prepare}><h2>Stock opname baru</h2>
    <p>Penghitungan mencakup posisi yang dipilih pada satu lokasi. Petugas mencatat hasil fisik tanpa angka pembanding stok buku.</p>
    <WarehousePicker label="Lokasi stock opname" load={receiptLocations} value={location} name={locationLabel} onChange={value => { setLocation(value); setRows([empty()]) }} />
    <TextareaField label="Alasan stock opname" value={reason} required maxLength={500} onChange={(_, data) => setReason(data.value)} />
    {location ? rows.map((row, index) => <CountEntryEditor key={row.key} row={row} number={index + 1} locationId={location.id}
      update={patch => setRows(current => current.map(item => item.key === row.key ? { ...item, ...patch } : item))}
      remove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />) : <p>Pilih lokasi untuk mencari posisi barang.</p>}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="row wrap"><Button type="button" disabled={!location || rows.length >= 100} onClick={() => setRows(current => [...current, empty()])}>Tambah posisi hitung</Button>
      <Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary" disabled={!location}>Tinjau stock opname</Button></div>
  </form>{operation && <WarehouseCommandDialog title="Simpan draft stock opname" confirmLabel="Simpan stock opname" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onClose}
    summary={<><p>{location && locationLabel(location)}</p><p>{reason}</p><ul>{rows.map(row => <li key={row.key}>{row.position && countItemLabel(row.position.item)} · posisi {row.position?.id} · {row.counter && countPersonLabel(row.counter)}</li>)}</ul><p>Draft belum membuka penghitungan dan belum mengubah stok.</p></>} />}</>
}
function CountEntryEditor({ row, number, locationId, update, remove }: { row: Row; number: number; locationId: string; update: (patch: Partial<Row>) => void; remove?: () => void }) {
  const positions = useCallback((search: string, page: number) => countPositions({ locationId, query: search.trim() || undefined, page }), [locationId])
  const counters = useCallback((search: string, page: number) => countCounters(locationId, search, page), [locationId])
  return <fieldset className="card stack" style={{ minWidth: 0 }}><legend>Posisi hitung {number}</legend>
    <WarehousePicker label={`Barang dihitung ${number}`} load={positions} value={row.position} onChange={position => update({ position })}
      name={position => `${countItemLabel(position.item)} · ${position.condition} · ${position.legalOwner} · ${position.custodianKind} · ${position.id}`} />
    {row.position && <><p><WarehouseStatus status={row.position.condition} /> · <WarehouseStatus status={row.position.legalOwner} /> · <WarehouseStatus status={row.position.status} /></p>
      <p className="muted" style={{ overflowWrap: 'anywhere' }}>Identitas: {row.position.stockIdentityId} · Pemegang: {row.position.custodianId}</p></>}
    <WarehousePicker label={`Penghitung ${number}`} load={counters} value={row.counter} onChange={counter => update({ counter })} name={countPersonLabel} />
    {remove && <Button type="button" onClick={remove}>Hapus posisi {number}</Button>}
  </fieldset>
}
