import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getReferenceStock, referenceSkus } from '@/api/warehouse/reference'
import { Badge, Button } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceStockHistory } from './ReferenceStockHistory'
import { ReferenceStockPositions } from './ReferenceStockPositions'

export function ReferenceStockPage() {
  const [params, setParams] = useSearchParams(), id = params.get('skuId')
  return <div className="stack"><PageHeader title="Stok & Riwayat" subtitle="Pilih barang untuk melihat stok tersedia pada setiap gudang dan material yang sedang dipegang teknisi." />
    {id ? <ReferenceStockDetail key={id} id={id} select={skuId => setParams(skuId ? { skuId } : {})} /> : <>
      <WarehousePicker label="Barang" load={referenceSkus} value={null} name={row => row.name + ' · ' + row.code} onChange={row => setParams(row ? { skuId: row.id } : {})} />
      <p className="muted">Belum ada barang yang dipilih. <Link to="/warehouse/catalog">Kelola barang dan gudang</Link>.</p>
    </>}
  </div>
}

function ReferenceStockDetail({ id, select }: { readonly id: string; readonly select: (skuId: string | null) => void }) {
  const load = useCallback(() => getReferenceStock(id), [id]), result = useWarehouseQuery(load)
  const [tab, setTab] = useState('positions')
  return <><Button onClick={result.reload}>Muat ulang saldo</Button><WarehouseState {...result}>{data => <>
    <WarehousePicker label="Barang" load={referenceSkus} value={data.sku} name={row => row.name + ' · ' + row.code} onChange={row => select(row?.id ?? null)} />
    <section className="stack" aria-label="Saldo per gudang"><h2>Stok tersedia per gudang</h2>
      <p className="muted">Termasuk rak di bawah gudang. Material di tangan teknisi dihitung terpisah. Minimum: <WarehouseQuantity value={data.sku.minimumQuantityBase} unit={data.sku.baseUnit} /> per gudang.</p>
      <DataTable presentation="warehouse" rows={data.warehouses} rowKey={row => row.warehouseId} columns={[
        { key: 'name', header: 'Gudang', cell: row => row.warehouseName },
        { key: 'quantity', header: 'Tersedia', cell: row => <WarehouseQuantity value={row.quantityBase} unit={data.sku.baseUnit} /> },
        { key: 'minimum', header: 'Minimum stok', cell: row => BigInt(row.quantityBase) < BigInt(data.sku.minimumQuantityBase) ? <Badge tone="warning">Di bawah minimum</Badge> : <Badge tone="good">Cukup</Badge> },
      ]} />
    </section><Tabs tabs={[{ key: 'positions', label: 'Lokasi & Pemegang' }, { key: 'history', label: 'Riwayat' }]} active={tab} onChange={setTab} />
    {tab === 'positions' ? <ReferenceStockPositions skuId={id} /> : <ReferenceStockHistory skuId={id} />}
  </>}</WarehouseState></>
}
