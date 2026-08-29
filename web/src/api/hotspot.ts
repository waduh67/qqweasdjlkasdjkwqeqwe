import { api } from './client'
import type { ServiceType } from './catalog'

export const HOTSPOT_VIEW_PERMISSIONS: string[] = [
  'hotspot.voucher.view',
  'hotspot.site.view',
  'hotspot.session.view',
]

export type HotspotViewPermission = (typeof HOTSPOT_VIEW_PERMISSIONS)[number]
export type PortalMode = 'OFF' | 'NAS_OWNED' | 'NETOPS_HOSTED'
export type VoucherStatus = 'AVAILABLE' | 'ACTIVE' | 'EXPIRED' | 'REVOKED'
export type VoucherBatchStatus = string

export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type HotspotBranding = {
  displayName: string | null
  logoUrl: string | null
}

export type PublicHotspotPortalContext = {
  displayName: string
  logoUrl: string | null
  redirectUrl: string | null
  clientMac: string | null
  clientIp: string | null
}

export type HotspotSiteView = {
  id: string
  name: string
  location: string | null
  nasId: string
  portalMode: PortalMode
  portalId: string
  branding: HotspotBranding
  defaultPlanId: string | null
}

export type SaveHotspotSiteRequest = {
  nasId: string
  name: string
  location?: string | null
  portalMode: PortalMode
  branding?: HotspotBranding | null
  defaultPlanId?: string | null
}

export type UpdateHotspotSiteRequest = Omit<SaveHotspotSiteRequest, 'nasId'>

export type VoucherBatchView = {
  id: string
  siteId: string
  planId: string
  durationSeconds: number
  status: VoucherBatchStatus
}

export type VoucherView = {
  id: string
  batchId: string | null
  username: string
  siteId: string
  planId: string
  durationSeconds: number
  status: VoucherStatus
  activatedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  revocationReason: string | null
}

export type IssuedVoucherCredential = {
  voucherId: string
  username: string
  password: string
}

export type CreateVoucherBatchRequest = {
  siteId: string
  planId: string
  durationSeconds: number
  quantity: number
}

export type CreateVoucherBatchResponse = {
  batch: VoucherBatchView
  credentials: IssuedVoucherCredential[]
}

export type ListVoucherBatchesQuery = {
  siteId?: string
  page?: number
  size?: number
}

export type ListVouchersQuery = {
  batchId?: string
  siteId?: string
  status?: VoucherStatus
  page?: number
  size?: number
}

function withQuery(path: string, query: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  const suffix = params.toString()
  return suffix ? `${path}?${suffix}` : path
}

export function getPublicHotspotPortalContext(state: string) {
  return api.post<PublicHotspotPortalContext>('/api/public/hotspot/portal-context/resolve', { state })
}

export const listHotspotSites = () => api.get<HotspotSiteView[]>('/api/hotspot/sites')
export const createHotspotSite = (request: SaveHotspotSiteRequest) => api.post<HotspotSiteView>('/api/hotspot/sites', request)
export const updateHotspotSite = (siteId: string, request: UpdateHotspotSiteRequest) => api.put<HotspotSiteView>(`/api/hotspot/sites/${siteId}`, request)

export function listVoucherBatches(query: ListVoucherBatchesQuery = {}) {
  return api.get<PageResponse<VoucherBatchView>>(withQuery('/api/hotspot/voucher-batches', query))
}

export function createVoucherBatch(request: CreateVoucherBatchRequest) {
  return api.post<CreateVoucherBatchResponse>('/api/hotspot/voucher-batches', request)
}

export function listVouchers(query: ListVouchersQuery = {}) {
  return api.get<PageResponse<VoucherView>>(withQuery('/api/hotspot/vouchers', query))
}

export function revokeVoucher(voucherId: string, reason: string) {
  return api.post<VoucherView>(`/api/hotspot/vouchers/${voucherId}/revoke`, { reason })
}

export function canViewHotspot(can: (permission: string) => boolean): boolean {
  return HOTSPOT_VIEW_PERMISSIONS.some(can)
}

export function isHotspotPlan(plan: { serviceTypes: ServiceType[]; active: boolean }): boolean {
  return plan.active && plan.serviceTypes.includes('HOTSPOT')
}
