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
    <p>{migrationSourceLabels[source.sourceTable]} · {source.location ? source.location.name || source.location.code : 'Lokasi belum tercatat atau perlu diperiksa'}</p>
    {source.source.serial !== null && <p>Serial asli: <code style={{ whiteSpace: 'pre-wrap' }}>{source.source.serial || '(kosong)'}</code></p>}
    {source.source.mac !== null && <p>MAC asli: <code style={{ whiteSpace: 'pre-wrap' }}>{source.source.mac || '(kosong)'}</code></p>}
    {source.source.legacyQuantity !== null && <p>Jumlah pada catatan lama: <strong>{source.source.legacyQuantity}</strong> · {source.source.baseUnit ? 'Satuan tercatat: ' + source.source.baseUnit : 'Satuan belum terbukti'}</p>}
    {source.customer && <p>Pelanggan: {source.customer.name || 'Nama belum tercatat'}</p>}
    {source.workOrder && <p>Pekerjaan: {source.workOrder.code || source.workOrder.name || 'Catatan pekerjaan lama'}</p>}
    <details><summary>Referensi audit kasus</summary><p>Kasus: {source.id}</p><p>Sumber: {source.sourceId}</p><p>Sidik bukti: {source.sourceHash}</p></details>
    {!!source.claims.length && <ul className="stack">{source.claims.map((claim, index) => <li key={index}>
      <span>{claim.identityType === 'SERIAL' ? 'Serial' : 'MAC'}: <code style={{ whiteSpace: 'pre-wrap' }}>{claim.rawValue || '(kosong)'}</code> · {claim.state ? claimLabels[claim.state] : 'Format identitas perlu diperiksa'}</span>
      {claim.canonicalValue && <p className="muted">Identitas pembanding: {claim.canonicalValue} · {claim.candidateCount} catatan tersimpan</p>}
    </li>)}</ul>}
  </div>
}
