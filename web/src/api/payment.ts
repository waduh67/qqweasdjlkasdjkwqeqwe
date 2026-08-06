/** Setelan payment gateway tenant (module `billing`): Pivot (agregator platform) atau Manual. */

import { api } from './client'

/** Penyedia pembayaran tenant: PIVOT (via akun platform) atau MANUAL (transfer/QRIS). */
export type PaymentProvider = 'PIVOT' | 'MANUAL'

export const PAYMENT_PROVIDER_LABEL: Record<PaymentProvider, string> = {
  PIVOT: 'Pivot',
  MANUAL: 'Manual (tunai/transfer)',
}

/**
 * Setelan seperti dibaca dari server. Tak ada kredensial per-tenant lagi — penagihan otomatis
 * memakai akun master Pivot platform + sub-account tenant. Field manual (transfer/QRIS)
 * non-rahasia, ditampilkan apa adanya; `qrisImageSet` hanya penanda gambar sudah terunggah.
 */
export interface PaymentGatewaySettingsView {
  provider: PaymentProvider
  enabled: boolean
  // Pembayaran manual (transfer/QRIS) — non-rahasia, ditampilkan apa adanya.
  manualTransferEnabled: boolean
  bankName: string | null
  accountNumber: string | null
  accountHolder: string | null
  manualQrisEnabled: boolean
  /** Apakah gambar QRIS sudah terunggah (byte di object storage). */
  qrisImageSet: boolean
}

/**
 * Perubahan setelan. Tak ada kredensial — hanya pilih penyedia (PIVOT/MANUAL), status aktif,
 * dan konfigurasi pembayaran manual. Gambar QRIS diunggah terpisah (multipart).
 */
export interface UpdatePaymentGatewaySettingsRequest {
  provider: PaymentProvider
  enabled: boolean
  manualTransferEnabled: boolean
  bankName: string | null
  accountNumber: string | null
  accountHolder: string | null
  manualQrisEnabled: boolean
}

export function getPaymentGatewaySettings(): Promise<PaymentGatewaySettingsView> {
  return api.get('/api/billing/gateway-settings')
}

export function updatePaymentGatewaySettings(
  body: UpdatePaymentGatewaySettingsRequest,
): Promise<PaymentGatewaySettingsView> {
  return api.put('/api/billing/gateway-settings', body)
}

/** Path konten byte gambar QRIS (ter-gate) — untuk pola `AuthedImage` (api.blob + createObjectURL). */
export const QRIS_IMAGE_PATH = '/api/billing/gateway-settings/qris'

/** Unggah/ganti gambar QRIS pembayaran manual. Multipart, jadi di luar tombol simpan utama. */
export function uploadQrisImage(file: File): Promise<PaymentGatewaySettingsView> {
  const form = new FormData()
  form.append('file', file)
  return api.postForm(QRIS_IMAGE_PATH, form)
}

/** Hapus gambar QRIS pembayaran manual. */
export function deleteQrisImage(): Promise<PaymentGatewaySettingsView> {
  return api.del(QRIS_IMAGE_PATH)
}

/**
 * Instruksi bayar manual ringkas untuk ditunjukkan ke pelanggan (halaman detail pelanggan) pada
 * tagihan MANUAL. Non-rahasia; gambar QRIS diambil lewat `QRIS_IMAGE_PATH`.
 */
export interface ManualPaymentInstructionsView {
  transferEnabled: boolean
  bankName: string | null
  accountNumber: string | null
  accountHolder: string | null
  qrisEnabled: boolean
  qrisImageAvailable: boolean
}

/** Instruksi bayar manual tenant aktif (di-gate `billing.invoice.view`). */
export function getManualPaymentInstructions(): Promise<ManualPaymentInstructionsView> {
  return api.get('/api/billing/manual-payment-instructions')
}
