/**
 * Potongan tampilan work order kecil yang dipakai bersama (baris tabel & drawer detail):
 * badge status, satu pasang label/nilai grid, dan chip roster teknisi. Dipisah dari
 * `labels.ts` (data murni) agar berkas ini hanya mengekspor komponen.
 */
import type { ReactNode } from 'react'
import { Badge } from '../ui'
import type { WorkOrderStatus, WorkOrderView } from '../../api/workorder'
import { STATUS_LABEL, STATUS_TONE } from './labels'

export function WoStatusBadge({ status }: { status: WorkOrderStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
}

/** Satu pasang label/nilai dalam grid ringkasan (`.wo-grid`). */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  )
}

/** Chip nama tiap teknisi di roster (tim datar); "belum ditugaskan" bila kosong. */
export function AssigneeChips({ wo }: { wo: WorkOrderView }) {
  if (wo.assignees.length === 0) return <span className="muted">belum ditugaskan</span>
  return (
    <div className="row wrap" style={{ gap: '0.3rem' }}>
      {wo.assignees.map((a) => (
        <span key={a.id} className="badge">
          {a.name ?? '—'}
        </span>
      ))}
    </div>
  )
}
