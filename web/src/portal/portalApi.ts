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

/**
 * Laporan gangguan yang dibuat pelanggan sendiri (module `helpdesk` di server).
 * `status`/`category` = nama enum; nama staf sudah disamarkan server jadi "Tim dukungan".
 */
export interface PortalTicket {
  id: string
  code: string
  category: string
  subject: string
  status: string
  /** Kode work order bila keluhannya sudah dijadwalkan ke teknisi. */
  workOrderCode: string | null
  openedAt: string
  lastActivityAt: string
}

export interface PortalTicketMessage {
  author: 'CUSTOMER' | 'OPERATOR' | 'SYSTEM'
  authorName: string
  body: string
  at: string
}

export interface PortalTicketDetail {
  ticket: PortalTicket
  description: string
  messages: PortalTicketMessage[]
}

/** Satu ISP yang bisa dipilih ketika identitas yang sama dipakai di lebih dari satu tempat. */
export interface PortalTenantChoice {
  tenantSlug: string
  tenantName: string
}

/**
 * Hasil satu percobaan masuk. `CHOOSE_TENANT` hanya muncul bila password sudah terbukti
 * benar di lebih dari satu ISP — server tak pernah membocorkan daftar ISP sebelum itu.
 */
export interface PortalLoginResponse {
  status: 'AUTHENTICATED' | 'CHOOSE_TENANT'
  tokens: PortalTokenResponse | null
  choices: PortalTenantChoice[]
}

/**
 * `identifier` = email, nomor HP, atau username — apa pun yang diingat pelanggan.
 * `tenant` hanya dikirim saat pelanggan datang lewat tautan ber-ISP atau baru saja memilih
 * ISP di layar lanjutan; pelanggan tak pernah diminta mengetiknya sendiri.
 */
export const portalLogin = (identifier: string, password: string, tenant?: string) =>
  portalApiClient.post<PortalLoginResponse>('/api/portal/auth/login', { identifier, password, tenant })

export const portalLogout = (refreshToken: string) =>
  portalApiClient.post<void>('/api/portal/auth/logout', { refreshToken })

/**
 * Minta kode pemulihan. SELALU sukses — server sengaja tak memberi tahu apakah identitasnya
 * dikenal, jadi UI tak boleh menjanjikan "kode sudah dikirim ke email Anda" melainkan
 * kalimat yang benar untuk kedua kemungkinan.
 */
export const portalForgotPassword = (identifier: string, tenant?: string) =>
  portalApiClient.post<void>('/api/portal/auth/forgot-password', { identifier, tenant })

/** Tukar kode dengan password baru. Di sini kegagalan dilaporkan apa adanya. */
export const portalResetPassword = (identifier: string, code: string, newPassword: string) =>
  portalApiClient.post<void>('/api/portal/auth/reset-password', { identifier, code, newPassword })

export const getPortalProfile = () => portalApiClient.get<PortalAccount>('/api/portal/me/profile')
export const getPortalBilling = () => portalApiClient.get<PortalBilling>('/api/portal/me/billing')
export const getPortalConnection = () => portalApiClient.get<PortalConnection>('/api/portal/me/connection')

export const changePortalPassword = (currentPassword: string, newPassword: string) =>
  portalApiClient.post<void>('/api/portal/me/password', { currentPassword, newPassword })

export const getPortalTickets = () => portalApiClient.get<PortalTicket[]>('/api/portal/me/tickets')

export const getPortalTicket = (id: string) =>
  portalApiClient.get<PortalTicketDetail>(`/api/portal/me/tickets/${id}`)

export const submitPortalTicket = (category: string, subject: string, description: string) =>
  portalApiClient.post<PortalTicketDetail>('/api/portal/me/tickets', { category, subject, description })

export const replyPortalTicket = (id: string, body: string) =>
  portalApiClient.post<PortalTicketDetail>(`/api/portal/me/tickets/${id}/replies`, { body })

/** "Sudah beres" — pelanggan menutup sendiri laporannya; utasnya berhenti menerima balasan. */
export const closePortalTicket = (id: string) =>
  portalApiClient.post<PortalTicketDetail>(`/api/portal/me/tickets/${id}/close`)

