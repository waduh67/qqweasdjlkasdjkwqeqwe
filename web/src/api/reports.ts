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
  /** Cacah seluruh tagihan per status (ISSUED/PAID/OVERDUE/VOID). */
  statusCounts: Record<string, number>
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
