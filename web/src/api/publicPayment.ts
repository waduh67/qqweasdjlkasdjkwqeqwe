/**
 * Halaman bayar publik `/bayar/<slug>/<uuid>` — TANPA login. Kapabilitasnya adalah UUID tagihan
 * di URL, itulah yang membuat tautannya bisa dikirim ke pelanggan lewat WhatsApp.
 *
 * Memakai `api` bersama seperti `api/signup.ts`: tanpa token, klien tak memasang header
 * Authorization, dan 401 tanpa refresh-token hanya jadi `ApiError` biasa — tak menendang sesi
 * operator siapa pun. Satu bentuk endpoint melayani DUA jenis tagihan (pelanggan tenant &
 * langganan SaaS); klien tak perlu tahu yang mana.
 */

import { api } from './client'

/** Instruksi bayar manual tenant (gateway MANUAL) — gambar QRIS diambil terpisah. */
export interface PublicManualInstructions {
  transferEnabled: boolean
  bankName: string | null
  accountNumber: string | null
  accountHolder: string | null
  qrisEnabled: boolean
  qrisImageAvailable: boolean
}

/**
 * Tagihan sebagaimana ditampilkan halaman bayar publik — subset paling sempit yang cukup untuk
 * membayar. Sengaja TIDAK ada `gatewayRef`/`paymentSessionId`/`simulatable`: pemegang tautan
 * belum tentu pemilik tagihan.
 *
 * `payableOnline` = tagihan masih terbuka DAN gateway aktif mendukung bayar in-app; bila false
 * karena gateway tenant MANUAL, `manual` berisi instruksi transfernya.
 */
export interface PublicInvoiceView {
  id: string
  number: string
  tenantSlug: string
  tenantName: string
  /** Yang ditagih: nama pelanggan (tagihan tenant) atau nama tenant (langganan SaaS). */
  payerName: string
  periodStart: string
  periodEnd: string
  amount: number
  status: string
  dueDate: string
  paidAt: string | null
  payableOnline: boolean
  payMethod: string | null
  vaChannel: string | null
  vaNumber: string | null
  vaName: string | null
  vaExpiresAt: string | null
  /** String QRIS mentah (dirender jadi kode QR di klien). */
  qrContent: string | null
  qrExpiresAt: string | null
  manual: PublicManualInstructions | null
}

/** Satu metode bayar in-app; `channels` kosong bila tak perlu pilih bank (QRIS). */
export interface PublicPaymentMethodOption {
  type: string
  label: string
  channels: { code: string; label: string }[]
}

const base = (tenantSlug: string, invoiceId: string) =>
  `/api/public/invoices/${encodeURIComponent(tenantSlug)}/${encodeURIComponent(invoiceId)}`

export function getPublicInvoice(tenantSlug: string, invoiceId: string): Promise<PublicInvoiceView> {
  return api.get(base(tenantSlug, invoiceId))
}

export function getPublicPaymentMethods(
  tenantSlug: string,
  invoiceId: string,
): Promise<PublicPaymentMethodOption[]> {
  return api.get(`${base(tenantSlug, invoiceId)}/methods`)
}

/** Pilih instrumen bayar; instruksi hidup yang cocok dipakai ulang server-side (bukan sesi baru). */
export function payPublicInvoice(
  tenantSlug: string,
  invoiceId: string,
  method: string,
  channel: string | null,
): Promise<PublicInvoiceView> {
  return api.post(`${base(tenantSlug, invoiceId)}/pay`, { method, channel })
}

/** URL gambar QRIS statis tenant — endpoint publik, jadi bisa dipasang langsung di `<img src>`. */
export function publicQrisImageUrl(tenantSlug: string, invoiceId: string): string {
  return `${base(tenantSlug, invoiceId)}/qris`
}

/**
 * Tautan bayar ABSOLUT untuk dibagikan (disalin operator / disisipkan ke pengingat WhatsApp).
 * Origin diambil dari halaman yang sedang dibuka — sama dengan yang dipakai pelanggan.
 */
export function payLink(tenantSlug: string, invoiceId: string): string {
  return `${window.location.origin}/bayar/${encodeURIComponent(tenantSlug)}/${encodeURIComponent(invoiceId)}`
}
