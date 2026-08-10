import { api } from './client'

/**
 * Tipe & panggilan modul `reporting`. Cermin `ReportController`/`ReportOverview` di server:
 * satu endpoint agregat yang merangkai angka keuangan (billing) dan pelanggan/langganan
 * (customer) menjadi laporan siap-baca. Semua nilai uang diserialkan sebagai string (BigDecimal).
 */

/** Angka keuangan tenant dalam rentang (lihat `BillingFinancialReport` di server). */
export interface BillingFinancialReport {
  revenueCollected: string
  paidInvoiceCount: number
  issuedAmount: string
  issuedInvoiceCount: number
  outstandingAmount: string
  outstandingInvoiceCount: number
  /** Cacah seluruh tagihan per status (ISSUED/PAID/OVERDUE/VOID/REFUNDED). */
  statusCounts: Record<string, number>
  /**
   * Uang yang BENAR-BENAR keluar lagi dalam rentang ini — dihitung menurut kapan refundnya
   * selesai, bukan kapan tagihannya lunas. `revenueCollected` tetap BRUTO (tak dikurangi).
   */
  refundedAmount: string
  refundCount: number
  /** `revenueCollected` − `refundedAmount`: pendapatan yang benar-benar tinggal di kas. */
  netRevenue: string
}

/** Potret pelanggan & langganan tenant (lihat `SubscriberStats` di server). */
export interface SubscriberStats {
  totalCustomers: number
  subscriptionsByStatus: Record<string, number>
  billableCount: number
  mrr: string
}

/** Satu titik tren pendapatan bulanan (`month` = "YYYY-MM"). */
export interface MonthlyRevenuePoint {
  month: string
  revenue: string
  paidInvoiceCount: number
  /** Refund yang selesai di bulan itu (bruto `revenue` belum dikurangi ini). */
  refunded: string
}

/** Satu ember umur piutang; `bucket` = NOT_DUE/D1_30/D31_60/D61_90/D90_PLUS. */
export interface ReceivableAgingBucket {
  bucket: string
  amount: string
  invoiceCount: number
}

/** Potret piutang per `asOf` — termasuk yang belum jatuh tempo (ember NOT_DUE). */
export interface ReceivableAging {
  asOf: string
  totalAmount: string
  totalInvoiceCount: number
  buckets: ReceivableAgingBucket[]
}

/** Perputaran langganan dalam rentang; `churnRatePercent` = berhenti ÷ basis awal × 100. */
export interface ChurnReport {
  baseCount: number
  activatedCount: number
  terminatedCount: number
  netGrowth: number
  churnRatePercent: string
}

/** Sekerat pendapatan menurut satu dimensi (paket atau wilayah), terbesar dulu. */
export interface RevenueSlice {
  label: string
  amount: string
  paidInvoiceCount: number
  subscriptionCount: number
}

/** Ringkasan bisnis satu tenant untuk sebuah rentang + tren. */
export interface ReportOverview {
  rangeStart: string
  rangeEnd: string
  finance: BillingFinancialReport
  subscribers: SubscriberStats
  /** Pendapatan rata-rata per langganan billable (MRR ÷ jumlah ACTIVE+ISOLATED). */
  arpu: string
  monthlyRevenue: MonthlyRevenuePoint[]
  /** Umur piutang: POTRET hari ini, bukan per ujung rentang. */
  aging: ReceivableAging
  churn: ChurnReport
  revenueByPackage: RevenueSlice[]
  revenueByArea: RevenueSlice[]
}

/**
 * Produktivitas satu teknisi. Satu WO yang dikerjakan berdua dihitung untuk KEDUANYA,
 * jadi jumlah `completedCount` per teknisi bisa melebihi `completedCount` tim.
 */
export interface TechnicianPerformance {
  technicianId: string
  technicianName: string
  completedCount: number
  avgResolutionHours: number | null
}

/** Kerja lapangan pada rentang; jam rata-rata `null` bila tak ada data (bukan 0). */
export interface FieldOpsSummary {
  completedCount: number
  completedByType: Record<string, number>
  avgResolutionHours: number | null
  /** MTTR gangguan — jam rata-rata WO bertipe REPAIR saja. */
  avgRepairResolutionHours: number | null
  avgResponseHours: number | null
  technicians: TechnicianPerformance[]
}

/** Meja bantuan pada rentang (lihat `HelpdeskSupportReport` di server). */
export interface HelpdeskSupportReport {
  openedCount: number
  resolvedCount: number
  openedByCategory: Record<string, number>
  avgFirstResponseHours: number | null
  avgResolutionHours: number | null
  responseBreachedCount: number
  resolutionBreachedCount: number
  slaCompliancePercent: string | null
}

/** Laporan operasional: kecepatan kerja lapangan + meja bantuan. */
export interface OperationsReport {
  rangeStart: string
  rangeEnd: string
  fieldOps: FieldOpsSummary
  support: HelpdeskSupportReport
}

/**
 * Ambil ringkasan bisnis. Tanpa `from`/`to`, server memakai default (bulan berjalan
 * s/d hari ini) — pemanggil tak perlu tahu "hari ini" tenant.
 */
export const getReportOverview = (params: { from?: string; to?: string; trailingMonths?: number } = {}) => {
  const q = new URLSearchParams()
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  if (params.trailingMonths) q.set('trailingMonths', String(params.trailingMonths))
  const qs = q.toString()
  return api.get<ReportOverview>(`/api/reports/overview${qs ? `?${qs}` : ''}`)
}

/**
 * Ambil laporan operasional. Dipisah dari overview karena sumbernya modul lain dan
 * pemakainya beda — dipanggil hanya saat tab operasional dibuka.
 */
export const getOperationsReport = (params: { from?: string; to?: string } = {}) => {
  const q = new URLSearchParams()
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  const qs = q.toString()
  return api.get<OperationsReport>(`/api/reports/operations${qs ? `?${qs}` : ''}`)
}
