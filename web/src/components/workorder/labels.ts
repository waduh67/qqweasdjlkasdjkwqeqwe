/**
 * Peta label, nada, dan helper work order yang dipakai bersama papan dispatch operator
 * (`WorkOrdersPage`) dan papan "Tugas Saya" teknisi (`MyWorkOrdersPage`) beserta isi
 * drawer detail. Murni data/fungsi (tanpa JSX) — satu sumber kebenaran agar tak disalin.
 */
import type {
  EvidenceKind,
  WorkOrderApprovalStatus,
  WorkOrderPriority,
  WorkOrderStatus,
  WorkOrderType,
  WorkOrderView,
} from '../../api/workorder'

export const TYPE_LABEL: Record<WorkOrderType, string> = {
  PSB: 'Pasang Baru',
  REPAIR: 'Perbaikan',
  MIGRATION: 'Migrasi',
  DISMANTLE: 'Bongkar',
  PREVENTIVE: 'Preventif',
}

export const STATUS_LABEL: Record<WorkOrderStatus, string> = {
  DRAFT: 'Draft',
  ASSIGNED: 'Ditugaskan',
  IN_PROGRESS: 'Dikerjakan',
  DONE: 'Selesai',
  CANCELLED: 'Batal',
}

export const STATUS_TONE: Record<WorkOrderStatus, 'neutral' | 'accent' | 'warning' | 'good'> = {
  DRAFT: 'neutral',
  ASSIGNED: 'accent',
  IN_PROGRESS: 'warning',
  DONE: 'good',
  CANCELLED: 'neutral',
}

export const PRIORITY_LABEL: Record<WorkOrderPriority, string> = {
  LOW: 'Rendah',
  NORMAL: 'Normal',
  HIGH: 'Tinggi',
  URGENT: 'Mendesak',
}

export const EVENT_LABEL: Record<string, string> = {
  CREATED: 'Dibuat',
  UPDATED: 'Diperbarui',
  ASSIGNED: 'Ditugaskan',
  STARTED: 'Mulai dikerjakan',
  COMPLETED: 'Selesai',
  CANCELLED: 'Dibatalkan',
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
}

export const APPROVAL_LABEL: Record<WorkOrderApprovalStatus, string> = {
  PENDING: 'Menunggu persetujuan',
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
}

export const APPROVAL_TONE: Record<WorkOrderApprovalStatus, 'warning' | 'good' | 'critical'> = {
  PENDING: 'warning',
  APPROVED: 'good',
  REJECTED: 'critical',
}

export const KIND_LABEL: Record<EvidenceKind, string> = {
  BEFORE: 'Sebelum',
  AFTER: 'Sesudah',
  LOCATION: 'Lokasi',
  SERIAL: 'Serial ONU',
  OTHER: 'Lainnya',
}

export const KINDS = Object.keys(KIND_LABEL) as EvidenceKind[]
export const TYPES = Object.keys(TYPE_LABEL) as WorkOrderType[]
export const PRIORITIES = Object.keys(PRIORITY_LABEL) as WorkOrderPriority[]
export const STATUSES = Object.keys(STATUS_LABEL) as WorkOrderStatus[]

export const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('id-ID') : '—')
export const fmtDbm = (v: number | null) => (v == null ? '—' : `${v.toFixed(2)} dBm`)

export type RxTone = 'good' | 'warning' | 'critical'

/** Klasifikasi kasar redaman Rx ONU GPON (dBm): makin mendekati 0 makin kuat, di bawah −28 makin lemah. */
export function rxHealth(dbm: number): { tone: RxTone; label: string } {
  if (dbm > -8) return { tone: 'critical', label: 'terlalu kuat' }
  if (dbm >= -25) return { tone: 'good', label: 'sehat' }
  if (dbm >= -28) return { tone: 'warning', label: 'waspada' }
  return { tone: 'critical', label: 'lemah' }
}

/** Nada prioritas: tinggi/mendesak menonjol (warning), sisanya netral. */
export const priorityTone = (p: WorkOrderPriority): 'warning' | 'neutral' =>
  p === 'URGENT' || p === 'HIGH' ? 'warning' : 'neutral'

/** Nama roster teknisi digabung untuk teks/sortir; kosong bila belum ditugaskan. */
export const assigneeLabel = (wo: WorkOrderView): string =>
  wo.assignees.map((a) => a.name ?? '—').join(', ')

/** Apakah pilihan roster identik dengan roster tersimpan (abaikan urutan) → tombol nonaktif. */
export const sameRoster = (ids: string[], current: WorkOrderView['assignees']): boolean =>
  ids.length === current.length && ids.every((id) => current.some((a) => a.id === id))

/**
 * Kontrak aksi lifecycle dari detail ke halaman pemanggil: jalankan `action`,
 * tampilkan `ok`, lalu `keepOpen` menentukan drawer tetap terbuka (mis. mulai/selesai
 * yang perlu memuat ulang detail) atau ditutup (mis. hapus WO).
 */
export type ActFn = (action: () => Promise<unknown>, ok: string, keepOpen: boolean) => void
