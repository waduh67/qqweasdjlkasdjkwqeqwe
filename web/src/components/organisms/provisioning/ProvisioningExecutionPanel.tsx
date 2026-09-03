import { Text } from '@fluentui/react-components'
import type { ExecutionTimelineEntry, ExecutionView } from '@/api/provisioning'
import { Badge, Button, EmptyState, StatusBadge } from '@/components/atoms'
import { EXECUTION_LABEL, executionTone } from './provisioningPresentation'

type ExecutionPanelProps = {
  readonly execution: ExecutionView | null
  readonly timeline: readonly ExecutionTimelineEntry[]
  readonly canCancel: boolean
  readonly cancelling: boolean
  readonly onCancel: () => void
}

export function ProvisioningExecutionPanel({ execution, timeline, canCancel, cancelling, onCancel }: ExecutionPanelProps) {
  if (!execution) return <EmptyState title="Belum ada eksekusi" hint="Tinjau plan dan jalankan apply produksi untuk melihat progres per perangkat." />
  const cancellable = execution.status === 'QUEUED' || execution.status === 'RUNNING' || execution.status === 'VERIFYING'
  return (
    <div className="stack">
      <section className="card stack" aria-live="polite">
        <div className="spread wrap">
          <div className="workspace-title-group"><Text as="h2" size={400} weight="semibold">Eksekusi {execution.id}</Text><Text as="span" className="muted" size={200}>Revisi {execution.revision}</Text></div>
          <StatusBadge status={execution.status} tone={executionTone(execution.status)} label={EXECUTION_LABEL[execution.status]} />
        </div>
        {execution.status === 'MANUAL_RECONCILIATION' && <div role="alert" className="workspace-callout critical">Rollback otomatis tidak dapat diselesaikan. Bekukan perubahan lanjutan dan rekonsiliasi perangkat terhadap snapshot terakhir yang terverifikasi.</div>}
        {canCancel && <div><Button variant="danger" disabled={!cancellable || cancelling} onClick={onCancel}>{cancelling ? 'Membatalkan…' : 'Batalkan eksekusi'}</Button></div>}
      </section>
      <section className="card stack">
        <div className="spread wrap"><Text as="h2" size={400} weight="semibold">Urutan per perangkat</Text><Badge>{timeline.length} status</Badge></div>
        {timeline.length === 0 ? <Text as="p" className="muted">Menunggu langkah pertama dari server…</Text> : (
          <ol className="workspace-path" aria-label="Urutan eksekusi perangkat">
            {[...timeline].sort((left, right) => left.stepOrder - right.stepOrder || left.attemptNumber - right.attemptNumber).map((entry) => (
              <li key={`${entry.stepOrder}-${entry.attemptNumber}-${entry.phase}`}>
                <span className="workspace-step-index" aria-hidden>{entry.stepOrder}</span>
                <div className="grow min-w-0">
                  <div className="spread wrap"><Text as="strong" weight="semibold">Langkah {entry.stepOrder} · {phaseLabel(entry.phase)}</Text><StatusBadge status={entry.status} tone={attemptTone(entry.status)} label={attemptLabel(entry.status)} /></div>
                  <Text as="span" className="muted" size={200}>Percobaan {entry.attemptNumber} · {new Date(entry.startedAt).toLocaleString('id-ID')}</Text>
                  {entry.errorCode && <div className="row wrap workspace-error-code"><Badge tone="critical">{entry.errorCode}</Badge><Text as="span" size={200}>Kode stabil dari server, bukan kegagalan generik.</Text></div>}
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}

function phaseLabel(phase: string): string {
  const labels: Record<string, string> = { PREFLIGHT: 'Pemeriksaan awal', APPLY: 'Apply', VERIFY: 'Verifikasi', ROLLBACK: 'Rollback', OBSERVE: 'Observasi' }
  return labels[phase] ?? phase
}

function attemptLabel(status: string): string {
  const labels: Record<string, string> = { SUCCEEDED: 'Berhasil', FAILED: 'Gagal', RUNNING: 'Berjalan', PENDING: 'Menunggu', CANCELLED: 'Dibatalkan' }
  return labels[status] ?? status
}

function attemptTone(status: string): 'neutral' | 'good' | 'warning' | 'critical' | 'accent' {
  if (status === 'SUCCEEDED') return 'good'
  if (status === 'FAILED') return 'critical'
  if (status === 'RUNNING') return 'accent'
  if (status === 'PENDING') return 'warning'
  return 'neutral'
}
