import { useSearchParams } from 'react-router-dom'
import { archiveReferenceLocation, archiveReferenceSku, archiveReferenceSupplier, getReferenceLocation, listReferenceLocations, listReferenceSkus, listReferenceSuppliers, saveReferenceLocation, saveReferenceSku, saveReferenceSupplier } from '@/api/warehouse/referenceCatalog'
import { listReferenceAreas } from '@/api/warehouse/referenceCatalog'
import { useCan } from '@/auth/useCan'
import { PageHeader, Tabs } from '@/components/molecules'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseWorkflow } from './WarehouseWorkflowContext'
import { WarehouseMasterPanel } from './WarehouseMasterPanel'
import { WarehouseSkuEditor } from './WarehouseSkuEditor'
import { WarehouseSupplierEditor } from './WarehouseSupplierEditor'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'

const locationApi = { list: listReferenceLocations, get: getReferenceLocation, save: saveReferenceLocation }
const tabs = [{ key: 'skus', label: 'Barang' }, { key: 'locations', label: 'Gudang & Rak' }, { key: 'suppliers', label: 'Pemasok' }]
export function ReferenceCatalogPage() {
  const { can } = useCan(), [params, setParams] = useSearchParams()
  const selected = tabs.find(tab => tab.key === params.get('tab'))?.key ?? 'skus'
  const workflow = useWarehouseWorkflow(), manage = can('warehouse.catalog.manage')
  return <div className="stack"><PageHeader title="Barang, Gudang & Pemasok" subtitle="Daftarkan barang, gudang, dan pemasok. Stok fisik dicatat melalui Penerimaan." />
    <Tabs tabs={tabs} active={selected} onChange={tab => setParams({ tab })} />
    {selected === 'skus' && <WarehouseMasterPanel title="Barang" load={listReferenceSkus} archive={archiveReferenceSku} canManage={manage}
      emptyHint="Tambahkan barang dan satuannya sebelum mencatat stok masuk."
      columns={[{ key: 'tracking', header: 'Pelacakan', cell: row => ({ SERIAL: 'Perangkat serial', LOT: 'Lot / gulungan', BULK: 'Jumlah' })[row.tracking] },
        { key: 'minimum', header: 'Minimum per gudang', cell: row => <WarehouseQuantity value={row.minimumQuantityBase} unit={row.baseUnit} /> }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseSkuEditor reference save={saveReferenceSku} row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}
    {selected === 'locations' && <WarehouseState {...workflow}>{({ owner }) => <WarehouseMasterPanel title="Lokasi" load={listReferenceLocations} archive={archiveReferenceLocation} canManage={manage}
      emptyHint="Gudang Utama sudah dibuat otomatis. Tambahkan gudang atau rak lain sesuai kebutuhan."
      columns={[{ key: 'kind', header: 'Jenis', cell: row => row.kind === 'WAREHOUSE' ? 'Gudang' : 'Rak' }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseLocationEditor reference api={locationApi} scopedAreas={listReferenceAreas} unrestrictedAreas={owner} managePermission="warehouse.catalog.manage" allowedKinds={['WAREHOUSE', 'BIN']} row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}</WarehouseState>}
    {selected === 'suppliers' && <WarehouseMasterPanel title="Pemasok" load={listReferenceSuppliers} archive={archiveReferenceSupplier} canManage={manage}
      emptyHint="Tambahkan pemasok agar sumber barang masuk mudah ditelusuri."
      columns={[{ key: 'contact', header: 'Kontak / referensi', cell: row => row.contactReference ?? 'Belum diisi' }]}
      editor={(row, readOnly, onClose, onSaved, onReload) => <WarehouseSupplierEditor save={saveReferenceSupplier} row={row} readOnly={readOnly} onClose={onClose} onSaved={onSaved} onReload={onReload} />} />}
  </div>
}
