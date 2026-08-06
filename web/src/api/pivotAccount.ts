/**
 * Sub-account Pivot tenant (module `billing`): tiap tenant punya sub-account di akun master Pivot
 * platform untuk menampung dana pembayaran pelanggannya lalu payout ke rekeningnya sendiri. Semua
 * endpoint di-gate izin `billing.gateway.manage`.
 */

import { api } from './client'

/** Jenis sub-account: NON_KYC (limit terbatas) atau KYC (terverifikasi, limit penuh). */
export type PivotAccountType = 'NON_KYC' | 'KYC'

/** Status siklus hidup sub-account di Pivot. */
export type PivotAccountStatus =
  | 'NOT_PROVISIONED'
  | 'CREATED'
  | 'ACTIVE'
  | 'DEACTIVATED'
  | 'REJECTED'

/** Status verifikasi KYC sub-account. */
export type PivotKycStatus =
  | 'NOT_REQUIRED'
  | 'WAITING_FOR_DOCUMENT'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'REJECTED'

export const PIVOT_ACCOUNT_TYPE_LABEL: Record<PivotAccountType, string> = {
  NON_KYC: 'Tanpa KYC',
  KYC: 'Terverifikasi (KYC)',
}

export const PIVOT_ACCOUNT_STATUS_LABEL: Record<PivotAccountStatus, string> = {
  NOT_PROVISIONED: 'Belum diprovisi',
  CREATED: 'Dibuat',
  ACTIVE: 'Aktif',
  DEACTIVATED: 'Dinonaktifkan',
  REJECTED: 'Ditolak',
}

export const PIVOT_KYC_STATUS_LABEL: Record<PivotKycStatus, string> = {
  NOT_REQUIRED: 'Tidak diperlukan',
  WAITING_FOR_DOCUMENT: 'Menunggu dokumen',
  IN_REVIEW: 'Sedang ditinjau',
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
}

/**
 * Ringkasan sub-account Pivot tenant. `masterActive` = akun master platform sudah dikonfigurasi &
 * aktif (kalau false, provisioning tak bisa jalan). `payoutReady` = rekening payout sudah tersetel.
 */
export interface TenantPivotAccountView {
  provisioned: boolean
  type: PivotAccountType
  status: PivotAccountStatus
  kycStatus: PivotKycStatus
  shortName: string | null
  payoutChannelCode: string | null
  payoutAccountNumber: string | null
  payoutAccountName: string | null
  payoutReady: boolean
  masterActive: boolean
}

/** Setel rekening payout sub-account: kode channel bank + nomor rekening. */
export interface PivotPayoutAccountRequest {
  channelCode: string
  accountNumber: string
}

/** Baca ringkasan sub-account Pivot tenant aktif. */
export function getPivotAccount(): Promise<TenantPivotAccountView> {
  return api.get('/api/billing/pivot-account')
}

/** Provisikan sub-account Pivot untuk tenant aktif (butuh akun master platform aktif). */
export function provisionPivotAccount(): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/provision')
}

/** Segarkan status sub-account dari Pivot (sinkron ulang status/KYC). */
export function refreshPivotAccount(): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/refresh')
}

/** Ajukan upgrade KYC (khusus sub-account NON_KYC) untuk menaikkan limit. */
export function requestPivotKyc(): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/request-kyc')
}

/** Setel/ganti rekening payout sub-account. */
export function setPivotPayoutAccount(
  body: PivotPayoutAccountRequest,
): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/payout-account', body)
}
