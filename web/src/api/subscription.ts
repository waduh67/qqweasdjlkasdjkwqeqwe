/**
 * Langganan aplikasi sisi TENANT (self-service): tenant admin melihat masa aktif/tagihan
 * langganannya sendiri dan memperpanjang mandiri lewat gateway aktif. Selalu untuk tenant
 * yang sedang login — tak ada parameter tenantId. Di-gate izin `billing.subscription.*`.
 */

import { api } from './client'
import type {
  SimulatedChargeStatus,
  SubscriptionInvoiceView,
  SubscriptionStatus,
} from './platformBilling'

export type {
  SimulatedChargeStatus,
  SubscriptionInvoiceView,
  SubscriptionStatus,
} from './platformBilling'

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

/**
 * Keadaan kunci baca-saja. Sengaja terpisah dari `TenantSelfSubscriptionView` dan tanpa gate
 * izin `billing.*`: teknisi & CS yang tak punya izin langganan pun perlu tahu kenapa tombol
 * simpannya mendadak mati.
 *
 * `daysOverdue` = umur tunggakan TERTUA (0 bila belum lewat jatuh tempo), `invoiceId` =
 * tagihan yang harus dilunasi lebih dulu.
 */
export interface SubscriptionLockView {
  locked: boolean
  daysOverdue: number
  dueDate: string | null
  amountDue: number
  currency: string
  invoiceId: string | null
}

/** Status kunci tenant yang sedang login. Aman dipanggil peran mana pun asal terautentikasi. */
export function getSubscriptionLock(): Promise<SubscriptionLockView> {
  return api.get('/api/subscription/lock')
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
 * Alat uji sandbox: paksa sesi bayar tagihan langganan jadi `SUCCESS`/`EXPIRED` lewat simulasi
 * Pivot. Pelunasan (dan perpanjangan masa aktif) menyusul lewat webhook, jadi tagihan yang
 * dikembalikan MASIH berstatus lama — pemanggil memuat ulang beberapa saat kemudian.
 */
export function simulateMyInvoicePayment(
  invoiceId: string,
  status: SimulatedChargeStatus,
): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/subscription/invoices/${invoiceId}/simulate`, { status })
}
