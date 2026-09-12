import { api } from './client'
import type { PageResponse } from './types'

/**
 * Pesanan sisi OPERATOR (`/api/orders`). Bentuk datanya diturunkan dari
 * `OrderController`, `OrderLeadController`, dan `OrderImportController` di server.
 *
 * JANGAN disamakan dengan `src/portal/portalClient.ts`: itu permukaan PELANGGAN yang sengaja
 * hanya memuat kosakata aman-dibaca-pelanggan. Berkas ini memuat alamat, alasan penolakan, dan
 * riwayat internal — semuanya hanya untuk konsol berizin `order.*`.
 */

export type OrderStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'ACCEPTED'
  | 'SCHEDULED'
  | 'FULFILLING'
  | 'FULFILLED'
  | 'CANCELLED'
  | 'REJECTED'

export type OrderTransition = 'SUBMIT' | 'ACCEPT' | 'SCHEDULE' | 'START_FULFILLING' | 'FULFILL' | 'CANCEL' | 'REJECT'

/** Penanda yang MENIMPA tampilan status di halaman lacak pelanggan; ortogonal terhadap [OrderStatus]. */
export type OrderPortalFlag = 'WAITING_CUSTOMER' | 'REQUIRES_ATTENTION'

export interface OrderSummaryView {
  readonly id: string
  readonly orderNumber: string
  readonly status: OrderStatus
  readonly customerId: string | null
  readonly leadId: string | null
  /** Null = pemesannya tak bisa diresolusi lagi (mis. pelanggan terhapus); barisnya tetap tampil. */
  readonly requesterName: string | null
  readonly requesterPhone: string | null
  readonly address: string
  readonly city: string
  readonly appointmentStartsAt: string | null
  readonly revision: number
  readonly createdAt: string
  readonly updatedAt: string
}

export interface OrderLineView {
  readonly catalogItemId: string
  readonly description: string
  readonly quantity: number
}

export interface OrderServiceAddress {
  readonly address: string
  readonly city: string
  readonly postalCode: string
  readonly latitude: number | null
  readonly longitude: number | null
}

export interface OrderAppointment {
  readonly startsAt: string
  readonly endsAt: string
}

export interface OrderView {
  readonly id: string
  readonly tenantId: string
  readonly customerId: string | null
  /** String polos, bukan [OrderStatus]: server memulangkan `status.name` dari agregat. */
  readonly status: OrderStatus
  readonly lines: OrderLineView[]
  readonly serviceAddress: OrderServiceAddress
  readonly appointment: OrderAppointment | null
  readonly cancellationReason: string | null
  readonly rejectionReason: string | null
  readonly revision: number
  readonly lastActorId: string | null
  readonly leadId: string | null
  readonly orderNumber: string | null
}

/**
 * Satu baris riwayat pesanan, sumbernya `order_audit`.
 *
 * [toStatus] TIDAK selalu berisi status pesanan: pada `ORDER_FLAGGED` kolom itu dipakai untuk
 * nama PENANDA-nya (lihat `OrderAttentionService`), karena itulah yang berubah di kejadian itu.
 */
export interface OrderTimelineEntryView {
  readonly revision: number
  readonly eventType: string
  readonly fromStatus: string | null
  readonly toStatus: string
  readonly reason: string | null
  readonly actorId: string | null
  readonly occurredAt: string
}

/**
 * Identitas operasi idempoten yang dituntut setiap endpoint tulis pesanan.
 *
 * Nama fieldnya `key`, BUKAN `operationKey` seperti di `fieldservice.ts` — `OperationRequest`
 * di `OrderController` memakai `key`, dan salah nama berarti 400 "key wajib diisi" yang
 * menunjuk ke tempat yang salah.
 */
export interface OrderOperation {
  readonly namespace: string
  readonly key: string
  readonly payloadHash: string
}

/**
 * Bikin identitas operasi baru. Pemanggil WAJIB menyimpannya dan memakai ulang yang SAMA saat
 * mencoba lagi permintaan yang gagal tanpa respons.
 *
 * Alasannya konkret dan mahal: `ACCEPT` bukan sekadar pindah status — ia mempromosikan calon
 * pelanggan dan membuka WO PSB. Operator yang menekan "Terima" lagi karena responsnya hilang di
 * jaringan, dengan kunci baru, akan membuka WO PSB KEDUA untuk pemasangan yang sama, dan teknisi
 * berangkat dua kali ke rumah yang sama.
 */
export function newOrderOperation(namespace: string): OrderOperation {
  return {
    namespace,
    key: crypto.randomUUID(),
    // Hash acak, bukan digest isi payload: server hanya memakainya untuk MEMBANDINGKAN replay
    // kunci yang sama (`payload_hash varchar(128)`), dan pasangan kunci+hash selalu dipakai
    // ulang utuh. Pola yang sama dengan `fieldservice.ts`.
    payloadHash: crypto.randomUUID().replaceAll('-', '').repeat(2),
  }
}

/**
 * Status asal yang sah untuk tiap transisi — cermin `Order.transition` di server.
 *
 * Ada di klien supaya tombol yang PASTI ditolak tidak pernah ditawarkan. Tanpa ini operator
 * menekan "Jadwalkan" pada pesanan yang belum diterima dan hanya mendapat 409 "Transisi
 * SUBMITTED ke SCHEDULED tidak diizinkan" — pesan yang benar, tapi baru muncul setelah ia
 * mengisi janji temunya.
 *
 * FULFILL menerima ACCEPTED dan SCHEDULED SELAIN FULFILLING dengan sengaja: sejak P5.4 hidup
 * pesanan setelah diterima dikemudikan approval WO PSB, dan operator sering tak pernah menekan
 * SCHEDULE/START_FULFILLING sama sekali.
 */
export const ORDER_TRANSITION_SOURCES: Record<OrderTransition, readonly OrderStatus[]> = {
  SUBMIT: ['DRAFT'],
  ACCEPT: ['SUBMITTED'],
  SCHEDULE: ['ACCEPTED'],
  START_FULFILLING: ['SCHEDULED'],
  FULFILL: ['FULFILLING', 'SCHEDULED', 'ACCEPTED'],
  CANCEL: ['SUBMITTED', 'ACCEPTED', 'SCHEDULED'],
  REJECT: ['SUBMITTED'],
}

export function canTransition(status: OrderStatus, transition: OrderTransition): boolean {
  return ORDER_TRANSITION_SOURCES[transition].includes(status)
}

export const ORDER_STATUSES: readonly OrderStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'ACCEPTED',
  'SCHEDULED',
  'FULFILLING',
  'FULFILLED',
  'CANCELLED',
  'REJECTED',
]

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  DRAFT: 'Draf',
  SUBMITTED: 'Masuk',
  ACCEPTED: 'Diterima',
  SCHEDULED: 'Dijadwalkan',
  FULFILLING: 'Dikerjakan',
  FULFILLED: 'Selesai',
  CANCELLED: 'Dibatalkan',
  REJECTED: 'Ditolak',
}

export const ORDER_TRANSITION_LABEL: Record<OrderTransition, string> = {
  SUBMIT: 'Ajukan',
  ACCEPT: 'Terima',
  SCHEDULE: 'Jadwalkan',
  START_FULFILLING: 'Mulai kerjakan',
  FULFILL: 'Tandai selesai',
  CANCEL: 'Batalkan',
  REJECT: 'Tolak',
}

export const ORDER_PORTAL_FLAG_LABEL: Record<OrderPortalFlag, string> = {
  WAITING_CUSTOMER: 'Menunggu pelanggan',
  REQUIRES_ATTENTION: 'Perlu perhatian',
}

export interface OrderListParams {
  query?: string
  status?: OrderStatus | ''
  createdFrom?: string
  createdTo?: string
  page?: number
  size?: number
}

export function listOrders(params: OrderListParams = {}): Promise<PageResponse<OrderSummaryView>> {
  const search = new URLSearchParams()
  if (params.query?.trim()) search.set('query', params.query.trim())
  if (params.status) search.set('status', params.status)
  if (params.createdFrom) search.set('createdFrom', params.createdFrom)
  if (params.createdTo) search.set('createdTo', params.createdTo)
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
  return api.get<PageResponse<OrderSummaryView>>(`/api/orders?${search}`)
}

export const getOrder = (id: string) => api.get<OrderView>(`/api/orders/${id}`)

export const getOrderTimeline = (id: string) => api.get<OrderTimelineEntryView[]>(`/api/orders/${id}/timeline`)

export interface OrderTransitionBody {
  readonly expectedRevision: number
  readonly operation: OrderOperation
  readonly reason?: string | null
  readonly appointment?: OrderAppointment | null
  /** Hanya dibaca saat transisinya `ACCEPT`; transisi lain mengabaikannya. */
  readonly promotion?: OrderPromotionBody | null
  readonly assignees?: string[]
}

export interface OrderPromotionBody {
  readonly planId?: string | null
  readonly code?: string | null
  readonly areaId?: string | null
  readonly idCardNumber?: string | null
  readonly monthlyFeeOverride?: string | null
}

export const transitionOrder = (id: string, transition: OrderTransition, body: OrderTransitionBody) =>
  api.post<OrderView>(`/api/orders/${id}/${transition}`, body)

/** `flag: null` MELEPAS penanda. [reason] ikut dibaca PELANGGAN di halaman lacak. */
export const flagOrder = (id: string, body: { flag: OrderPortalFlag | null; reason: string | null; operation: OrderOperation }) =>
  api.post<OrderView>(`/api/orders/${id}/attention`, body)

/** [note] adalah catatan INTERNAL; kalimat untuk pelanggan disusun server, bukan operator. */
export const markOrderUnreachable = (id: string, body: { note: string | null; operation: OrderOperation }) =>
  api.post<OrderView>(`/api/orders/${id}/unreachable`, body)

/** Penanda portal yang sedang terpasang, hasil rekonstruksi dari riwayat. Lihat [derivePortalFlag]. */
export interface DerivedPortalFlag {
  readonly flag: OrderPortalFlag
  readonly reason: string | null
  readonly occurredAt: string
  readonly actorId: string | null
}

const TERMINAL_STATUSES: readonly string[] = ['FULFILLED', 'CANCELLED', 'REJECTED']

/**
 * Menyimpulkan penanda portal yang sedang terpasang DARI RIWAYAT pesanan.
 *
 * Ini tambalan, bukan desain: `OrderSummaryView` MAUPUN `OrderView` tidak memulangkan
 * `portalFlag`/`portalFlagReason`/`portalFlagSource` sama sekali, padahal penanda bisa dipasang
 * OTOMATIS (kunjungan gagal karena pelanggan, saga fulfillment macet). Tanpa rekonstruksi ini
 * operator tak punya satu pun permukaan yang memberitahunya bahwa pesanan sedang bertanda —
 * pelanggan membaca "kami belum berhasil menghubungi Anda" di halaman lacak sementara antrean
 * operator menampilkannya sebagai pesanan diterima yang biasa saja.
 *
 * ATURAN yang ditiru dari agregat: transisi ke status akhir (FULFILLED/CANCELLED/REJECTED)
 * MELEPAS penanda tanpa menulis `ORDER_UNFLAGGED`, jadi kejadian itu harus ikut me-reset.
 *
 * Yang TIDAK bisa disimpulkan di sini: SIAPA yang memasang (`portalFlagSource`). `actorId` bukan
 * penandanya — jalur otomatis pun mengoper pelaku manusia yang memicunya (mis. teknisi yang
 * melaporkan kunjungan gagal).
 */
export function derivePortalFlag(timeline: readonly OrderTimelineEntryView[]): DerivedPortalFlag | null {
  let current: DerivedPortalFlag | null = null
  // Riwayat datang urut-naik menurut revisi; entri terbaru menang.
  for (const entry of timeline) {
    if (entry.eventType === 'ORDER_FLAGGED') {
      current = {
        flag: entry.toStatus as OrderPortalFlag,
        reason: entry.reason,
        occurredAt: entry.occurredAt,
        actorId: entry.actorId,
      }
    } else if (entry.eventType === 'ORDER_UNFLAGGED') {
      current = null
    } else if (TERMINAL_STATUSES.includes(entry.toStatus)) {
      current = null
    }
  }
  return current
}

// ---------------------------------------------------------------------------------------
// Calon pelanggan (lead)
// ---------------------------------------------------------------------------------------

export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUALIFIED' | 'CONVERTED' | 'DROPPED'
export type LeadSource = 'PUBLIC_WEB' | 'CSV_IMPORT' | 'OPERATOR' | 'REFERRAL' | 'OTHER'

export const LEAD_STATUSES: readonly LeadStatus[] = ['NEW', 'CONTACTED', 'QUALIFIED', 'CONVERTED', 'DROPPED']
export const LEAD_SOURCES: readonly LeadSource[] = ['PUBLIC_WEB', 'CSV_IMPORT', 'OPERATOR', 'REFERRAL', 'OTHER']

export const LEAD_STATUS_LABEL: Record<LeadStatus, string> = {
  NEW: 'Baru',
  CONTACTED: 'Sudah dihubungi',
  QUALIFIED: 'Layak',
  CONVERTED: 'Jadi pelanggan',
  DROPPED: 'Batal',
}

export const LEAD_SOURCE_LABEL: Record<LeadSource, string> = {
  PUBLIC_WEB: 'Web publik',
  CSV_IMPORT: 'Impor CSV',
  OPERATOR: 'Operator',
  REFERRAL: 'Referensi',
  OTHER: 'Lainnya',
}

/**
 * Status tujuan yang sah — cermin `OrderLead.changeStatus`. CONVERTED sengaja kosong di semua
 * baris: konversi hanya boleh lewat promosi, karena ia melahirkan pelanggan sungguhan.
 */
export const LEAD_STATUS_TARGETS: Record<LeadStatus, readonly LeadStatus[]> = {
  NEW: ['CONTACTED', 'QUALIFIED', 'DROPPED'],
  CONTACTED: ['QUALIFIED', 'DROPPED'],
  QUALIFIED: ['CONTACTED', 'DROPPED'],
  DROPPED: ['CONTACTED'],
  CONVERTED: [],
}

export interface OrderLeadView {
  readonly id: string
  readonly name: string
  readonly phone: string
  readonly email: string | null
  readonly address: string | null
  readonly latitude: number | null
  readonly longitude: number | null
  readonly interestedPlanId: string | null
  readonly source: LeadSource
  readonly status: LeadStatus
  readonly convertedCustomerId: string | null
  readonly notes: string | null
  readonly createdAt: string
  readonly updatedAt: string
}

export interface PromotedLeadView {
  readonly leadId: string
  readonly customerId: string
  readonly subscriptionId: string | null
  readonly alreadyConverted: boolean
}

export interface OrderLeadListParams {
  query?: string
  status?: LeadStatus | ''
  source?: LeadSource | ''
  page?: number
  size?: number
}

export function listOrderLeads(params: OrderLeadListParams = {}): Promise<PageResponse<OrderLeadView>> {
  const search = new URLSearchParams()
  if (params.query?.trim()) search.set('query', params.query.trim())
  if (params.status) search.set('status', params.status)
  if (params.source) search.set('source', params.source)
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
  return api.get<PageResponse<OrderLeadView>>(`/api/orders/leads?${search}`)
}

export interface CreateOrderLeadBody {
  readonly name: string
  readonly phone: string
  readonly email?: string | null
  readonly address?: string | null
  readonly interestedPlanId?: string | null
  readonly source?: LeadSource
  readonly notes?: string | null
}

export const getOrderLead = (id: string) => api.get<OrderLeadView>(`/api/orders/leads/${id}`)
export const createOrderLead = (body: CreateOrderLeadBody) => api.post<OrderLeadView>('/api/orders/leads', body)
export const changeOrderLeadStatus = (id: string, status: LeadStatus) =>
  api.post<OrderLeadView>(`/api/orders/leads/${id}/status`, { status })
export const promoteOrderLead = (id: string, body: OrderPromotionBody = {}) =>
  api.post<PromotedLeadView>(`/api/orders/leads/${id}/promote`, body)

// ---------------------------------------------------------------------------------------
// Impor CSV massal
// ---------------------------------------------------------------------------------------

export type OrderImportBatchStatus = 'PREVIEWED' | 'COMMITTED'
export type OrderImportRowStatus = 'ACCEPTED' | 'REJECTED' | 'DUPLICATE' | 'CREATED' | 'FAILED'

export interface OrderImportBatchSummaryView {
  readonly id: string
  readonly fileName: string
  readonly status: OrderImportBatchStatus
  readonly delimiter: string
  readonly byteSize: number
  readonly totalRows: number
  readonly acceptedRows: number
  readonly rejectedRows: number
  readonly createdRows: number
  readonly failedRows: number
  readonly importedBy: string | null
  readonly createdAt: string
  readonly committedAt: string | null
}

export interface OrderImportRowView {
  readonly id: string
  readonly lineNumber: number
  readonly status: OrderImportRowStatus
  /** Kalimat untuk MANUSIA — alasan baris ini ditolak. Wajib ditampilkan apa adanya. */
  readonly message: string | null
  readonly name: string | null
  readonly phone: string | null
  readonly email: string | null
  readonly planId: string | null
  readonly address: string | null
  readonly city: string | null
  readonly postalCode: string | null
  readonly notes: string | null
  readonly orderId: string | null
  readonly orderNumber: string | null
}

export interface OrderImportBatchDetailView {
  readonly batch: OrderImportBatchSummaryView
  readonly rows: OrderImportRowView[]
}

export const ORDER_IMPORT_ROW_STATUS_LABEL: Record<OrderImportRowStatus, string> = {
  ACCEPTED: 'Siap dibuat',
  REJECTED: 'Ditolak',
  DUPLICATE: 'Duplikat',
  CREATED: 'Dibuat',
  FAILED: 'Gagal',
}

export const ORDER_IMPORT_BATCH_STATUS_LABEL: Record<OrderImportBatchStatus, string> = {
  PREVIEWED: 'Pratinjau',
  COMMITTED: 'Sudah dijalankan',
}

/** Cermin `OrderImportLimits` di server. Ditampilkan SEBELUM unggah, bukan sesudah ditolak. */
export const ORDER_IMPORT_MAX_BYTES = 2 * 1024 * 1024
export const ORDER_IMPORT_MAX_ROWS = 1000

/** Kolom yang dikenali pengurai; bintang = wajib ada di baris pertama berkas. */
export const ORDER_IMPORT_COLUMNS: readonly { label: string; required: boolean }[] = [
  { label: 'nama', required: true },
  { label: 'hp', required: true },
  { label: 'email', required: false },
  { label: 'paket', required: true },
  { label: 'alamat', required: true },
  { label: 'kota', required: true },
  { label: 'kodepos', required: true },
  { label: 'catatan', required: false },
]

/**
 * Unggah + urai. TIDAK membuat pesanan apa pun — pesanan baru lahir setelah [commitOrderImport].
 *
 * Dikirim lewat `api.postForm` supaya boundary multipart ditulis browser sendiri; memasang
 * `Content-Type` sendiri akan menghasilkan boundary yang tak cocok dan server menolak dengan
 * "Berkas CSV kosong atau tidak terkirim" yang menyesatkan.
 */
export function previewOrderImport(file: File): Promise<OrderImportBatchDetailView> {
  const form = new FormData()
  form.append('file', file)
  return api.postForm<OrderImportBatchDetailView>('/api/orders/import/preview', form)
}

export const getOrderImportBatch = (batchId: string) =>
  api.get<OrderImportBatchDetailView>(`/api/orders/import/${batchId}`)

export const commitOrderImport = (batchId: string) =>
  api.post<OrderImportBatchDetailView>(`/api/orders/import/${batchId}/commit`)

export const listOrderImportHistory = (limit = 20) =>
  api.get<OrderImportBatchSummaryView[]>(`/api/orders/import?limit=${limit}`)

/**
 * Berkas contoh. Diambil lewat `api.blob` (bukan `<a href>`) karena endpoint-nya menuntut
 * header Bearer: tautan biasa tak membawanya dan operator hanya menerima berkas 401.
 */
export const downloadOrderImportTemplate = () => api.blob('/api/orders/import/template')
