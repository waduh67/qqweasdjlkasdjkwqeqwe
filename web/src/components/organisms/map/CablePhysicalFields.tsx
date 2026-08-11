import { SelectField } from '@/components/atoms'
import { CABLE_INSTALLATION_LABEL, CABLE_OWNERSHIP_LABEL } from '@/api/network'
import type { CableInstallation, CableOwnership } from '@/api/network'

/**
 * Sepasang dropdown "fisik jalur", dipakai form kabel baru maupun panel kabel
 * tersimpan supaya pilihan dan katanya persis sama di kedua tempat. Nilai ''
 * pada [installation] berarti belum disurvei — dilaporkan ke pemanggil sebagai
 * `null`, bukan ditebak.
 */
export function CablePhysicalFields({
  installation,
  ownership,
  onInstallation,
  onOwnership,
}: {
  installation: CableInstallation | ''
  ownership: CableOwnership
  onInstallation: (value: CableInstallation | null) => void
  onOwnership: (value: CableOwnership) => void
}) {
  return (
    <div className="stack" style={{ gap: '0.3rem' }}>
      <div className="row" style={{ gap: '0.4rem' }}>
        <SelectField
          label="Cara pasang"
          value={installation}
          onChange={(_, data) => onInstallation(data.value === '' ? null : (data.value as CableInstallation))}
          style={{ flex: 1 }}
        >
          <option value="">Belum disurvei</option>
          {(Object.keys(CABLE_INSTALLATION_LABEL) as CableInstallation[]).map((value) => (
            <option key={value} value={value}>
              {CABLE_INSTALLATION_LABEL[value]}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Kepemilikan"
          value={ownership}
          onChange={(_, data) => onOwnership(data.value as CableOwnership)}
          style={{ width: '9rem' }}
        >
          {(Object.keys(CABLE_OWNERSHIP_LABEL) as CableOwnership[]).map((value) => (
            <option key={value} value={value}>
              {CABLE_OWNERSHIP_LABEL[value]}
            </option>
          ))}
        </SelectField>
      </div>
      <span className="muted" style={{ fontSize: '0.78rem' }}>
        Penentu siapa yang berangkat saat putus: tim tangga untuk jalur udara, tim galian untuk jalur tanam.
      </span>
    </div>
  )
}
