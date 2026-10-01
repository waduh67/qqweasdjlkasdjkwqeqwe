import { WarehouseFacts } from '@/components/organisms/warehouse/WarehouseFacts'
import { DataTable } from '@/components/organisms/DataTable'
import { Disclosure } from '@/components/molecules/Disclosure'
import type { MigrationCase } from '@/api/warehouse/provenanceModels'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { baselinePreview, migrationSourceLabels, claimLabels } from './provenancePresentation'

export function MigrationStockPreview({ source, unit }: { source: MigrationCase; unit: string }) {
  const value = baselinePreview(source, unit)
  return value ? <p>Calon saldo: <strong><WarehouseQuantity value={value.quantityBase} unit={value.baseUnit} /></strong> di {source.location?.name || source.location?.code}. Kepemilikan harus terbukti milik ISP.</p>
    : <p className="muted">Pilih satuan yang dibuktikan dokumen. Kuantitas tetap berasal dari catatan asli; jumlah yang belum dapat dibuktikan tidak menjadi stok tersedia.</p>
}
export function MigrationSourceDetails({ source }: { source: MigrationCase }) {
  return <div className="stack" style={{ overflowWrap: 'anywhere' }}>
    <WarehouseFacts items={[
      { label: 'Jenis catatan', value: migrationSourceLabels[source.sourceTable] }, { label: 'Lokasi', value: source.location?.name || source.location?.code || 'Belum terverifikasi' },
      ...(source.source.serial !== null ? [{ label: 'Serial asli', value: <span style={{ whiteSpace: 'pre-wrap' }}>{source.source.serial || '(kosong)'}</span> }] : []),
      ...(source.source.mac !== null ? [{ label: 'MAC asli', value: <span style={{ whiteSpace: 'pre-wrap' }}>{source.source.mac || '(kosong)'}</span> }] : []),
      ...(source.source.legacyQuantity !== null ? [{ label: 'Jumlah lama', value: source.source.legacyQuantity }, { label: 'Satuan', value: source.source.baseUnit || 'Belum terverifikasi' }] : []),
      ...(source.customer ? [{ label: 'Pelanggan', value: source.customer.name || 'Nama belum tercatat' }] : []),
      ...(source.workOrder ? [{ label: 'Pekerjaan', value: source.workOrder.code || source.workOrder.name || 'Pekerjaan lama' }] : []),
    ]} />
    <Disclosure title={<>Referensi audit kasus</>}><p>Kasus: {source.id}</p><p>Sumber: {source.sourceId}</p><p>Sidik bukti: {source.sourceHash}</p></Disclosure>
    {!!source.claims.length && <DataTable presentation="warehouse" rows={source.claims.map((claim, index) => ({ ...claim, index }))} rowKey={row => String(row.index)} columns={[
      { key: 'type', header: 'Jenis identitas', cell: row => row.identityType === 'SERIAL' ? 'Serial' : 'MAC' },
      { key: 'value', header: 'Nilai asli', cell: row => <span style={{ whiteSpace: 'pre' }}>{row.rawValue || '(kosong)'}</span> },
      { key: 'state', header: 'Status', cell: row => row.state ? claimLabels[row.state] : 'Perlu diperiksa' },
      { key: 'canonical', header: 'Identitas pembanding', cell: row => row.canonicalValue ?? '—' },
      { key: 'count', header: 'Catatan', cell: row => row.candidateCount, align: 'right' },
    ]} />}

  </div>
}
