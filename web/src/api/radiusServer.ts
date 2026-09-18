import { api } from './client'

export type RadiusServerStatus = 'ACTIVE' | 'DRAINING' | 'DISABLED'

export interface RadiusServerView {
  id: string
  name: string
  host: string
  authPort: number
  acctPort: number
  coaPort: number
  sharedSecret: string
  dbUrl: string
  dbUser: string
  maxTenants: number
  tenantCount: number
  status: RadiusServerStatus
}

export interface RadiusServerDetailView {
  server: RadiusServerView
  assignedTenantIds: string[]
}

export interface CreateRadiusServerRequest {
  name: string
  host: string
  authPort?: number
  acctPort?: number
  coaPort?: number
  sharedSecret: string
  dbUrl: string
  dbUser: string
  dbPassword: string
  maxTenants?: number
  status?: RadiusServerStatus
}

export interface UpdateRadiusServerRequest {
  name: string
  host: string
  authPort: number
  acctPort: number
  coaPort: number
  sharedSecret: string
  dbUrl: string
  dbUser: string
  dbPassword?: string
  maxTenants: number
  status: RadiusServerStatus
}

export interface TestConnectionRequest {
  dbUrl: string
  dbUser: string
  dbPassword: string
}

export interface TestConnectionResult {
  success: boolean
  message: string
}

export const listRadiusServers = () => api.get<RadiusServerView[]>('/api/platform/radius-servers')

export const getRadiusServer = (id: string) => api.get<RadiusServerDetailView>(`/api/platform/radius-servers/${id}`)

export const createRadiusServer = (req: CreateRadiusServerRequest) =>
  api.post<RadiusServerView>('/api/platform/radius-servers', req)

export const updateRadiusServer = (id: string, req: UpdateRadiusServerRequest) =>
  api.put<RadiusServerView>(`/api/platform/radius-servers/${id}`, req)

export const deleteRadiusServer = (id: string) =>
  api.del<void>(`/api/platform/radius-servers/${id}`)

export const testRadiusConnection = (req: TestConnectionRequest) =>
  api.post<TestConnectionResult>('/api/platform/radius-servers/test-connection', req)

export const testServerConnection = (id: string) =>
  api.post<TestConnectionResult>(`/api/platform/radius-servers/${id}/test-connection`, {})
