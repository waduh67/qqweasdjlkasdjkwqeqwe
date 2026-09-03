import { api } from './client'

export type ProvisioningRolloutView = {
  readonly plannerEnabled: boolean
  readonly uiEnabled: boolean
  readonly autoApplyEnabled: boolean
  readonly maxAffectedSubscribers: number
  readonly circuitFailureThreshold: number
  readonly bulkExpansionEnabled: boolean
}

export const getProvisioningRollout = () => api.get<ProvisioningRolloutView>('/api/provisioning/rollout')
