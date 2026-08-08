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
 * `profileComplete` = profil bisnis (identitas/PIC/alamat) sudah cukup untuk mendaftar ke Pivot.
 */
export interface TenantPivotAccountView {
  provisioned: boolean
  /** UUID sub-account di Pivot (`x-submerchant-id`); null bila belum diprovisi. Bukan rahasia. */
  subMerchantUuid: string | null
  type: PivotAccountType
  status: PivotAccountStatus
  kycStatus: PivotKycStatus
  shortName: string | null
  // Profil bisnis sub-account (non-rahasia) — wajib sebelum provisioning.
  legalName: string | null
  merchantEmail: string | null
  merchantPhone: string | null
  picName: string | null
  picEmail: string | null
  picPhone: string | null
  address: string | null
  profileComplete: boolean
  payoutChannelCode: string | null
  payoutAccountNumber: string | null
  payoutAccountName: string | null
  payoutReady: boolean
  masterActive: boolean
  /**
   * Biaya yang dipotong tiap payout (Rp bila FIXED, angka persen bila PERCENTAGE) — setelan platform,
   * dibuka supaya UI bisa menunjukkan berapa yang benar-benar sampai sebelum dikirim. 0 = tak dipotong.
   */
  payoutFeeMinor: number
  payoutFeeType: 'FIXED' | 'PERCENTAGE'
}

/**
 * Profil bisnis sub-account yang diisi tenant. `legalName` opsional (fallback nama tenant).
 * Rekening payout (`channelCode`+`accountNumber`) kini bagian dari profil — Pivot mewajibkan
 * `bankAccount` saat create sub-account, jadi diisi sekalian sebelum provisioning.
 */
export interface PivotProfileRequest {
  legalName: string | null
  merchantEmail: string | null
  merchantPhone: string | null
  picName: string | null
  picEmail: string | null
  picPhone: string | null
  address: string | null
  channelCode: string | null
  accountNumber: string | null
  accountName: string | null
}

/**
 * Setel rekening payout sub-account: kode channel bank + nomor rekening + nama pemilik. Nama diketik
 * tenant (Pivot tak mengembalikannya) lalu dicocokkan dengan catatan bank saat inquiry; maks 60 karakter.
 */
export interface PivotPayoutAccountRequest {
  channelCode: string
  accountNumber: string
  accountName: string
}

/** Baca ringkasan sub-account Pivot tenant aktif. */
export function getPivotAccount(): Promise<TenantPivotAccountView> {
  return api.get('/api/billing/pivot-account')
}

/** Simpan profil bisnis sub-account (identitas + PIC + alamat) — wajib sebelum provisioning. */
export function savePivotProfile(body: PivotProfileRequest): Promise<TenantPivotAccountView> {
  return api.put('/api/billing/pivot-account/profile', body)
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

/** Undang/assign user admin ke sub-account tenant (Pivot mengirim email undangan). */
export function assignSubAccountUser(body: {
  email: string
  name: string
}): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/users', body)
}

/** Kirim ulang undangan ke user sub-account tenant berdasarkan email. */
export function resendSubAccountInvitation(body: {
  email: string
}): Promise<TenantPivotAccountView> {
  return api.post('/api/billing/pivot-account/users/resend-invitation', body)
}

/** Jenis penyaluran dana: PAYOUT (ke beneficiary bebas) atau WITHDRAWAL (tarik saldo KYC sendiri). */
export type PivotPayoutKind = 'PAYOUT' | 'WITHDRAWAL'

/** Status penyaluran dana di sisi lokal (difinalkan callback Pivot). */
export type PivotPayoutStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'

/**
 * Cuplikan saldo PEMBAYARAN tenant (rupiah utuh) — dana hasil tagihan pelanggan, bukan saldo payout
 * (dompet terpisah di Pivot). `subAccount` = saldo dibaca on-behalf sub-account tenant.
 */
export interface PivotBalanceView {
  availableMinor: number
  currency: string
  subAccount: boolean
}

/**
 * Satu baris riwayat penyaluran dana untuk ditampilkan.
 *
 * `amountMinor` = nominal yang diminta; `feeMinor` = biaya payout yang dipotong (dibekukan per baris
 * — tarifnya setelan yang bisa berubah); `netAmountMinor` = yang benar-benar mendarat di rekening.
 */
export interface TenantPayoutView {
  id: string
  kind: PivotPayoutKind
  amountMinor: number
  feeMinor: number
  netAmountMinor: number
  channelCode: string | null
  accountNumber: string | null
  accountName: string | null
  status: PivotPayoutStatus
  pivotRef: string | null
  failureReason: string | null
  createdAt: string
}

/** Baca saldo pembayaran tenant (on-behalf sub-account bila sudah terprovisi). */
export function getPivotBalance(): Promise<PivotBalanceView> {
  return api.get('/api/billing/pivot-account/balance')
}

/** Riwayat penyaluran dana tenant (terbaru-dahulu). */
export function listPivotPayouts(): Promise<TenantPayoutView[]> {
  return api.get('/api/billing/pivot-account/payouts')
}

/**
 * Salurkan dana ke rekening beneficiary bebas. Server mencocokkan `accountName` dengan catatan bank
 * (inquiry) — payout ditolak bila namanya beda — & wajib mengecek saldo sebelum membuat payout.
 */
export function createPivotPayout(body: {
  channelCode: string
  accountNumber: string
  accountName: string
  amountMinor: number
  description?: string | null
}): Promise<TenantPayoutView> {
  return api.post('/api/billing/pivot-account/payouts', body)
}
