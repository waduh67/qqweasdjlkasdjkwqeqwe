import { Link, useSearchParams } from 'react-router-dom'
import { archiveLocation, archiveSku, archiveSupplier, listLocations, listSkus, listSuppliers } from '@/api/warehouse/masters'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { PageHeader, Tabs } from '@/components/molecules'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseMasterPanel } from './WarehouseMasterPanel'
import { WarehouseSkuEditor } from './WarehouseSkuEditor'
import { WarehouseSupplierEditor } from './WarehouseSupplierEditor'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'
import { WarehouseScopePanel } from './WarehouseScopePanel'

const tabs = [
  { key: 'locations', label: 'Lokasi', permission: 'inventory.location.view' },
  { key: 'skus', label: 'Barang', permission: 'inventory.sku.view' },
  { key: 'suppliers', label: 'Pemasok', permission: 'inventory.receipt.view' },
  { key: 'access', label: 'Akses gudang', permission: 'inventory.location.view' },
] as const

export function WarehouseCatalogPage() {
  const { can } = useCan()
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const visible = tabs.filter(tab => can(tab.permission))
  const selected = visible.find(tab => tab.key === params.get('tab'))?.key ?? visible[0]?.key
  return <div className="stack"><PageHeader title="Katalog & Lokasi" subtitle="Siapkan barang, lokasi penyimpanan, pemasok dan akses petugas untuk operasi gudang." />
    <details className="card" open={!user?.platformAdmin && user?.areaIds.length === 0}>
      <summary>Urutan setup gudang</summary>
      <ol>
        <li>Siapkan area dan berikan area itu secara eksplisit kepada administrator serta petugas gudang.
          {can('iam.area.view') && <> <Link to="/areas">Kelola area</Link>.</>}{can('iam.user.view') && <> <Link to="/users">Atur area pengguna</Link>.</>}</li>
        <li>Buat gudang utama dan bin pada area yang sesuai melalui tab Lokasi.</li>
        <li>Buat barang beserta satuan dan pelacakannya, lalu pemasok.</li>
        <li>Berikan akses lokasi kepada petugas melalui tab Akses gudang.</li>
        <li>Catat penerimaan dan pemeriksaan untuk menambah stok.{can('inventory.receipt.view') && <> <Link to="/warehouse/receipts">Buka penerimaan</Link>.</>}</li>
      </ol>
      {!user?.platformAdmin && user?.areaIds.length === 0 && <p className="error">Akun Anda belum memiliki area gudang. Area kosong berarti tidak memiliki akses gudang.</p>}
      <p className="muted">Membuat lokasi atau barang tidak menambah stok. Perubahan role dan area dapat memerlukan login ulang.</p>
    </details>
    <Tabs tabs={visible.map(({ key, label }) => ({ key, label }))} active={selected ?? 'locations'} onChange={tab => setParams({ tab })} />
    {selected === 'locations' && <WarehouseMasterPanel title="Lokasi" load={listLocations} archive={archiveLocation} canManage={can('inventory.location.manage')}
      emptyHint="Hanya lokasi dalam cakupan gudang dan area Anda yang ditampilkan. Gunakan Tambah lokasi untuk memulai."
      columns={[{ key: 'eligibility', header: 'Sumber pengeluaran', cell: row => row.issueEligible ? 'Diizinkan' : 'Tidak diizinkan' }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseLocationEditor row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}
    {selected === 'skus' && <WarehouseMasterPanel title="Barang" load={listSkus} archive={archiveSku} canManage={can('inventory.sku.manage')}
      emptyHint="Buat barang dengan satuan dan cara pelacakan yang sesuai sebelum mencatat penerimaan."
      columns={[{ key: 'tracking', header: 'Pelacakan', cell: row => ({ SERIAL: 'Perangkat serial', LOT: 'Lot / gulungan', BULK: 'Curah' })[row.tracking] },
        { key: 'minimum', header: 'Stok minimum', cell: row => <WarehouseQuantity value={row.minimumQuantityBase} unit={row.baseUnit} /> }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseSkuEditor row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}
    {selected === 'suppliers' && <WarehouseMasterPanel title="Pemasok" load={listSuppliers} archive={archiveSupplier} canManage={can('inventory.receipt.manage')}
      emptyHint="Tambahkan pemasok untuk menghubungkan penerimaan dengan sumber pengadaan."
      columns={[{ key: 'contact', header: 'Kontak / referensi', cell: row => row.contactReference ?? '—' }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseSupplierEditor row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}
    {selected === 'access' && <WarehouseScopePanel />}
  </div>
}
