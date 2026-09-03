import { api } from './client'

export type ProvisioningDeviceKind = 'OLT' | 'SWITCH' | 'ROUTER' | 'BRAS'
export type ManagedNodeRole = 'OLT' | 'ACCESS_SWITCH' | 'AGGREGATION_SWITCH' | 'BRAS'
export type AdministrativeStatus = 'ENABLED' | 'DISABLED' | 'EXCLUDED'
export type VlanAllocationMode = 'SHARED' | 'DEDICATED'
export type ProvisioningMode = 'PRODUCTION_AUTO_APPLY' | 'DRY_RUN' | 'SIMULATOR'
export type CertificationStatus = 'CERTIFIED' | 'PROVISIONAL' | 'UNSUPPORTED' | 'REQUIRES_MANUAL'
export type ExecutionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'VERIFYING'
  | 'SUCCEEDED'
  | 'ROLLING_BACK'
  | 'ROLLED_BACK'
  | 'FAILED'
  | 'MANUAL_RECONCILIATION'
  | 'CANCELLED'

export interface RevisionedResource<T> {
  revision: number
  value: T
}

export interface ProvisioningTopology {
  nodes: Array<{ id: string; name: string; role: ManagedNodeRole; reference?: { kind: 'OLT' | 'PON' | 'ONU' | 'NAS'; id: string } | null; administrativeStatus: AdministrativeStatus }>
  interfaces: Array<{ id: string; nodeId: string; name: string; role: 'ACCESS' | 'TRUNK' | 'UPLINK' | 'MANAGEMENT'; reference?: { kind: 'OLT' | 'PON' | 'ONU' | 'NAS'; id: string } | null; administrativeStatus: AdministrativeStatus }>
  links: Array<{ id: string; interfaceAId: string; interfaceZId: string; administrativeStatus: AdministrativeStatus }>
}

export interface VlanRangeInput { start: number; endInclusive: number }
export interface TopologyNodeInput { revision?: number; name: string; role: ManagedNodeRole; referenceKind?: string | null; referenceId?: string | null; status: AdministrativeStatus }
export interface TopologyInterfaceInput { revision?: number; nodeId: string; name: string; role: string; referenceKind?: string | null; referenceId?: string | null; status: string }
export interface TopologyLinkInput { revision?: number; interfaceAId: string; interfaceZId: string; status: string }
export interface VlanPoolInput { revision?: number; name: string; vlanStart: number; vlanEnd: number; reserved?: VlanRangeInput[] }
export interface SegmentProfileInput { revision?: number; name: string; poolId: string }
export interface ServiceIntentInput { revision?: number; subscriptionId: string; segmentProfileId: string; allocationMode: VlanAllocationMode; dedicatedVlanId?: number | null; accessOltId: string; accessPonPortId: string; accessOnuId: string; status?: string }

export interface VlanPoolView {
  id: string
  name: string
  range: VlanRangeInput
  reservedRanges: VlanRangeInput[]
}

export interface SegmentProfileView {
  id: string
  name: string
  poolId: string
}

export interface ServiceIntentView {
  id: string
  subscriptionId: string | null
  hotspotSiteId: string | null
  segmentProfileId: string
  allocationMode: VlanAllocationMode
  dedicatedVlanId: number | null
  accessOltId: string | null
  accessPonPortId: string | null
  accessOnuId: string | null
  status: string
}

export interface GeneratedPlanView { id: string; intentId: string; revision: number; status: string; contentHash: string }

export interface PlanPreview {
  plan: {
    id: string
    tenantId: string
    intentId: string
    revision: number
    status: 'GENERATED' | 'VALIDATED' | 'REJECTED' | 'SUPERSEDED'
    contentHash: string
    preconditionHash: string
    steps: Array<{
      id: string
      order: number
      device: { kind: ProvisioningDeviceKind; id: string }
      operation: string
      attributes: Record<string, string>
      preconditionHash: string
    }>
  }
  decision: { allowed: boolean; code: string; warnings: string[]; evidenceIds: string[] }
}

export interface ExecutionView {
  id: string
  planId: string
  revision: number
  status: ExecutionStatus
}

export interface ExecutionTimelineEntry {
  stepOrder: number
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  attemptNumber: number
  phase: string
  status: string
  errorCode: string | null
  startedAt: string
  completedAt: string | null
}

export interface ObservationView {
  id: string
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  observedAt: string
}

export interface CapabilityEvidenceView {
  id: string
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  vendor: string
  model: string
  firmware: string
  transport: string
  operationClass: string
  supported: boolean
  observedAt: string
  expiresAt: string
}

export interface ManagementProtectionView {
  id: string
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  complete: boolean
  sourceType: 'TOPOLOGY_OBSERVATION' | 'DEVICE_OBSERVATION' | null
  sourceEvidenceId: string | null
  validUntil: string
}

export interface DriftView {
  id: string
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  revision: number
  status: 'NONE' | 'BENIGN' | 'CONFLICTING' | 'UNKNOWN'
  recordedAt: string
}

export interface AdapterCertificationView {
  id: string
  tenantId: string
  deviceKind: ProvisioningDeviceKind
  deviceId: string
  vendor: string
  model: string
  firmware: string
  transport: string
  operationClass: string
  status: CertificationStatus
  validUntil: string
  evidenceId: string | null
  revokedAt: string | null
  revision: number
}

export type CertifyAdapterInput = {
  readonly deviceKind: ProvisioningDeviceKind
  readonly deviceId: string
  readonly vendor: string
  readonly model: string
  readonly firmware: string
  readonly transport: string
  readonly operationClass: string
  readonly validUntil: string
}

export class InvalidProvisioningResponseError extends Error {
  readonly name = 'InvalidProvisioningResponseError'

  constructor() {
    super('Respons provisioning tidak sesuai kontrak')
  }
}

function isExecutionStatus(value: string): value is ExecutionStatus {
  switch (value) {
    case 'QUEUED':
    case 'RUNNING':
    case 'VERIFYING':
    case 'SUCCEEDED':
    case 'ROLLING_BACK':
    case 'ROLLED_BACK':
    case 'FAILED':
    case 'MANUAL_RECONCILIATION':
    case 'CANCELLED':
      return true
    default:
      return false
  }
}

function parseExecution(value: unknown): ExecutionView {
  if (
    typeof value !== 'object' || value === null ||
    !('id' in value) || typeof value.id !== 'string' ||
    !('planId' in value) || typeof value.planId !== 'string' ||
    !('revision' in value) || typeof value.revision !== 'number' ||
    !('status' in value) || typeof value.status !== 'string' || !isExecutionStatus(value.status)
  ) throw new InvalidProvisioningResponseError()
  return { id: value.id, planId: value.planId, revision: value.revision, status: value.status }
}

const revisionHeader = (revision: number) => ({ 'If-Match': `"${revision}"` })

export const getTopology = () => api.get<ProvisioningTopology>('/api/provisioning/topology')
export const createTopologyNode = (body: TopologyNodeInput) => api.post<RevisionedResource<ProvisioningTopology['nodes'][number]>>('/api/provisioning/topology/nodes', body)
export const updateTopologyNode = (id: string, body: TopologyNodeInput) => api.put<RevisionedResource<ProvisioningTopology['nodes'][number]>>(`/api/provisioning/topology/nodes/${id}`, body)
export const createTopologyInterface = (body: TopologyInterfaceInput) => api.post<RevisionedResource<ProvisioningTopology['interfaces'][number]>>('/api/provisioning/topology/interfaces', body)
export const updateTopologyInterface = (id: string, body: TopologyInterfaceInput) => api.put<RevisionedResource<ProvisioningTopology['interfaces'][number]>>(`/api/provisioning/topology/interfaces/${id}`, body)
export const createTopologyLink = (body: TopologyLinkInput) => api.post<RevisionedResource<ProvisioningTopology['links'][number]>>('/api/provisioning/topology/links', body)
export const updateTopologyLink = (id: string, body: TopologyLinkInput) => api.put<RevisionedResource<ProvisioningTopology['links'][number]>>(`/api/provisioning/topology/links/${id}`, body)
export const deleteTopologyResource = (type: 'TOPOLOGY_NODE' | 'TOPOLOGY_INTERFACE' | 'TOPOLOGY_LINK', id: string, revision: number) =>
  api.del<void>(`/api/provisioning/topology/${type}/${id}?revision=${revision}`)
export const listVlanPools = () => api.get<Array<RevisionedResource<VlanPoolView>>>('/api/provisioning/vlan-pools')
export const createVlanPool = (body: VlanPoolInput) => api.post<RevisionedResource<VlanPoolView>>('/api/provisioning/vlan-pools', body)
export const updateVlanPool = (id: string, body: VlanPoolInput) => api.put<RevisionedResource<VlanPoolView>>(`/api/provisioning/vlan-pools/${id}`, body)
export const deleteVlanPool = (id: string, revision: number) => api.del<void>(`/api/provisioning/vlan-pools/${id}?revision=${revision}`)
export const listSegmentProfiles = () => api.get<Array<RevisionedResource<SegmentProfileView>>>('/api/provisioning/segment-profiles')
export const createSegmentProfile = (body: SegmentProfileInput) => api.post<RevisionedResource<SegmentProfileView>>('/api/provisioning/segment-profiles', body)
export const updateSegmentProfile = (id: string, body: SegmentProfileInput) => api.put<RevisionedResource<SegmentProfileView>>(`/api/provisioning/segment-profiles/${id}`, body)
export const deleteSegmentProfile = (id: string, revision: number) => api.del<void>(`/api/provisioning/segment-profiles/${id}?revision=${revision}`)
export const listServiceIntents = (signal?: AbortSignal) =>
  api.request<Array<RevisionedResource<ServiceIntentView>>>('/api/provisioning/intents', { signal })
export const createServiceIntent = (body: ServiceIntentInput) => api.post<RevisionedResource<ServiceIntentView>>('/api/provisioning/intents', body)
export const updateServiceIntent = (id: string, body: ServiceIntentInput) => api.put<RevisionedResource<ServiceIntentView>>(`/api/provisioning/intents/${id}`, body)
export const generateProvisioningPlan = (
  intentId: string,
  change: 'CREATE' | 'DELETE' = 'CREATE',
  signal?: AbortSignal,
) => api.request<GeneratedPlanView>(`/api/provisioning/intents/${intentId}/plans`, {
  method: 'POST', body: JSON.stringify({ change }), signal,
})
export const suspendProvisioningIntent = (intentId: string) => api.post<string>(`/api/provisioning/intents/${intentId}/suspend`)
export const restoreProvisioningIntent = (intentId: string) => api.post<string>(`/api/provisioning/intents/${intentId}/restore`)
export const deprovisionIntent = async (intentId: string, idempotencyKey: string, forceDisconnect = false) =>
  parseExecution(await api.request<unknown>(`/api/provisioning/intents/${intentId}/deprovision?forceDisconnect=${forceDisconnect}`, {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey },
  }))
export const previewProvisioning = (
  planId: string,
  mode: Exclude<ProvisioningMode, 'PRODUCTION_AUTO_APPLY'>,
  signal?: AbortSignal,
) => api.request<PlanPreview>(`/api/provisioning/plans/${planId}/preview?mode=${mode}`, { method: 'POST', signal })
export const applyProvisioningPlan = async (planId: string, revision: number, idempotencyKey: string) =>
  parseExecution(await api.request<unknown>(`/api/provisioning/plans/${planId}/apply`, {
    method: 'POST',
    headers: { ...revisionHeader(revision), 'Idempotency-Key': idempotencyKey },
  }))
export const cancelProvisioningExecution = async (executionId: string, revision: number) =>
  parseExecution(await api.request<unknown>(`/api/provisioning/executions/${executionId}/cancel`, {
    method: 'POST', headers: revisionHeader(revision),
  }))
export const getProvisioningExecution = async (executionId: string, signal?: AbortSignal) =>
  parseExecution(await api.request<unknown>(`/api/provisioning/executions/${executionId}`, { signal }))
export const getProvisioningTimeline = (executionId: string, signal?: AbortSignal) =>
  api.request<ExecutionTimelineEntry[]>(`/api/provisioning/executions/${executionId}/timeline`, { signal })
export const listProvisioningCapabilities = () => api.get<CapabilityEvidenceView[]>('/api/provisioning/capabilities')
export const listManagementProtections = () => api.get<ManagementProtectionView[]>('/api/provisioning/management-protections')
export const listProvisioningObservations = (signal?: AbortSignal) =>
  api.request<ObservationView[]>('/api/provisioning/observations', { signal })
export const listProvisioningDrift = () => api.get<DriftView[]>('/api/provisioning/drift')
export const adoptProvisioningDrift = (id: string, revision: number) =>
  api.request<DriftView>(`/api/provisioning/drift/${id}/adopt`, {
    method: 'POST', headers: revisionHeader(revision),
  })
export const listAdapterCertifications = (tenantId: string) =>
  api.get<AdapterCertificationView[]>(`/api/platform/tenants/${tenantId}/provisioning/certifications`)
export const certifyAdapter = (tenantId: string, body: CertifyAdapterInput) =>
  api.post<AdapterCertificationView>(`/api/platform/tenants/${tenantId}/provisioning/certifications`, body)
export const revokeAdapterCertification = (tenantId: string, certificationId: string, revision: number) =>
  api.request<AdapterCertificationView>(
    `/api/platform/tenants/${tenantId}/provisioning/certifications/${certificationId}/revoke`,
    { method: 'POST', headers: revisionHeader(revision) },
  )
