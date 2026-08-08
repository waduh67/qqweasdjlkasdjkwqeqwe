/**
 * Billing langganan SaaS level-platform: super-admin mengatur default global (grace/jatuh-tempo/
 * tanggal-tagih/harga) + konfigurasi master Pivot (satu akun agregator untuk seluruh platform),
 * lalu mengelola langganan tiap tenant (biaya bulanan flat, tagihan, pembayaran). Semua endpoint
 * di-gate izin `platform.billing.*` / `platform.subscription.*`.
 */

import { api, ApiError } from './client'

/** Setelan billing global (tanpa gateway per-penyedia — penagihan platform via Pivot master). */
export interface PlatformBillingSettingsView {
  defaultGraceDays: number
  defaultDueDays: number
  defaultBillingDay: number
  /** Harga langganan bulanan default (sama untuk semua tenant, bisa dioverride saat onboarding). */
  defaultMonthlyFee: number
  currency: string
}

/** Ganti default global billing platform. */
export interface UpdatePlatformSettingsRequest {
  defaultGraceDays: number
  defaultDueDays: number
  defaultBillingDay: number
  defaultMonthlyFee: number
  currency: string
}

export function getPlatformBillingSettings(): Promise<PlatformBillingSettingsView> {
  return api.get('/api/platform/billing/settings')
}

export function updatePlatformSettings(
  body: UpdatePlatformSettingsRequest,
): Promise<PlatformBillingSettingsView> {
  return api.put('/api/platform/billing/settings', body)
}

// ---- Konfigurasi master Pivot (agregator platform) ----

/** Cara menghitung fee platform per transaksi: nominal tetap (Rp) atau persentase. */
export type PlatformFeeType = 'FIXED' | 'PERCENTAGE'

export const PLATFORM_FEE_TYPE_LABEL: Record<PlatformFeeType, string> = {
  FIXED: 'Nominal tetap (Rp)',
  PERCENTAGE: 'Persentase (%)',
}

/**
 * Konfigurasi akun master Pivot platform. Kredensial write-only: server hanya menandai sudah terisi
 * (`*Set`), tak pernah menariknya kembali (labelnya di dashboard Pivot: Client ID / Client Secret /
 * Callback Secret). `platformFeeMinor` = nilai fee (Rp untuk FIXED, angka persen untuk PERCENTAGE).
 * Field `default*` = default sub-account (non-rahasia, ditampilkan apa adanya).
 */
export interface PivotMasterConfigView {
  enabled: boolean
  sandbox: boolean
  merchantIdSet: boolean
  merchantSecretSet: boolean
  callbackApiKeySet: boolean
  credentialsSet: boolean
  platformFeeMinor: number
  platformFeeType: PlatformFeeType
  payoutChannelCode: string | null
  payoutAccountNumber: string | null
  // Default field wajib create sub-account (non-rahasia).
  defaultBusinessType: string | null
  defaultBusinessStructure: string | null
  defaultParentIndustry: string | null
  defaultChildIndustry: string | null
  defaultMcc: string | null
  defaultDigitalStatus: string | null
  defaultBusinessCountry: string | null
  defaultCountryOfEntity: string | null
  defaultLogoUrl: string | null
  defaultWebsite: string | null
  defaultDistrictId: number | null
  defaultPostCode: string | null
}

/** Ubah konfigurasi master Pivot. Kredensial null/kosong = pertahankan yang tersimpan apa adanya. */
export interface PivotMasterConfigRequest {
  enabled: boolean
  sandbox: boolean
  merchantId: string | null
  merchantSecret: string | null
  callbackApiKey: string | null
  platformFeeMinor: number
  platformFeeType: PlatformFeeType
  payoutChannelCode: string | null
  payoutAccountNumber: string | null
  // Default field wajib create sub-account (non-rahasia).
  defaultBusinessType: string | null
  defaultBusinessStructure: string | null
  defaultParentIndustry: string | null
  defaultChildIndustry: string | null
  defaultMcc: string | null
  defaultDigitalStatus: string | null
  defaultBusinessCountry: string | null
  defaultCountryOfEntity: string | null
  defaultLogoUrl: string | null
  defaultWebsite: string | null
  defaultDistrictId: number | null
  defaultPostCode: string | null
}

export function getPivotMasterConfig(): Promise<PivotMasterConfigView> {
  return api.get('/api/platform/pivot-config')
}

export function updatePivotMasterConfig(
  body: PivotMasterConfigRequest,
): Promise<PivotMasterConfigView> {
  return api.put('/api/platform/pivot-config', body)
}

// ---- Langganan per tenant ----

export type SubscriptionStatus = 'ACTIVE' | 'PAST_DUE' | 'SUSPENDED' | 'CANCELLED'
export type SubscriptionInvoiceStatus = 'ISSUED' | 'PAID' | 'OVERDUE' | 'VOID'

export const SUBSCRIPTION_STATUS_LABEL: Record<SubscriptionStatus, string> = {
  ACTIVE: 'Aktif',
  PAST_DUE: 'Menunggak',
  SUSPENDED: 'Ditangguhkan',
  CANCELLED: 'Dibatalkan',
}

export const INVOICE_STATUS_LABEL: Record<SubscriptionInvoiceStatus, string> = {
  ISSUED: 'Terbit',
  PAID: 'Lunas',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
}

export interface SubscriptionInvoiceView {
  id: string
  tenantId: string
  number: string
  periodStart: string
  periodEnd: string
  amount: number
  status: SubscriptionInvoiceStatus
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
  /**
   * Tagihan ini bisa dipaksa lunas/kedaluwarsa lewat simulasi sandbox gateway (alat uji): Pivot
   * mode sandbox, sesi bayar sudah dibuat, tagihan masih tertunggak. Di produksi selalu false.
   */
  simulatable: boolean
}

/** Satu metode bayar in-app; [channels] kosong bila tak perlu pilih bank (QRIS). */
export interface PaymentMethodOption {
  type: string
  label: string
  channels: { code: string; label: string }[]
}

export interface TenantSubscriptionDetailView {
  tenantId: string
  monthlyFee: number
  status: SubscriptionStatus
  billingDay: number | null
  graceDays: number | null
  currentPeriodStart: string | null
  currentPeriodEnd: string | null
  nextInvoiceAt: string | null
  activatedAt: string | null
  invoices: SubscriptionInvoiceView[]
}

export interface ConfigureSubscriptionRequest {
  monthlyFee: number
  billingDay: number | null
  graceDays: number | null
}

export interface ManualPaymentRequest {
  amount: number | null
  note: string | null
}

/** Ringkasan langganan tenant; null bila belum berlangganan (server balas 404). */
export async function getTenantSubscription(
  tenantId: string,
): Promise<TenantSubscriptionDetailView | null> {
  try {
    return await api.get<TenantSubscriptionDetailView>(`/api/platform/tenants/${tenantId}/subscription`)
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null
    throw err
  }
}

export function configureTenantSubscription(
  tenantId: string,
  body: ConfigureSubscriptionRequest,
): Promise<TenantSubscriptionDetailView> {
  return api.put(`/api/platform/tenants/${tenantId}/subscription`, body)
}

export function generateSubscriptionInvoice(tenantId: string): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/platform/tenants/${tenantId}/subscription/invoices`)
}

export function voidSubscriptionInvoice(
  tenantId: string,
  invoiceId: string,
): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/platform/tenants/${tenantId}/subscription/invoices/${invoiceId}/void`)
}

export function paySubscriptionInvoice(
  tenantId: string,
  invoiceId: string,
  body: ManualPaymentRequest,
): Promise<SubscriptionInvoiceView> {
  return api.post(`/api/platform/tenants/${tenantId}/subscription/invoices/${invoiceId}/pay`, body)
}

export function cancelTenantSubscription(tenantId: string): Promise<TenantSubscriptionDetailView> {
  return api.post(`/api/platform/tenants/${tenantId}/subscription/cancel`)
}

// ── Simulasi pembayaran (alat uji sandbox) ────────────────────────────────────────────────────

/** Status akhir yang dipaksakan ke sesi bayar saat simulasi sandbox. */
export type SimulatedChargeStatus = 'SUCCESS' | 'EXPIRED'

/**
 * Ketersediaan simulasi. `available` = boleh dijalankan (Pivot master aktif DAN mode sandbox);
 * `reason` menjelaskan penyebab bila tidak.
 */
export interface SimulationAvailability {
  available: boolean
  configured: boolean
  sandbox: boolean
  reason: string | null
}

export interface SimulatePaymentResult {
  paymentSessionId: string
  status: SimulatedChargeStatus
  provider: string
}

export function getSimulationAvailability(): Promise<SimulationAvailability> {
  return api.get('/api/platform/payments/simulate')
}

/**
 * Kirim simulasi untuk sebuah payment session ID Pivot (`data.id` saat create payment — nilai yang
 * sama tersimpan sebagai `gatewayRef` di tagihan). `subMerchantId` hanya untuk sesi yang dibuat
 * atas nama sub-account tenant (tagihan pelanggan); kosongkan untuk sesi langganan SaaS.
 */
export function simulatePayment(body: {
  paymentSessionId: string
  status: SimulatedChargeStatus
  subMerchantId?: string | null
}): Promise<SimulatePaymentResult> {
  return api.post('/api/platform/payments/simulate', body)
}
