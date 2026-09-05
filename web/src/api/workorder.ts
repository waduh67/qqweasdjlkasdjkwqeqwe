/** Work order sisi operator/dispatcher (module `workorder`). */

import { api } from './client'
import type { PageResponse } from './types'

export type WorkOrderType = 'PSB' | 'REPAIR' | 'MIGRATION' | 'DISMANTLE' | 'PREVENTIVE'
export type WorkOrderStatus = 'DRAFT' | 'ASSIGNED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
export type WorkOrderPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type WorkOrderApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface WorkOrderView {
  id: string
  code: string
  type: WorkOrderType
  status: WorkOrderStatus
  priority: WorkOrderPriority
  title: string
  description: string | null
  customerId: string | null
  customerName: string | null
  incidentId: string | null
  areaId: string | null
  /** Koordinat lokasi pelanggan tertaut untuk navigasi teknisi; `null` bila tak tertaut. */
  destinationLat: number | null
  destinationLng: number | null
  /** Roster teknisi ter-assign (tim datar, semua setara); kosong bila belum ditugaskan. */
  assignees: WorkOrderAssigneeView[]
  scheduledAt: string | null
  assignedAt: string | null
  startedAt: string | null
  completedAt: string | null
  resolutionNote: string | null
  cancelReason: string | null
  /** Redaman optik (dBm) sebelum & sesudah pengerjaan; `null` bila belum diukur. */
  rxBeforeDbm: number | null
  rxAfterDbm: number | null
  /** Kurasi hasil kerja: PENDING/APPROVED/REJECTED; `null` bila WO belum pernah selesai. */
  approvalStatus: WorkOrderApprovalStatus | null
  approvedBy: string | null
  approvedByName: string | null
  approvedAt: string | null
  approvalNote: string | null
  createdAt: string
}

/** Seorang teknisi di roster WO; nama diresolusi lewat iam (`null` bila pengguna sudah tak ada). */
export interface WorkOrderAssigneeView {
  id: string
  name: string | null
}

export interface WorkOrderEventView {
  type: string
  message: string
  at: string
}

export interface TechnicianWorkloadView {
  technicianId: string
  technicianName: string | null
  openCount: number
}

/** Ringkasan papan dispatch (`GET /api/work-orders/dashboard`). */
export interface WorkOrderDashboardView {
  total: number
  open: number
  unassignedOpen: number
  /** Hasil kerja selesai yang menunggu dikurasi penyelia. */
  pendingApproval: number
  byStatus: Record<WorkOrderStatus, number>
  byType: Record<WorkOrderType, number>
  workloads: TechnicianWorkloadView[]
}

export interface WorkOrderDetail {
  workOrder: WorkOrderView
  timeline: WorkOrderEventView[]
}

/** Bukti pengerjaan (Phase 4.2): foto & tanda tangan. Byte diambil via `api.blob`. */
export type EvidenceKind =
  | 'FAT'
  | 'ODP'
  | 'DROPCORE'
  | 'ONT'
  | 'ONU'
  | 'OPTICAL_BEFORE'
  | 'OPTICAL_AFTER'
  | 'TECHNICIAN_SIGNATURE'
  | 'CUSTOMER_ACKNOWLEDGEMENT'
  | 'BEFORE'
  | 'AFTER'
  | 'LOCATION'
  | 'SERIAL'
  | 'OTHER'

export type ProofArtifactKind =
  | 'FAT'
  | 'ODP'
  | 'DROPCORE'
  | 'ONT'
  | 'ONU'
  | 'OPTICAL_BEFORE'
  | 'OPTICAL_AFTER'
  | 'TECHNICIAN_SIGNATURE'
  | 'CUSTOMER_ACKNOWLEDGEMENT'
  | 'LOCATION'

export interface EvidenceView {
  revisionId: string
  workOrderId: string
  kind: EvidenceKind
  caption: string | null
  contentType: string
  sizeBytes: number
  latitude: number | null
  longitude: number | null
  capturedAt: string | null
  uploadedBy: string
  uploadedByName: string | null
  createdAt: string
}

export interface SignatureView {
  revisionId: string
  workOrderId: string
  signerName: string
  contentType: string
  sizeBytes: number
  signedBy: string
  signedByName: string | null
  signedAt: string
  createdAt: string
}

export interface ProofOfWorkView {
  revision: string
  artifacts: ProofArtifactRevisionView[]
}

export interface ProofArtifactRevisionView {
  kind: ProofArtifactKind
  revisionId: string
  label: string
}

/**
 * Riwayat work order satu pelanggan (semua status), terbaru dulu. Endpoint search
 * mengembalikan halaman — ambil `.content`. Ukuran besar agar riwayat pendek utuh
 * dalam satu tarik untuk panel Subscriber-360.
 */
export const listWorkOrdersForCustomer = (customerId: string) =>
  api
    .get<PageResponse<WorkOrderView>>(`/api/work-orders?customerId=${customerId}&size=100`)
    .then((page) => page.content)

/** Work order yang belum tutup buku — satu-satunya yang masuk akal jadi wadah kerja baru. */
const OPEN = (wo: WorkOrderView) => wo.status !== 'DONE' && wo.status !== 'CANCELLED'

/**
 * Kandidat tiket untuk picker "kerja ini bagian dari tugas mana", dipakai meja splicing.
 *
 * Dua sumber, sesuai izin pemakainya: dispatcher/operator (`workorder.order.view`) mencari
 * ke seluruh papan lewat pencarian server, teknisi lapangan hanya melihat tugasnya sendiri
 * (`/mine`) dan disaring di sini karena endpoint itu tak menerima kata kunci. Yang sudah
 * selesai/batal disingkirkan: menempelkan kerja lapangan ke tiket yang sudah tutup buku
 * hampir selalu salah pilih, dan server pun akan menolaknya kalau memang bukan tiketnya.
 */
export const searchOpenWorkOrders = async (term: string, mine: boolean): Promise<WorkOrderView[]> => {
  const keyword = term.trim()
  const path = mine
    ? '/api/work-orders/mine?size=50'
    : `/api/work-orders?size=50${keyword ? `&query=${encodeURIComponent(keyword)}` : ''}`
  const page = await api.get<PageResponse<WorkOrderView>>(path)
  const needle = keyword.toLowerCase()
  return page.content
    .filter(OPEN)
    .filter((wo) => !mine || !needle || `${wo.code} ${wo.title}`.toLowerCase().includes(needle))
    .slice(0, 20)
}

/**
 * Papan tugas milik teknisi yang sedang login (`GET /api/work-orders/mine`), opsional
 * disaring status. Kontrak yang sama dikonsumsi aplikasi teknisi mobile untuk
 * menampilkan "tugas saya".
 */
export const listMyWorkOrders = (status?: WorkOrderStatus) =>
  api.get<PageResponse<WorkOrderView>>(
    `/api/work-orders/mine${status ? `?status=${status}` : ''}`,
  )
