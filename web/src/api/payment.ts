/** Setelan payment gateway tenant (module `billing`): Pivot, Tripay BYOK, atau Manual. */

import { api } from './client'

/** Penyedia pembayaran tenant: PIVOT (via akun platform), TRIPAY (akun tenant), atau MANUAL. */
export type PaymentProvider = 'PIVOT' | 'TRIPAY' | 'MANUAL'

export const PAYMENT_PROVIDER_LABEL: Record<PaymentProvider, string> = {
  PIVOT: 'Pivot',
  TRIPAY: 'Tripay (akun sendiri)',
  MANUAL: 'Manual (tunai/transfer)',
}

/**
 * Setelan seperti dibaca dari server. Kredensial Tripay bersifat write-only: GET hanya membawa
 * penanda apakah API Key dan Private Key sudah tersimpan, bukan nilainya.
 */
export interface PaymentGatewaySettingsView {
  readonly provider: PaymentProvider
  readonly enabled: boolean
  // Pembayaran manual (transfer/QRIS) — non-rahasia, ditampilkan apa adanya.
  readonly manualTransferEnabled: boolean
  readonly bankName: string | null
  readonly accountNumber: string | null
  readonly accountHolder: string | null
  readonly manualQrisEnabled: boolean
  /** Apakah gambar QRIS sudah terunggah (byte di object storage). */
  readonly qrisImageSet: boolean
  // Tripay BYOK — kredensialnya tak pernah dikembalikan server.
  readonly tripayMerchantCode: string | null
  readonly tripayApiKeySet: boolean
  readonly tripayPrivateKeySet: boolean
  readonly tripaySandbox: boolean
}

/**
 * Perubahan setelan. Tripay API Key dan Private Key write-only; `null` atau string kosong
 * mempertahankan nilai tersimpan. Gambar QRIS diunggah terpisah (multipart).
 */
export interface UpdatePaymentGatewaySettingsRequest {
  readonly provider: PaymentProvider
  readonly enabled: boolean
  readonly manualTransferEnabled: boolean
  readonly bankName: string | null
  readonly accountNumber: string | null
  readonly accountHolder: string | null
  readonly manualQrisEnabled: boolean
  readonly tripayMerchantCode: string | null
  readonly tripayApiKey: string | null
  readonly tripayPrivateKey: string | null
  readonly tripaySandbox: boolean
}

export interface TripaySandboxTestRequest {
  readonly merchantCode: string
  readonly apiKey: string | null
  readonly privateKey: string | null
}

export interface TripaySandboxTestView {
  readonly paymentUrl: string
}

export function getPaymentGatewaySettings(): Promise<PaymentGatewaySettingsView> {
  return api.get('/api/billing/gateway-settings')
}

export function updatePaymentGatewaySettings(
  body: UpdatePaymentGatewaySettingsRequest,
): Promise<PaymentGatewaySettingsView> {
  return api.put('/api/billing/gateway-settings', body)
}

export function testTripaySandboxPayment(
  body: TripaySandboxTestRequest,
): Promise<TripaySandboxTestView> {
  return api.post('/api/billing/gateway-settings/tripay/test', body)
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
