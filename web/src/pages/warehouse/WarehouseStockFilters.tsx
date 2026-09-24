import { useCallback, useState, type FormEvent } from 'react'
import { getLocation, getSku, listLocations, listSkus } from '@/api/warehouse/masters'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { CONDITIONS, LEGAL_OWNERS } from '@/api/warehouse/models'
import { STOCK_BUCKETS, type PositionFilter } from '@/api/warehouse/stock'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { locationLabel } from './receiptChoices'

const buckets = { AVAILABLE: 'Tersedia', RESERVED: 'Dipesan', PICKED: 'Disiapkan', TECHNICIAN: 'Di tangan teknisi', TRANSIT: 'Dalam perjalanan', INSTALLED: 'Perangkat terpasang', QUARANTINE: 'Karantina' }
// Archived masters can still identify historical stock; filtering must include them.
const stockSkus = (search: string, page: number) => listSkus({ search, page })
const stockLocations = (search: string, page: number) => listLocations({ search, page })
type Props = { filter: PositionFilter; buckets: boolean; history?: boolean; label?: string; onApply: (values: Record<string, string>) => void }
export function WarehouseStockFilters(props: Props) {
  const { can } = useCan()
  const loader = useCallback(async () => {
    const [sku, location] = await Promise.all([
      props.filter.skuId && can('inventory.sku.view') ? getSku(props.filter.skuId) : Promise.resolve(null),
      props.filter.locationId && can('inventory.location.view') ? getLocation(props.filter.locationId) : Promise.resolve(null),
    ])
    return { sku, location }
  }, [props.filter.skuId, props.filter.locationId, can])
  const result = useWarehouseQuery(loader)
  return <details className="card"><summary>{props.label ?? 'Filter stok'}</summary><WarehouseState {...result}>{data => <StockFilterForm {...props} initialSku={data.sku} initialLocation={data.location} />}</WarehouseState></details>
}
function StockFilterForm({ filter, buckets: showBuckets, history, onApply, initialSku, initialLocation }: Props & { initialSku: WarehouseSku | null; initialLocation: WarehouseLocation | null }) {
  const { can } = useCan()
  const [sku, setSku] = useState(initialSku), [location, setLocation] = useState(initialLocation)
  const [serial, setSerial] = useState(filter.serial ?? ''), [bucket, setBucket] = useState(filter.bucket ?? '')
  const [condition, setCondition] = useState(filter.condition ?? ''), [owner, setOwner] = useState(filter.owner ?? '')
  const [sort, setSort] = useState(filter.sort ?? (history ? 'createdAt' : 'name')), [direction, setDirection] = useState(filter.direction ?? 'asc')
  function submit(event: FormEvent) {
    event.preventDefault()
    onApply({ skuId: sku?.id ?? (!can('inventory.sku.view') ? filter.skuId ?? '' : ''), locationId: location?.id ?? (!can('inventory.location.view') ? filter.locationId ?? '' : ''),
      serial: serial.trim(), bucket: showBuckets ? bucket : '', condition, owner, sort, direction })
  }
  return <form className="stack" onSubmit={submit}>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
      {can('inventory.sku.view') && <WarehousePicker label="Barang" load={stockSkus} value={sku} onChange={setSku} name={row => `${row.name} · ${row.code}${row.state === 'ARCHIVED' ? ' (arsip)' : ''}`} optional />}
      {can('inventory.location.view') && <WarehousePicker label="Lokasi stok" load={stockLocations} value={location} onChange={setLocation} name={row => `${locationLabel(row)}${row.state === 'ARCHIVED' ? ' (arsip)' : ''}`} optional />}
      <TextField label="Serial lengkap" value={serial} maxLength={128} onChange={(_, data) => setSerial(data.value)} />
      {showBuckets && <SelectField label="Kelompok stok" value={bucket} onChange={(_, data) => setBucket(data.value as typeof bucket)}><option value="">Semua stok</option>{STOCK_BUCKETS.map(value => <option key={value} value={value}>{buckets[value]}</option>)}</SelectField>}
      <SelectField label="Kondisi" value={condition} onChange={(_, data) => setCondition(data.value)}><option value="">Semua kondisi</option>{CONDITIONS.map(value => <option key={value} value={value}>{({ SERVICEABLE: 'Layak pakai', QUARANTINE: 'Karantina', DAMAGED: 'Rusak', SCRAP: 'Tidak dapat dipakai' })[value]}</option>)}</SelectField>
      <SelectField label="Kepemilikan" value={owner} onChange={(_, data) => setOwner(data.value)}><option value="">Semua pemilik</option>{LEGAL_OWNERS.map(value => <option key={value} value={value}>{({ ISP: 'Milik ISP', CUSTOMER: 'Milik pelanggan', UNKNOWN: 'Belum diketahui' })[value]}</option>)}</SelectField>
      <SelectField label="Urutkan stok" value={sort} onChange={(_, data) => setSort(data.value as typeof sort)}>{!history && <option value="name">Nama</option>}<option value="createdAt">Tanggal</option><option value="id">Referensi</option></SelectField>
      <SelectField label="Arah urutan" value={direction} onChange={(_, data) => setDirection(data.value as typeof direction)}><option value="asc">Naik / terlama</option><option value="desc">Turun / terbaru</option></SelectField>
    </div><Button type="submit" variant="primary">Terapkan filter</Button>
  </form>
}
