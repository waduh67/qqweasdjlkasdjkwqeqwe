/**
 * Langganan aplikasi sisi TENANT (self-service): tenant admin melihat masa aktif/tagihan
 * langganannya sendiri dan memperpanjang mandiri lewat gateway aktif. Selalu untuk tenant
 * yang sedang login — tak ada parameter tenantId. Di-gate izin `billing.subscription.*`.
 */

import { api } from './client'
import type { PaymentMethodOption, SubscriptionInvoiceView, SubscriptionStatus } from './platformBilling'

export type { PaymentMethodOption, SubscriptionInvoiceView, SubscriptionStatus } from './platformBilling'

/** Satu baris pemakaian kosmetik — `limit` null artinya "Unlimited". */
export interface UsageMetricView {
  key: string
  label: string
  used: number
  limit: number | null
}

/** Pandangan langganan sisi tenant. */
export interface TenantSelfSubscriptionView {
  status: SubscriptionStatus
  monthlyFee: number
  /** Masa aktif (tanggal); null bila belum pernah aktif (belum ada pembayaran). */
  activeUntil: string | null
  currentPeriodStart: string | null
  nextInvoiceAt: string | null
  usage: UsageMetricView[]
  invoices: SubscriptionInvoiceView[]
}

/** Langganan tenant yang login; null bila belum berlangganan (server balas 204 → undefined). */
export async function getMySubscription(): Promise<TenantSelfSubscriptionView | null> {
  const res = await api.get<TenantSelfSubscriptionView | undefined>('/api/subscription')
  return res ?? null
}

/**
 * Terbitkan/ambil tagihan untuk dibayar; kembalikan tagihan berisi tautan bayar.
 * `months` = jumlah bulan dibayar di muka (1..12) — nilai tagihan `biaya × months`.
 */
export function renewMySubscription(months = 1): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/subscription/renew?months=${months}`)
}

/** Metode bayar in-app yang tersedia (QRIS + Virtual Account) untuk langganan. */
export function getSubscriptionPaymentMethods(): Promise<PaymentMethodOption[]> {
  return api.get('/api/subscription/payment-methods')
}

/**
 * Buat charge in-app (VA/QRIS) untuk satu tagihan tertunggak lalu kembalikan tagihan berisi
 * instruksi bayar (nomor VA / string QRIS). `channel` wajib untuk Virtual Account (kode bank).
 * Dipakai panel Bayar per-tagihan di Riwayat tagihan.
 */
export function payMyInvoice(
  invoiceId: string,
  method: string,
  channel: string | null,
): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/subscription/invoices/${invoiceId}/pay`, { method, channel })
}
