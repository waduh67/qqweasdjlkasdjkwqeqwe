import { api } from './client'

/**
 * Tipe & panggilan module billing. Cermin `BillingController`/`InvoiceView` di server.
 * Dipakai panel Tagihan di halaman detail pelanggan (Subscriber-360) maupun halaman
 * Tagihan lintas-pelanggan (daftar semua tagihan + penerbitan/pembatalan/bayar manual).
 */

/** ISSUED (terbit, belum bayar), PAID (lunas), OVERDUE (lewat jatuh tempo), VOID (dibatalkan). */
export type InvoiceStatus = 'ISSUED' | 'PAID' | 'OVERDUE' | 'VOID'

/** Proyeksi satu tagihan. `amount` diserialkan sebagai string oleh Jackson (BigDecimal). */
export interface InvoiceView {
  id: string
  number: string
  customerId: string
  subscriptionId: string
  periodStart: string
  periodEnd: string
  amount: string
  /** Tagihan diprorata (aktivasi tengah periode); `proratedDays` = jumlah hari yang ditagih. */
  prorated: boolean
  proratedDays: number | null
  status: InvoiceStatus
  issuedAt: string
  dueDate: string
  paidAt: string | null
  gatewayProvider: string | null
  payUrl: string | null
}

/** Proyeksi satu pembayaran (manual/gateway) atas sebuah tagihan. */
export interface PaymentView {
  id: string
  invoiceId: string
  customerId: string
  amount: string
  provider: string
  gatewayRef: string | null
  paidAt: string
  note: string | null
}

/** Hasil penerbitan massal: berapa tagihan yang dibuat untuk periode berjalan. */
export interface GenerateResult {
  created: number
}

/** Tagihan milik satu pelanggan (semua status), terbaru dari server apa adanya. */
export const listInvoicesForCustomer = (customerId: string) =>
  api.get<InvoiceView[]>(`/api/billing/invoices?customerId=${customerId}`)

/** Semua tagihan tenant (boleh disaring per status), untuk halaman Tagihan. */
export const listInvoices = (status?: InvoiceStatus) =>
  api.get<InvoiceView[]>(`/api/billing/invoices${status ? `?status=${status}` : ''}`)

/** Terbitkan tagihan periode berjalan untuk semua langganan yang layak tagih. */
export const generateInvoices = () =>
  api.post<GenerateResult>('/api/billing/invoices/generate')

/** Batalkan sebuah tagihan (ditolak server bila sudah lunas). */
export const voidInvoice = (id: string) =>
  api.post<InvoiceView>(`/api/billing/invoices/${id}/void`)

/** Catat pembayaran manual (mis. transfer/QRIS) → tagihan jadi lunas. */
export const recordManualPayment = (id: string, note?: string) =>
  api.post<InvoiceView>(`/api/billing/invoices/${id}/pay`, note ? { note } : undefined)

/** Pembayaran yang tercatat atas sebuah tagihan. */
export const listPayments = (invoiceId: string) =>
  api.get<PaymentView[]>(`/api/billing/payments?invoiceId=${invoiceId}`)
