import { api } from './client'
import type { LocationPayload } from './onboarding'
import type { ServiceType } from './catalog'

export interface MigrationView {
  customerId: string; subscriptionId: string; source: 'MANUAL' | 'CSV' | 'MIKROTIK'
  username: string | null; authType: ServiceType; nasId: string | null; framedIp: string | null
  hasPassword: boolean; accessId: string | null; billingManaged: boolean
  firstInvoiceOn: string | null; billingDay: number | null
  onus?: Array<{ onuId: string; serialNumber: string; oltCode: string; ponPortLabel: string | null; linkedAt: string }>
}
export interface MigrationRequest {
  operationKey: string
  customer: { code: string | null; name: string; phone: string | null; email: string | null; address: string; areaId: string | null; location: LocationPayload | null; planId: string; monthlyFeeOverride: number | null }
  status: 'ACTIVE' | 'SUSPENDED'; installationDate: string | null; firstInvoiceOn: string | null; billingDay: number | null
  username: string | null; password: string | null; authType: ServiceType; nasId: string | null; framedIp: string | null
  onuSerial?: string | null
}
export const migrateCustomer = (body: MigrationRequest) => api.post<MigrationView>('/api/onboarding/migrations', body)
export const getCustomerMigration = (id: string) => api.get<MigrationView | null>(`/api/onboarding/migrations/${id}`)
export const linkMigrationOnu = (id: string, serialNumber: string) => api.post<NonNullable<MigrationView['onus']>>(`/api/onboarding/migrations/${id}/onus`, { serialNumber })
export const startMigrationBilling = (id: string, firstInvoiceOn: string, billingDay: number | null) => api.post<MigrationView>(`/api/onboarding/migrations/${id}/billing`, { firstInvoiceOn, billingDay })
export const prepareMigrationRadius = (id: string, body: { nasId: string; username: string; password?: string; authType: ServiceType; framedIp: string | null }) => api.post<MigrationView>(`/api/onboarding/migrations/${id}/radius`, body)
export function localToday() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
