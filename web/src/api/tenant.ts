import { api } from './client'
import type { PageResponse } from './types'

export interface TenantOwner {
  readonly id: string
  readonly name: string
  readonly email: string
  readonly status: string
}

export interface PlatformTenant {
  readonly id: string
  readonly slug: string
  readonly name: string
  readonly status: string
  readonly owner?: TenantOwner | null
}

export const getPlatformTenant = (id: string) => api.get<PlatformTenant>('/api/platform/tenants/' + id)
export const getOwnerCandidates = (tenantId: string, search: { query: string; page: number }) => {
  const params = new URLSearchParams({ query: search.query, page: String(search.page), size: '20' })
  return api.get<PageResponse<TenantOwner>>('/api/platform/tenants/' + tenantId + '/owner/candidates?' + params)
}
export const bindTenantOwner = (tenantId: string, userId: string) =>
  api.put<TenantOwner>('/api/platform/tenants/' + tenantId + '/owner', { userId })
export const resetTenantOwnerPassword = (tenantId: string, command: { expectedOwnerUserId: string; newPassword: string }) =>
  api.post<void>('/api/platform/tenants/' + tenantId + '/owner/password', command)

/**
 * Unduh arsip ZIP berisi seluruh data tenant sendiri (portabilitas data / offboarding).
 * Server men-stream isinya, jadi respons bisa berukuran besar dan tak punya Content-Length.
 */
export const downloadTenantArchive = () => api.blob('/api/tenant/export')
