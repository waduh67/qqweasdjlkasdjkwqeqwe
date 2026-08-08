import { portalApiClient } from './portalClient'

/**
 * Tipe & panggilan realm PORTAL pelanggan — cermin `PortalAuthController` +
 * `PortalSelfServiceController` di server (`/api/portal/...`). Semua baca ter-scope ke
 * pelanggan yang login (server ambil id dari principal, bukan dari klien).
 */

/** Profil pelanggan pada token (dari login/refresh). */
export interface PortalProfile {
  customerId: string
  tenantId: string
  tenantSlug: string
  code: string
  name: string
  login: string
  phone: string | null
  status: string
}

export interface PortalTokenResponse {
  accessToken: string
  tokenType: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  customer: PortalProfile
}

/** Satu langganan + detail paket (best-effort dari katalog). `amount` string (BigDecimal). */
export interface PortalSubscription {
  subscriptionId: string
  packageName: string
  bandwidthMbps: number
  status: string
  monthlyFee: string | null
  downMbps: number | null
  upMbps: number | null
  fupEnabled: boolean
  fupQuotaMb: number | null
}

export interface PortalAccount {
  customerId: string
  code: string
  name: string
  phone: string | null
  status: string
  subscriptions: PortalSubscription[]
}

export interface PortalInvoice {
  id: string
  number: string
  periodStart: string
  periodEnd: string
  amount: string
  status: string
  issuedAt: string
  dueDate: string
  paidAt: string | null
  /** Bisa dibayar online sekarang (masih terbuka: ISSUED/OVERDUE). */
  payable: boolean
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
}

export interface PortalPayment {
  id: string
  invoiceId: string
  amount: string
  provider: string
  paidAt: string
  note: string | null
}

export interface PortalBilling {
  outstandingAmount: string
  outstandingCount: number
  unpaidCount: number
  oldestDueDate: string | null
  lastPaidAt: string | null
  invoices: PortalInvoice[]
  payments: PortalPayment[]
}

export interface PortalSession {
  username: string
  accessStatus: string
  planName: string | null
  online: boolean
  framedIp: string | null
  nasName: string | null
  uptimeSeconds: number | null
  startedAt: string | null
  lastSeenAt: string | null
}

export interface PortalDevice {
  deviceId: string
  serialNumber: string
  manufacturer: string | null
  model: string | null
  softwareVersion: string | null
  ipAddress: string | null
  online: boolean
  lastInformAt: string | null
}

export interface PortalConnection {
  session: PortalSession | null
  devices: PortalDevice[]
}

export const portalLogin = (tenant: string, login: string, password: string) =>
  portalApiClient.post<PortalTokenResponse>('/api/portal/auth/login', { tenant, login, password })

export const portalLogout = (refreshToken: string) =>
  portalApiClient.post<void>('/api/portal/auth/logout', { refreshToken })

export const getPortalProfile = () => portalApiClient.get<PortalAccount>('/api/portal/me/profile')
export const getPortalBilling = () => portalApiClient.get<PortalBilling>('/api/portal/me/billing')
export const getPortalConnection = () => portalApiClient.get<PortalConnection>('/api/portal/me/connection')

export const changePortalPassword = (currentPassword: string, newPassword: string) =>
  portalApiClient.post<void>('/api/portal/me/password', { currentPassword, newPassword })

