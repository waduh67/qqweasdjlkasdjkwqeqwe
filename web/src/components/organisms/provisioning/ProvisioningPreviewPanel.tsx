import { Text } from '@fluentui/react-components'
import type { CapabilityEvidenceView, PlanPreview } from '@/api/provisioning'
import { Badge, Button, StatusBadge, TextField } from '@/components/atoms'
import { DEVICE_LABEL, stableCodeLabel, type ProductionReadiness } from './provisioningPresentation'

type PreviewPanelProps = {
  readonly planId: string
  readonly preview: PlanPreview | null
  readonly capabilities: readonly CapabilityEvidenceView[]
  readonly readiness: ProductionReadiness
  readonly loading: boolean
  readonly applying: boolean
  readonly canPreview: boolean
  readonly canApply: boolean
  readonly autoApplyEnabled: boolean
  readonly onPlanIdChange: (value: string) => void
  readonly onPreview: () => void
  readonly onApply: () => void
}

const visibleAttribute = (key: string) => !key.startsWith('safety.') && key !== 'intentId'

export function ProvisioningPreviewPanel(props: PreviewPanelProps) {
  const { preview, readiness } = props
  return (
    <div className="stack">
      <div className="card stack">
        <Text as="h2" size={400} weight="semibold">Pratinjau plan</Text>
        <div className="workspace-actions">
          <TextField label="ID plan aktif" hint="Salin ID plan yang dibuat server untuk intent terpilih." value={props.planId} onChange={(_, data) => props.onPlanIdChange(data.value)} />
          <Button variant="default" disabled={!props.canPreview || props.planId.trim() === '' || props.loading} onClick={props.onPreview}>Pratinjau dry-run</Button>
          <Button variant="primary" disabled={!props.canApply || !readiness.ready || props.applying} onClick={props.onApply}>{props.applying ? 'Menerapkan…' : 'Terapkan ke produksi'}</Button>
        </div>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Dry-run tetap tersedia untuk adapter provisional. Produksi mengikuti validasi server dan tidak pernah melewati gerbang keselamatan.</Text>
        {!props.autoApplyEnabled && <div role="status" className="workspace-callout warning">Auto-apply produksi dinonaktifkan oleh konfigurasi rollout. Dry-run tetap tersedia.</div>}
      </div>

      {preview && (
        <>
          <section className="workspace-safety-grid" aria-label="Ringkasan keselamatan produksi">
            <SafetyState label="Keputusan server" ready={preview.decision.allowed} />
            <SafetyState label="Kapabilitas adapter" ready={readiness.capabilityReady} />
            <SafetyState label="Sertifikasi eksak" ready={readiness.certificationReady} />
            <SafetyState label="Proteksi manajemen" ready={readiness.protectionReady} />
          </section>
          <section className="card stack" aria-labelledby="decision-title">
            <div className="spread wrap"><Text as="h2" size={400} weight="semibold" id="decision-title">Keputusan dan dampak</Text><Badge tone={preview.decision.allowed ? 'good' : 'critical'}>{preview.decision.code}</Badge></div>
            <Text as="span" size={300}>{stableCodeLabel(preview.decision.code)}</Text>
            {preview.decision.warnings.map((warning) => <div role="alert" className="workspace-callout warning" key={warning}>{warning}</div>)}
            {readiness.reasons.map((reason) => <div role="alert" className="workspace-callout critical" key={reason}>{reason}</div>)}
          </section>
          <section className="card stack" aria-labelledby="steps-title">
            <div className="spread wrap"><Text as="h2" size={400} weight="semibold" id="steps-title">Jalur dan langkah perangkat</Text><Badge>{preview.plan.steps.length} langkah</Badge></div>
            <ol className="workspace-path" aria-label="Jalur perubahan perangkat">
              {[...preview.plan.steps].sort((left, right) => left.order - right.order).map((step) => {
                const capability = props.capabilities.find((candidate) => candidate.deviceKind === step.device.kind && candidate.deviceId === step.device.id && candidate.operationClass === step.operation)
                return (
                  <li key={step.id}>
                    <span className="workspace-step-index" aria-hidden>{step.order}</span>
                    <div className="stack grow min-w-0 workspace-step-content">
                      <div className="spread wrap"><div><Text as="strong" block weight="semibold">{DEVICE_LABEL[step.device.kind]}</Text><Text as="span" block className="muted workspace-code" size={200}>{step.device.id}</Text><Text as="span" className="muted" size={200}>{step.operation}</Text></div><StatusBadge status={capability?.supported ? 'ACTIVE' : 'WARNING'} label={capability?.supported ? 'Kapabel' : 'Provisional'} /></div>
                      {capability && <Text as="span" className="muted workspace-code" size={200}>{capability.vendor} / {capability.model} / {capability.firmware} / {capability.transport}</Text>}
                      <Text as="span" className="muted" size={200}>Diff ternormalisasi</Text>
                      <div className="workspace-diff" aria-label={`Diff ternormalisasi langkah ${step.order}`}>
                        {Object.entries(step.attributes).filter(([key]) => visibleAttribute(key)).map(([key, value]) => <div className="workspace-kv" key={key}><span>{attributeLabel(key)}</span><strong>{value}</strong></div>)}
                      </div>
                      <div className="workspace-kv"><span>Hash sebelum</span><strong className="workspace-code">{step.preconditionHash}</strong></div>
                    </div>
                  </li>
                )
              })}
            </ol>
          </section>
        </>
      )}
    </div>
  )
}

function SafetyState({ label, ready }: { readonly label: string; readonly ready: boolean }) {
  return <div className="card workspace-safety-item"><StatusBadge status={ready ? 'ACTIVE' : 'WARNING'} label={ready ? 'Lulus' : 'Belum lulus'} /><Text as="span" size={200}>{label}</Text></div>
}

function attributeLabel(key: string): string {
  const labels: Record<string, string> = { vlanId: 'VLAN', interface: 'Port / interface', onuId: 'ONU', onuSerial: 'Serial ONU', firewallAllowList: 'Firewall allow-list', blastRadius: 'Blast radius', pppoeProfile: 'Profil PPPoE' }
  return labels[key] ?? key.replace(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toUpperCase())
}
