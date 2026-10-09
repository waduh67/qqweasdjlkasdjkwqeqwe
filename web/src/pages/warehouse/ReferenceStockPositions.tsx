import { useCallback, useState } from 'react'
import { listReferencePositions, type ReferencePositionFilter } from '@/api/warehouse/referenceStock'
import { referenceLocations } from '@/api/warehouse/reference'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function ReferenceStockPositions({ skuId }: { readonly skuId: string }) {
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [holder, setHolder] = useState<ReferencePositionFilter['holderKind']>()
  const [location, setLocation] = useState<WarehouseLocation | null>(null)
  const load = useCallback(() => listReferencePositions(skuId, { search: search.trim(), page, holderKind: holder, locationId: location?.id }), [skuId, search, page, holder, location])
  const result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Posisi stok">
    <div className="resource-field-grid">
      <TextField label="Cari posisi" placeholder="Nama barang, serial, atau MAC" value={search} maxLength={200} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <WarehousePicker optional label="Lokasi stok" load={referenceLocations} value={location} name={row => (row.name ?? row.code) + ' · ' + row.code} onChange={row => { setLocation(row); setPage(0) }} />
      <SelectField label="Pemegang stok" value={holder ?? ''} onChange={(_, data) => { setHolder(data.value === 'WAREHOUSE' ? 'WAREHOUSE' : data.value === 'TECHNICIAN' ? 'TECHNICIAN' : data.value === 'VEHICLE' ? 'VEHICLE' : data.value === 'CUSTOMER' ? 'CUSTOMER' : undefined); setPage(0) }}>
        <option value="">Semua dalam akses Anda</option><option value="WAREHOUSE">Gudang</option><option value="TECHNICIAN">Teknisi</option><option value="VEHICLE">Kendaraan</option><option value="CUSTOMER">Pelanggan</option>
      </SelectField>
    </div><Button onClick={result.reload}>Muat ulang posisi</Button>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => [row.stockIdentityId, row.locationId, row.holderId, row.holderKind, row.status].join(':')} empty={<EmptyState title="Belum ada posisi stok" hint="Stok muncul setelah penerimaan atau penyerahan tercatat. Pencarian hanya memuat lokasi dalam akses Anda." />} columns={[
        { key: 'location', header: 'Lokasi', cell: row => row.locationName },
        { key: 'holder', header: 'Pemegang', cell: row => row.holderName, description: row => row.holderEmail },
        { key: 'quantity', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
        { key: 'serial', header: 'Serial / MAC', cell: row => row.serial ?? 'Tanpa serial', description: row => row.mac },
        { key: 'status', header: 'Status', cell: row => row.status === 'AVAILABLE' ? 'Tersedia di gudang' : row.status === 'ISSUED' ? 'Di tangan teknisi' : row.status === 'LOST' ? 'Hilang' : row.status },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
