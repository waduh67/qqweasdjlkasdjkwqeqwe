import { useState, type FormEvent } from 'react'
import { listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { formatBaseQuantity, quantityFromInput, type BaseUnit } from '@/api/warehouse/quantity'
import { saveReplenishmentRule, type ReplenishmentDetails, type ReplenishmentRule, type ReplenishmentRuleInput } from '@/api/warehouse/replenishment'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { locationLabel } from './receiptChoices'

const skus = (search: string, page: number) => listSkus({ search, page, state: 'ACTIVE' })
const locations = (search: string, page: number) => listLocations({ search, page, state: 'ACTIVE' })
const labels = { minimumBase: 'Minimum', maximumBase: 'Maksimum', targetBase: 'Target', packageMultipleBase: 'Kelipatan kemasan' }
export function WarehouseReplenishmentRuleForm({ data, onClose, onDone, reload }: { data?: ReplenishmentDetails; onClose: () => void; onDone: (rule: ReplenishmentRule) => void; reload: () => void }) {
  const [sku, setSku] = useState<WarehouseSku | null>(null), [location, setLocation] = useState<WarehouseLocation | null>(null)
  const [unit, setUnit] = useState<BaseUnit>(data?.rule.baseUnit ?? 'EA')
  const blank = { minimumBase: '', maximumBase: '', targetBase: '', packageMultipleBase: '' }
  const [values, setValues] = useState(() => data ? Object.fromEntries(Object.keys(blank).map(key => [key, formatBaseQuantity(data.rule[key as keyof typeof blank], unit, '.')])) as typeof blank : blank)
  const [lead, setLead] = useState(String(data?.rule.leadTimeDays ?? 0)), [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<{ command: WarehouseCommand<ReplenishmentRule>; input: ReplenishmentRuleInput } | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      if (!data && (!sku || !location || !location.issueEligible || !['WAREHOUSE', 'BIN'].includes(location.kind))) throw new Error('Pilih barang aktif dan gudang atau rak yang dapat mengeluarkan stok.')
      if (!/^(0|[1-9][0-9]*)$/.test(lead) || Number(lead) > 3650) throw new Error('Waktu tunggu harus 0 sampai 3650 hari.')
      const amounts = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, quantityFromInput(value, unit, key !== 'packageMultipleBase')])) as typeof blank
      if (BigInt(amounts.minimumBase) > BigInt(amounts.targetBase) || BigInt(amounts.targetBase) > BigInt(amounts.maximumBase)) throw new Error('Minimum harus ≤ target ≤ maksimum.')
      const input = { skuId: data?.rule.skuId ?? sku!.id, locationId: data?.rule.locationId ?? location!.id, baseUnit: unit, ...amounts, leadTimeDays: Number(lead), ...(data ? { expectedRevision: data.rule.revision } : {}) }
      setReview({ command: saveReplenishmentRule(input, data?.rule.id), input }); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Aturan tidak valid.') }
  }
  return <section className="card stack" aria-label="Form aturan pengisian"><h2>{data ? 'Ubah aturan pengisian' : 'Aturan pengisian baru'}</h2>
    <form className="stack" onSubmit={submit}>{data ? <p>{data.sku.name} · {data.location.name ?? data.location.code} · Revisi {data.rule.revision}</p> : <>
      <WarehousePicker label="Barang untuk pengisian" load={skus} value={sku} onChange={next => { setSku(next); if (next && next.baseUnit !== unit) { setUnit(next.baseUnit); setValues(blank) } }} name={row => `${row.name} · ${row.code}`} />
      <WarehousePicker label="Gudang tujuan pengisian" load={locations} value={location} onChange={setLocation} name={locationLabel} eligible={row => row.issueEligible && ['WAREHOUSE', 'BIN'].includes(row.kind)} />
    </>}
      {Object.entries(labels).map(([key, label]) => <WarehouseQuantityField key={key} label={label} value={values[key as keyof typeof values]} unit={unit} allowZero={key !== 'packageMultipleBase'} onChange={value => setValues(current => ({ ...current, [key]: value }))} />)}
      <TextField label="Waktu tunggu (hari)" value={lead} inputMode="numeric" onChange={(_, value) => setLead(value.value)} />
      <p>Saran muncul bila stok tersedia ditambah barang masuk terkonfirmasi di bawah minimum. Jumlah menuju target dibulatkan ke kelipatan kemasan; reservasi sudah dikurangi dari stok tersedia.</p>
      {error && <p role="alert">{error}</p>}<div className="row wrap"><Button onClick={onClose}>Batal mengubah aturan</Button><Button type="submit" variant="primary">Tinjau aturan</Button></div>
    </form>
    {review && <WarehouseCommandDialog title="Konfirmasi aturan pengisian" confirmLabel="Simpan aturan" command={review.command} onClose={() => setReview(null)} onDone={onDone} onReload={reload}
      summary={<><p>{data?.sku.name ?? sku?.name} · {data?.location.name ?? location?.name ?? location?.code}</p>
        <ul>{Object.entries(labels).map(([key, label]) => <li key={key}>{label}: {data && <><WarehouseQuantity value={data.rule[key as keyof typeof labels]} unit={unit} /> → </>}<WarehouseQuantity value={review.input[key as keyof typeof labels]} unit={unit} /></li>)}</ul>
        <p>Waktu tunggu {review.input.leadTimeDays} hari. Aturan menjadi aktif. Perubahan aturan tidak menambah stok atau membuat pesanan pembelian.</p></>} />}
  </section>
}
