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
  /** Total tagihan (sudah termasuk `taxAmount` PPN). */
  amount: string
  /** Dasar sebelum PPN (DPP) = `amount` − `taxAmount`. */
  baseAmount: string
  /** Komponen PPN dalam `amount`; "0" bila tagihan tanpa PPN. */
  taxAmount: string
  /** Tarif PPN yang diterapkan (pecahan, mis. "0.1100"); null bila tanpa PPN. */
  taxRate: string | null
  /** Tagihan diprorata (aktivasi tengah periode); `proratedDays` = jumlah hari yang ditagih. */
  prorated: boolean
  proratedDays: number | null
  status: InvoiceStatus
  issuedAt: string
  dueDate: string
  paidAt: string | null
  gatewayProvider: string | null
  payUrl: string | null
  // Instruksi bayar in-app (mode API Pivot); null bila belum pilih metode.
  payMethod: string | null
  vaChannel: string | null
  vaNumber: string | null
  vaName: string | null
  vaExpiresAt: string | null
  /** String QRIS mentah (dirender jadi kode QR di klien). */
  qrContent: string | null
  qrUrl: string | null
  qrExpiresAt: string | null
}

/** Satu metode bayar in-app; [channels] kosong bila tak perlu pilih bank (QRIS). */
export interface PaymentMethodOption {
  type: string
  label: string
  channels: { code: string; label: string }[]
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

/** Detail satu tagihan (untuk polling status setelah charge in-app dibuat). */
export const getInvoice = (id: string) => api.get<InvoiceView>(`/api/billing/invoices/${id}`)

/** Metode bayar in-app yang tersedia (QRIS + Virtual Account). */
export const getBillingPaymentMethods = () =>
  api.get<PaymentMethodOption[]>('/api/billing/payment-methods')

/**
 * Buat charge in-app (VA/QRIS) untuk tagihan lewat penyedia gateway yang AKTIF sekarang, lalu
 * kembalikan view terbaru berisi instruksi bayar (nomor VA / string QRIS). `channel` wajib untuk
 * Virtual Account (kode bank). Ditolak (Conflict) bila penyedia aktif MANUAL.
 */
export const refreshPaymentLink = (id: string, method: string, channel: string | null) =>
  api.post<InvoiceView>(`/api/billing/invoices/${id}/recharge`, { method, channel })

/** Pembayaran yang tercatat atas sebuah tagihan. */
export const listPayments = (invoiceId: string) =>
  api.get<PaymentView[]>(`/api/billing/payments?invoiceId=${invoiceId}`)

/**
 * Setelan pajak tenant. PPN adalah komponen yang DITAGIHKAN ke pelanggan (menambah total
 * tagihan). BHP/USO adalah kewajiban LAPORAN tenant (bukan ditagih ke pelanggan), dihitung
 * server dari peredaran bruto. Semua tarif adalah PECAHAN sebagai string (mis. "0.1100" = 11%).
 */
export interface TaxSettingsView {
  ppnEnabled: boolean
  ppnRate: string
  regulatoryEnabled: boolean
  bhpRate: string
  usoRate: string
}

/** Perubahan setelan pajak; tarif dikirim sebagai pecahan number di [0,1). */
export interface UpdateTaxSettingsRequest {
  ppnEnabled: boolean
  ppnRate: number
  regulatoryEnabled: boolean
  bhpRate: number
  usoRate: number
}

/**
 * Ringkasan pajak satu rentang (dihitung dari tagihan LUNAS). `ppnCollected` = Σ PPN tertagih
 * (pass-through ke negara); `regulatoryRevenueBase` = peredaran bruto sebelum PPN (dasar BHP/USO);
 * `regulatoryObligation` = BHP + USO — "0" bila pelaporan BHP/USO nonaktif. Semua nilai uang string.
 */
export interface TaxObligationView {
  from: string
  to: string
  ppnEnabled: boolean
  ppnRate: string
  ppnCollected: string
  regulatoryEnabled: boolean
  bhpRate: string
  usoRate: string
  regulatoryRevenueBase: string
  bhpAmount: string
  usoAmount: string
  regulatoryObligation: string
}

/** Baca setelan pajak tenant aktif. */
export const getTaxSettings = () => api.get<TaxSettingsView>('/api/billing/tax-settings')

/** Ubah setelan pajak tenant. */
export const updateTaxSettings = (body: UpdateTaxSettingsRequest) =>
  api.put<TaxSettingsView>('/api/billing/tax-settings', body)

/**
 * PPN terkumpul + kewajiban BHP/USO. Tanpa argumen, server memakai tahun kalender berjalan
 * (1 Jan s/d hari ini). `from`/`to` dalam format "YYYY-MM-DD".
 */
export const getTaxObligation = (from?: string, to?: string) => {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return api.get<TaxObligationView>(`/api/billing/tax-obligation${qs ? `?${qs}` : ''}`)
}
