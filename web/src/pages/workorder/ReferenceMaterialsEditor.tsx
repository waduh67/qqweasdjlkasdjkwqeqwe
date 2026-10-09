import { ownMaterials, type ReferencePosition } from '@/api/warehouse/reference'
import { Button } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import type { ReferenceMaterialDraft } from './referenceCompletionDraft'

const load = async (search: string, page: number) => {
  const result = await ownMaterials(search, page)
  return { ...result, items: result.items.map(row => ({ ...row, id: row.stockIdentityId })) }
}
const label = (row: ReferencePosition) => [row.skuName, row.serial, row.locationName].filter(Boolean).join(' · ')

export function ReferenceMaterialsEditor({ rows, onChange, enabled, required }: {
  rows: readonly ReferenceMaterialDraft[]; onChange: (rows: readonly ReferenceMaterialDraft[]) => void; enabled: boolean; required: boolean
}) {
  const update = (key: string, patch: Partial<ReferenceMaterialDraft>) => onChange(rows.map(row => row.key === key ? { ...row, ...patch } : row))
  return <section className="stack" aria-labelledby="reference-materials-title"><h2 id="reference-materials-title">3. Material terpakai</h2>
    <p>{required ? 'Pekerjaan ini wajib mencatat material.' : 'Tambahkan barang yang dipakai bila ada.'} Sisa barang tetap tercatat di tangan Anda.</p>
    {rows.map((row, index) => <div className="card stack" key={row.key}>
      <WarehousePicker label={'Barang ' + (index + 1)} load={load} value={row.source ? { ...row.source, id: row.source.stockIdentityId } : null}
        onChange={source => update(row.key, { source, quantity: source?.tracking === 'SERIAL' ? '1' : '' })} name={label} disabled={!enabled} />
      {row.source && <><p>Di tangan: <WarehouseQuantity value={row.source.quantityBase} unit={row.source.baseUnit} />{row.source.mac && ' · MAC ' + row.source.mac}</p>
        <WarehouseQuantityField label="Terpakai" value={row.quantity} unit={row.source.baseUnit} disabled={!enabled || row.source.tracking === 'SERIAL'} onChange={value => update(row.key, { quantity: value })} /></>}
      <Button variant="subtle" disabled={!enabled} onClick={() => onChange(rows.filter(item => item.key !== row.key))}>Hapus barang {index + 1}</Button>
    </div>)}
    <Button disabled={!enabled || rows.length >= 100} onClick={() => onChange([...rows, { key: crypto.randomUUID(), source: null, quantity: '' }])}>Tambah material</Button>
  </section>
}
