import { api } from './client'

/**
 * Tipe & panggilan module billing. Cermin `BillingController`/`InvoiceView` di server.
 * Dipakai panel Tagihan di halaman detail pelanggan (Subscriber-360): daftar tagihan
 * pelanggan + hitung tunggakan sisi klien.
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
  status: InvoiceStatus
  issuedAt: string
  dueDate: string
  paidAt: string | null
  gatewayProvider: string | null
  payUrl: string | null
}

/** Tagihan milik satu pelanggan (semua status), terbaru dari server apa adanya. */
export const listInvoicesForCustomer = (customerId: string) =>
  api.get<InvoiceView[]>(`/api/billing/invoices?customerId=${customerId}`)
