/**
 * Langganan aplikasi sisi TENANT (self-service): tenant admin melihat masa aktif/tagihan
 * langganannya sendiri dan memperpanjang mandiri lewat gateway aktif. Selalu untuk tenant
 * yang sedang login — tak ada parameter tenantId. Di-gate izin `billing.subscription.*`.
 */

import { api } from './client'
import type { SubscriptionInvoiceView, SubscriptionStatus } from './platformBilling'

export type { SubscriptionInvoiceView, SubscriptionStatus } from './platformBilling'

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

/**
 * Siapkan tautan bayar untuk satu tagihan tertunggak (server charge ulang ke gateway aktif bila
 * tautannya belum sempat terbit), lalu kembalikan tagihannya berisi `payUrl`. Dipakai tombol
 * "Bayar" per-tagihan di Riwayat tagihan.
 */
export function payMyInvoice(invoiceId: string): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/subscription/invoices/${invoiceId}/pay`)
}
