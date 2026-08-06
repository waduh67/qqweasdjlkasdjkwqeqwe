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
