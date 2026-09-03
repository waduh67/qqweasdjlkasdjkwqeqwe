import { useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import type { AdapterCertificationView, CapabilityEvidenceView, DriftView } from '@/api/provisioning'
import { Button, EmptyState, StatusBadge, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules'
import { DEVICE_LABEL } from './provisioningPresentation'

export function DriftPanel({ drift, canAdopt, adoptingId, onAdopt }: { readonly drift: readonly DriftView[]; readonly canAdopt: boolean; readonly adoptingId: string | null; readonly onAdopt: (item: DriftView) => void }) {
  if (drift.length === 0) return <EmptyState title="Tidak ada drift" hint="Belum ada perbedaan antara baseline dan observasi perangkat." />
  return (
    <section className="workspace-grid" aria-label="Daftar drift konfigurasi">
      {drift.map((item) => (
        <article className="card stack" key={item.id}>
          <div className="spread wrap"><div><Text as="h2" size={400} weight="semibold">{DEVICE_LABEL[item.deviceKind]}</Text><Text as="span" className="muted workspace-code" size={200}>{item.deviceId}</Text></div><StatusBadge status={item.status === 'NONE' ? 'ACTIVE' : item.status === 'BENIGN' ? 'WARNING' : 'CRITICAL'} label={driftLabel(item.status)} /></div>
          <Text as="span" className="muted" size={200}>Revisi {item.revision} · {new Date(item.recordedAt).toLocaleString('id-ID')}</Text>
          {item.status === 'BENIGN' && <Button variant="default" disabled={!canAdopt || adoptingId === item.id} onClick={() => onAdopt(item)}>{adoptingId === item.id ? 'Mengadopsi…' : 'Adopsi baseline'}</Button>}
        </article>
      ))}
    </section>
  )
}

type CertificationPanelProps = {
  readonly tenantId: string | null
  readonly capabilities: readonly CapabilityEvidenceView[]
  readonly certifications: readonly AdapterCertificationView[]
  readonly canCertify: boolean
  readonly onCertify: (tenantId: string, capability: CapabilityEvidenceView, validUntil: string) => Promise<void>
  readonly onRevoke: (certification: AdapterCertificationView) => Promise<void>
  readonly onError: (cause: unknown) => void
}

export function CertificationPanel(props: CertificationPanelProps) {
  const [open, setOpen] = useState(false)
  const [capabilityId, setCapabilityId] = useState('')
  const [validUntil, setValidUntil] = useState('')
  const [saving, setSaving] = useState(false)
  const selected = useMemo(() => props.capabilities.find((capability) => capability.id === capabilityId), [capabilityId, props.capabilities])
  const submit = async () => {
    if (!props.tenantId || !selected || validUntil === '') return
    setSaving(true)
    try {
      await props.onCertify(props.tenantId, selected, new Date(validUntil).toISOString())
      setOpen(false)
    } catch (cause) {
      props.onError(cause)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="stack">
      <section className="card stack">
        <div className="spread wrap"><div className="workspace-title-group"><Text as="h2" size={400} weight="semibold">Matriks kapabilitas dan sertifikasi</Text><Text as="p" className="muted" size={200}>Fingerprint vendor, model, firmware, transport, dan operasi harus cocok persis.</Text></div>{props.canCertify && <Button variant="primary" disabled={!props.tenantId || props.capabilities.length === 0} onClick={() => setOpen(true)}>Sertifikasi adapter</Button>}</div>
        {!props.canCertify && <div className="workspace-callout warning">Kontrol sertifikasi hanya tersedia untuk platform admin. Operator tenant tetap dapat melihat keputusan keselamatan dari server.</div>}
      </section>
      {props.capabilities.length === 0 ? <EmptyState title="Belum ada bukti kapabilitas" hint="Jalankan discovery adapter agar matriks dapat dievaluasi." /> : (
        <section className="workspace-grid" aria-label="Matriks kapabilitas adapter">
          {props.capabilities.map((capability) => {
            const certification = props.certifications.find((candidate) => candidate.deviceKind === capability.deviceKind && candidate.deviceId === capability.deviceId && candidate.operationClass === capability.operationClass && candidate.revokedAt === null)
            return (
              <article className="card stack" key={capability.id}>
                <div className="spread wrap"><Text as="h2" size={400} weight="semibold">{capability.vendor} {capability.model}</Text><StatusBadge status={certification?.status === 'CERTIFIED' ? 'ACTIVE' : capability.supported ? 'WARNING' : 'CRITICAL'} label={certification?.status === 'CERTIFIED' ? 'Tersertifikasi' : capability.supported ? 'Provisional' : 'Tidak didukung'} /></div>
                <div className="workspace-kv"><span>Firmware</span><strong>{capability.firmware}</strong></div><div className="workspace-kv"><span>Transport</span><strong>{capability.transport}</strong></div><div className="workspace-kv"><span>Operasi</span><strong>{capability.operationClass}</strong></div>
                {certification && props.canCertify && <Button variant="danger" onClick={() => void props.onRevoke(certification)}>Cabut sertifikasi</Button>}
              </article>
            )
          })}
        </section>
      )}
      {open && (
        <Modal title="Sertifikasi adapter" onClose={() => !saving && setOpen(false)} footer={<><Button variant="subtle" disabled={saving} onClick={() => setOpen(false)}>Batal</Button><Button variant="primary" disabled={!selected || validUntil === '' || saving} onClick={() => void submit()}>{saving ? 'Menyimpan…' : 'Sertifikasi'}</Button></>}>
          <div className="stack">
            <label className="stack workspace-field"><Text as="span" weight="semibold">Fingerprint adapter</Text><select value={capabilityId} onChange={(event) => setCapabilityId(event.target.value)}><option value="">Pilih fingerprint</option>{props.capabilities.map((capability) => <option key={capability.id} value={capability.id}>{capability.vendor} {capability.model} · {capability.operationClass}</option>)}</select></label>
            <TextField type="datetime-local" label="Berlaku sampai" value={validUntil} onChange={(_, data) => setValidUntil(data.value)} />
            <div className="workspace-callout warning">Sertifikasi mengizinkan produksi hanya untuk fingerprint dan operasi eksak ini. Server tetap memvalidasi bukti saat apply.</div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function driftLabel(status: DriftView['status']): string {
  const labels: Record<DriftView['status'], string> = { NONE: 'Selaras', BENIGN: 'Benign, dapat diadopsi', CONFLICTING: 'Konflik', UNKNOWN: 'Belum diketahui' }
  return labels[status]
}
