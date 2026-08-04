/**
 * Billing langganan SaaS level-platform: super-admin memilih gateway aktif + kredensialnya
 * (global, bukan per-tenant), lalu mengelola langganan tiap tenant (biaya bulanan flat, tagihan,
 * pembayaran). Semua endpoint di-gate izin `platform.billing.*` / `platform.subscription.*`.
 */

import { api, ApiError } from './client'

export type PlatformProvider = 'PAYWUZ' | 'XENDIT' | 'MIDTRANS'

export const PLATFORM_PROVIDER_LABEL: Record<PlatformProvider, string> = {
  PAYWUZ: 'Paywuz',
  XENDIT: 'Xendit',
  MIDTRANS: 'Midtrans',
}

/** Urutan tampil dropdown gateway aktif — Paywuz default. */
export const PLATFORM_PROVIDERS: PlatformProvider[] = ['PAYWUZ', 'XENDIT', 'MIDTRANS']

/** Ringkasan satu penyedia (tanpa rahasia — hanya penanda boolean sudah terisi). */
export interface PlatformGatewayView {
  provider: PlatformProvider
  enabled: boolean
  apiKeySet: boolean
  secretKeySet: boolean
  webhookTokenSet: boolean
  paymentMethod: string | null
  credentialsSet: boolean
}

/** Setelan billing global + baris tiap penyedia. */
export interface PlatformBillingSettingsView {
  activeProvider: PlatformProvider
  defaultGraceDays: number
  defaultDueDays: number
  defaultBillingDay: number
  /** Harga langganan bulanan default (sama untuk semua tenant, bisa dioverride saat onboarding). */
  defaultMonthlyFee: number
  currency: string
  gateways: PlatformGatewayView[]
}

/** Ganti gateway aktif + default global. */
export interface UpdatePlatformSettingsRequest {
  activeProvider: PlatformProvider
  defaultGraceDays: number
  defaultDueDays: number
  defaultBillingDay: number
  defaultMonthlyFee: number
  currency: string
}

/** Ubah kredensial satu penyedia. Rahasia null/kosong = biarkan yang tersimpan apa adanya. */
export interface UpdatePlatformGatewayRequest {
  enabled: boolean
  apiKey: string | null
  secretKey: string | null
  webhookToken: string | null
  paymentMethod: string | null
}

export function getPlatformBillingSettings(): Promise<PlatformBillingSettingsView> {
  return api.get('/api/platform/billing/settings')
}

export function updatePlatformSettings(
  body: UpdatePlatformSettingsRequest,
): Promise<PlatformBillingSettingsView> {
  return api.put('/api/platform/billing/settings', body)
}

export function updatePlatformGateway(
  provider: PlatformProvider,
  body: UpdatePlatformGatewayRequest,
): Promise<PlatformBillingSettingsView> {
  return api.put(`/api/platform/billing/settings/gateways/${provider}`, body)
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
