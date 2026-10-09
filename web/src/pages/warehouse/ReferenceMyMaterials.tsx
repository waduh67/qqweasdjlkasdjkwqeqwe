import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { ownMaterials } from '@/api/warehouse/reference'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function ReferenceMyMaterials() {
  const { user } = useAuth(), [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const { hasPermission } = useCan()
  const load = useCallback(() => ownMaterials(search.trim(), page), [search, page])
  const result = useWarehouseQuery(load, `${user?.id}:${user?.tenantId}`)
  return <div className="stack"><PageHeader title="Material Saya" subtitle="Barang yang sudah diserahkan ke Anda. Pemakaian dicatat saat menyelesaikan tugas." actions={<Button onClick={result.reload}>Muat ulang</Button>} />
    <div className="row wrap"><Link to="/my-work-orders">Buka tugas untuk mencatat pemakaian</Link>{hasPermission('warehouse.request.own') && <Link to="/warehouse/requests">Ajukan atau pantau permintaan material</Link>}
      {hasPermission('warehouse.return.own') && <Link to="/warehouse/returns">Ajukan atau pantau retur material</Link>}</div>
    <TextField label="Cari material" placeholder="Nama, kode barang, atau serial" value={search} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.stockIdentityId} empty={<EmptyState title="Belum ada material di tangan Anda" hint="Material muncul di sini setelah admin gudang menyerahkannya ke Anda." />} columns={[
        { key: 'material', header: 'Barang', cell: row => row.skuName, description: row => row.skuCode },
        { key: 'serial', header: 'Serial / MAC', cell: row => row.serial ?? 'Tanpa serial', description: row => row.mac },
        { key: 'quantity', header: 'Jumlah di tangan', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
        { key: 'location', header: 'Lokasi', cell: row => row.locationName },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </div>
}
