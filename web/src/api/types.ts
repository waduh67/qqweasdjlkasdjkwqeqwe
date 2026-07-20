/** Bentuk data yang dipertukarkan dengan ftth-server. */

export interface Profile {
  id: string
  email: string
  name: string
  tenantId: string
  tenantSlug: string
  platformAdmin: boolean
  roleIds: string[]
  permissions: string[]
  areaIds: string[]
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: Profile
}

export interface Permission {
  id: string
  code: string
  module: string
  resource: string
  action: string
  description: string | null
  platformOnly: boolean
}

export interface PermissionCatalog {
  modules: { module: string; permissions: Permission[] }[]
}

export interface Role {
  id: string
  name: string
  description: string | null
  systemRole: boolean
  permissionIds: string[]
}

export interface User {
  id: string
  email: string
  name: string
  status: string
  platformAdmin: boolean
  roleIds: string[]
  areaIds: string[]
  createdAt: string
}

export interface Area {
  id: string
  code: string
  name: string
  parentId: string | null
}

export interface AuditEntry {
  id: string
  actorId: string | null
  actorEmail: string | null
  action: string
  entityType: string | null
  entityId: string | null
  detail: Record<string, unknown>
  occurredAt: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
