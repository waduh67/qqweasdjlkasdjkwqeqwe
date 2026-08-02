/** Setelan payment gateway tenant (module `billing`): penyedia + mode + kredensial BYO. */

import { api } from './client'

export type PaymentProvider = 'XENDIT' | 'PAYWUZ' | 'PIVOT' | 'MANUAL'
export type GatewayMode = 'BYO' | 'PLATFORM'

export const PAYMENT_PROVIDER_LABEL: Record<PaymentProvider, string> = {
  XENDIT: 'Xendit',
  PAYWUZ: 'Paywuz (belum didukung)',
  PIVOT: 'Pivot (belum didukung)',
  MANUAL: 'Manual (tunai/transfer)',
}

export const GATEWAY_MODE_LABEL: Record<GatewayMode, string> = {
  BYO: 'Akun sendiri (BYO)',
  PLATFORM: 'Akun platform (agregator)',
}

/** Penyedia yang charge otomatisnya sudah jalan (sisanya kerangka/manual). */
export const SUPPORTED_PROVIDERS: PaymentProvider[] = ['XENDIT', 'MANUAL']

/**
 * Setelan seperti dibaca dari server. Kredensial TAK pernah dikembalikan — hanya penanda
 * sudah terisi (`*Set`) agar rahasia tak bocor ke UI. `subAccountId` aman ditampilkan.
 */
export interface PaymentGatewaySettingsView {
  provider: PaymentProvider
  mode: GatewayMode
  enabled: boolean
  apiKeySet: boolean
  secretKeySet: boolean
  webhookTokenSet: boolean
  subAccountId: string | null
}

/**
 * Perubahan setelan. Kredensial (`apiKey`/`secretKey`/`webhookToken`) null/kosong = biarkan
 * yang tersimpan apa adanya, jadi menyunting field lain tak menghapus rahasia. `subAccountId`
 * tak dikirim — ia hasil provisioning platform-admin.
 */
export interface UpdatePaymentGatewaySettingsRequest {
  provider: PaymentProvider
  mode: GatewayMode
  enabled: boolean
  apiKey: string | null
  secretKey: string | null
  webhookToken: string | null
}

export function getPaymentGatewaySettings(): Promise<PaymentGatewaySettingsView> {
  return api.get('/api/billing/gateway-settings')
}

export function updatePaymentGatewaySettings(
  body: UpdatePaymentGatewaySettingsRequest,
): Promise<PaymentGatewaySettingsView> {
  return api.put('/api/billing/gateway-settings', body)
}

/** Permintaan provisioning sub-account Xendit (aksi platform-admin, mode PLATFORM). */
export interface ProvisionXenditSubAccountRequest {
  email: string
  businessName: string | null
}

/** Hasil provisioning: id sub-account + apakah token callback-nya ikut tersimpan. */
export interface SubAccountProvisionResult {
  tenantId: string
  subAccountId: string
  callbackTokenSet: boolean
}

/**
 * Provisikan sub-account Xendit (mode PLATFORM/xenPlatform) untuk sebuah tenant dan kunci baris
 * gateway-nya ke XENDIT/PLATFORM/aktif. Butuh izin platform `billing.gateway.provision`.
 */
export function provisionXenditSubAccount(
  tenantId: string,
  body: ProvisionXenditSubAccountRequest,
): Promise<SubAccountProvisionResult> {
  return api.post(`/api/billing/platform/gateway/${tenantId}/xendit-subaccount`, body)
}
