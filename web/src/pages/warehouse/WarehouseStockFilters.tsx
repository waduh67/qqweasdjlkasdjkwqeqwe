import { type ReactNode } from 'react'
import { useLocation, useNavigationType } from 'react-router-dom'
import { getLocation, getSku, listLocations, listSkus } from '@/api/warehouse/masters'
import { CONDITIONS, LEGAL_OWNERS } from '@/api/warehouse/models'
import { STOCK_BUCKETS, type PositionFilter } from '@/api/warehouse/stock'
import { useCan } from '@/auth/useCan'
import { FilterBar, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { WarehouseReferenceFilter } from '@/components/organisms/warehouse/WarehouseReferenceFilter'
import { locationLabel } from './receiptChoices'

const buckets = { AVAILABLE: 'Tersedia', RESERVED: 'Dipesan', PICKED: 'Disiapkan', TECHNICIAN: 'Di tangan teknisi', TRANSIT: 'Dalam perjalanan', INSTALLED: 'Perangkat terpasang', QUARANTINE: 'Karantina' }
const stockSkus = (search: string, page: number) => listSkus({ search, page })
const stockLocations = (search: string, page: number) => listLocations({ search, page })
type Props = { filter: PositionFilter; buckets: boolean; history?: boolean; label?: string; onApply: (values: Record<string, string>) => void; children?: ReactNode }
export function WarehouseStockFilters({ filter, buckets: showBuckets, history, onApply, children, label }: Props) {
  const { can } = useCan(), location = useLocation(), navigation = useNavigationType()
  const sort = filter.sort ?? (history ? 'createdAt' : 'name')
  return <FilterBar label={label} search={<FilterSearch resetKey={navigation === 'POP' ? location.key : undefined} label="Serial lengkap" value={filter.serial ?? ''} maxLength={128} onChange={serial => onApply({ serial: serial.trim() })} />}>
    {children}
    {(can('inventory.sku.view') || filter.skuId) && <WarehouseReferenceFilter label="Barang" valueId={filter.skuId} canLookup={can('inventory.sku.view')} get={getSku} load={stockSkus} onChange={skuId => onApply({ skuId })} name={row => `${row.name} · ${row.code}${row.state === 'ARCHIVED' ? ' (arsip)' : ''}`} />}
    {(can('inventory.location.view') || filter.locationId) && <WarehouseReferenceFilter label="Lokasi stok" valueId={filter.locationId} canLookup={can('inventory.location.view')} get={getLocation} load={stockLocations} onChange={locationId => onApply({ locationId })} name={row => `${locationLabel(row)}${row.state === 'ARCHIVED' ? ' (arsip)' : ''}`} />}
    {showBuckets && <FilterSelect label="Kelompok stok" value={filter.bucket ?? ''} onChange={bucket => onApply({ bucket })}><option value="">Semua stok</option>{STOCK_BUCKETS.map(value => <option key={value} value={value}>{buckets[value]}</option>)}</FilterSelect>}
    <FilterSelect secondary label="Kondisi" value={filter.condition ?? ''} onChange={condition => onApply({ condition })}><option value="">Semua kondisi</option>{CONDITIONS.map(value => <option key={value} value={value}>{({ SERVICEABLE: 'Layak pakai', QUARANTINE: 'Karantina', DAMAGED: 'Rusak', SCRAP: 'Tidak dapat dipakai' })[value]}</option>)}</FilterSelect>
    <FilterSelect secondary label="Kepemilikan" value={filter.owner ?? ''} onChange={owner => onApply({ owner })}><option value="">Semua pemilik</option>{LEGAL_OWNERS.map(value => <option key={value} value={value}>{({ ISP: 'Milik ISP', CUSTOMER: 'Milik pelanggan', UNKNOWN: 'Belum diketahui' })[value]}</option>)}</FilterSelect>
    <FilterSelect secondary caption="Urutan" label="Urutkan stok" value={sort} defaultValue={history ? 'createdAt' : 'name'} onChange={sort => onApply({ sort })}>{!history && <option value="name">Nama</option>}<option value="createdAt">Tanggal</option><option value="id">Referensi</option></FilterSelect>
    <FilterSelect secondary caption="Arah" label="Arah urutan" value={filter.direction ?? 'asc'} defaultValue="asc" onChange={direction => onApply({ direction })}><option value="asc">Naik / terlama</option><option value="desc">Turun / terbaru</option></FilterSelect>
  </FilterBar>
}
