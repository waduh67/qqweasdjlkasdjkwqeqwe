import type { Page, Route } from '@playwright/test'

export type RolloutScenario = {
  readonly autoApplyEnabled: boolean
  readonly previewAllowed: boolean
  readonly executionStatus?: 'QUEUED' | 'RUNNING' | 'VERIFYING' | 'SUCCEEDED' | 'ROLLING_BACK' | 'ROLLED_BACK' | 'FAILED' | 'MANUAL_RECONCILIATION' | 'CANCELLED'
  readonly platformAdmin?: boolean
}

const permissions = [
  'provisioning.segment.view', 'provisioning.segment.manage', 'provisioning.plan.view',
  'provisioning.execution.apply', 'provisioning.execution.cancel', 'provisioning.drift.view',
  'provisioning.drift.adopt',
]

const profile = {
  id: 'operator-1', email: 'operator@example.test', name: 'Operator QA', tenantId: 'tenant-1',
  tenantSlug: 'qa', platformAdmin: false, roleIds: ['role-1'], permissions, areaIds: [],
  twoFactorEnabled: true,
}

const preview = (planId: string, allowed: boolean) => ({
  plan: {
    id: planId, tenantId: 'tenant-1', intentId: planId.includes('enterprise') ? 'intent-enterprise' : 'intent-home',
    revision: 1, status: 'VALIDATED', contentHash: 'content-hash', preconditionHash: 'precondition-hash',
    steps: [
      { id: 'step-1', order: 1, device: { kind: 'BRAS', id: 'bras-1' }, operation: 'ENSURE_PPPOE_TERMINATION', attributes: { vlanId: planId.includes('enterprise') ? '3101' : '120', pppoeProfile: planId.includes('enterprise') ? 'Enterprise dedicated' : 'Residential shared', firewallAllowList: 'RADIUS, PPPoE' }, preconditionHash: 'bras-before' },
      { id: 'step-2', order: 2, device: { kind: 'SWITCH', id: 'switch-1' }, operation: 'ENSURE_TAGGED_VLAN', attributes: { vlanId: planId.includes('enterprise') ? '3101' : '120', interface: 'xe-0/0/1' }, preconditionHash: 'switch-before' },
      { id: 'step-3', order: 3, device: { kind: 'OLT', id: 'olt-1' }, operation: 'ENSURE_ACCESS_PORT', attributes: { vlanId: planId.includes('enterprise') ? '3101' : '120', interface: 'PON 0/1', onuSerial: 'QA-ONU-17', blastRadius: '1 pelanggan' }, preconditionHash: 'olt-before' },
    ],
  },
  decision: {
    allowed,
    code: allowed ? 'ALLOWED' : 'PROTECTED_MANAGEMENT_RESOURCE',
    warnings: allowed ? [] : ['Adapter provisional dan resource manajemen dilindungi.'],
    evidenceIds: allowed ? ['evidence-1', 'evidence-2'] : [],
  },
})

const json = (route: Route, body: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify(body),
})

export async function installProvisioningRoutes(page: Page, scenario: RolloutScenario): Promise<void> {
  let appliedPlanId = 'plan-enterprise'
  const sessionProfile = scenario.platformAdmin ? { ...profile, platformAdmin: true, permissions: [...permissions, 'provisioning.certification.manage'] } : profile
  const tokenResponse = { accessToken: 'browser-test-token', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'browser-test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: sessionProfile }
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'browser-test-refresh'))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path === '/api/auth/refresh') return json(route, tokenResponse)
    if (path === '/api/me') return json(route, sessionProfile)
    if (path === '/api/subscription/lock') return json(route, { locked: false })
    if (path === '/api/provisioning/rollout') return json(route, {
      plannerEnabled: true, uiEnabled: true, autoApplyEnabled: scenario.autoApplyEnabled,
      maxAffectedSubscribers: 1, circuitFailureThreshold: 1, bulkExpansionEnabled: false,
    })
    if (path === '/api/provisioning/topology') return json(route, {
      nodes: [
        { id: 'olt-1', name: 'OLT QA', role: 'ACCESS_OLT', administrativeStatus: 'ACTIVE' },
        { id: 'switch-1', name: 'Transit QA', role: 'AGGREGATION_SWITCH', administrativeStatus: 'ACTIVE' },
        { id: 'bras-1', name: 'BRAS QA', role: 'BRAS', administrativeStatus: 'ACTIVE' },
      ],
      interfaces: [], links: [],
    })
    if (path === '/api/provisioning/vlan-pools') return json(route, [{ revision: 1, value: { id: 'pool-1', name: 'Pool QA', range: { start: 100, endInclusive: 3999 }, reservedRanges: [] } }])
    if (path === '/api/provisioning/segment-profiles') return json(route, [
      { revision: 1, value: { id: 'profile-home', name: 'Residential shared', poolId: 'pool-1' } },
      { revision: 1, value: { id: 'profile-enterprise', name: 'Enterprise dedicated', poolId: 'pool-1' } },
    ])
    if (path === '/api/provisioning/intents') return json(route, [
      { revision: 1, value: { id: 'intent-home', subscriptionId: 'sub-home', segmentProfileId: 'profile-home', status: 'ACTIVE' } },
      { revision: 1, value: { id: 'intent-enterprise', subscriptionId: 'sub-enterprise', segmentProfileId: 'profile-enterprise', status: 'ACTIVE' } },
    ])
    if (path === '/api/provisioning/capabilities') return json(route, [
      { id: 'cap-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_ACCESS_PORT', supported: scenario.previewAllowed, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z' },
      { id: 'cap-2', deviceKind: 'SWITCH', deviceId: 'switch-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_TAGGED_VLAN', supported: scenario.previewAllowed, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z' },
      { id: 'cap-3', deviceKind: 'BRAS', deviceId: 'bras-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_PPPOE_TERMINATION', supported: scenario.previewAllowed, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z' },
    ])
    if (path === '/api/provisioning/management-protections') return json(route, [
      { id: 'protection-1', deviceKind: 'OLT', deviceId: 'olt-1', complete: scenario.previewAllowed, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'obs-1', validUntil: '2099-01-01T00:00:00Z' },
      { id: 'protection-2', deviceKind: 'SWITCH', deviceId: 'switch-1', complete: scenario.previewAllowed, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'obs-2', validUntil: '2099-01-01T00:00:00Z' },
      { id: 'protection-3', deviceKind: 'BRAS', deviceId: 'bras-1', complete: scenario.previewAllowed, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'obs-3', validUntil: '2099-01-01T00:00:00Z' },
    ])
    if (path === '/api/provisioning/drift') return json(route, [{ id: 'drift-1', deviceKind: 'OLT', deviceId: 'olt-1', revision: 1, status: 'CONFLICTING', recordedAt: '2026-09-03T00:00:00Z' }])
    if (path.includes('/provisioning/certifications')) return json(route, [
      { id: 'cert-1', tenantId: 'tenant-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_ACCESS_PORT', status: 'CERTIFIED', validUntil: '2099-01-01T00:00:00Z', evidenceId: 'cap-1', revokedAt: null, revision: 1 },
      { id: 'cert-2', tenantId: 'tenant-1', deviceKind: 'SWITCH', deviceId: 'switch-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_TAGGED_VLAN', status: 'CERTIFIED', validUntil: '2099-01-01T00:00:00Z', evidenceId: 'cap-2', revokedAt: null, revision: 1 },
      { id: 'cert-3', tenantId: 'tenant-1', deviceKind: 'BRAS', deviceId: 'bras-1', vendor: 'SIMULATOR_FIXTURE', model: 'DETERMINISTIC_NETWORK', firmware: '1', transport: 'IN_MEMORY', operationClass: 'ENSURE_PPPOE_TERMINATION', status: 'CERTIFIED', validUntil: '2099-01-01T00:00:00Z', evidenceId: 'cap-3', revokedAt: null, revision: 1 },
    ])
    if (path.includes('/preview')) {
      const planId = path.split('/plans/')[1]?.split('/')[0] ?? 'plan-unknown'
      return json(route, preview(planId, scenario.previewAllowed))
    }
    if (path.includes('/apply')) {
      appliedPlanId = path.split('/plans/')[1]?.split('/')[0] ?? 'plan-unknown'
      return json(route, { id: `execution-${appliedPlanId}`, planId: appliedPlanId, revision: 1, status: 'QUEUED' })
    }
    if (path.endsWith('/timeline')) return json(route, timelineFor(scenario.executionStatus))
    if (path.includes('/executions/')) return json(route, { id: `execution-${appliedPlanId}`, planId: appliedPlanId, revision: 2, status: scenario.executionStatus ?? 'SUCCEEDED' })
    return json(route, [])
  })
}

function timelineFor(status: RolloutScenario['executionStatus']) {
  const bras = { stepOrder: 1, deviceKind: 'BRAS', deviceId: 'bras-1' } as const
  const transit = { stepOrder: 2, deviceKind: 'SWITCH', deviceId: 'switch-1' } as const
  const olt = { stepOrder: 3, deviceKind: 'OLT', deviceId: 'olt-1' } as const
  const event = (
    target: typeof bras | typeof transit | typeof olt,
    phase: string,
    result: { readonly status: string; readonly errorCode: string | null; readonly completed: boolean },
  ) => ({
    ...target, attemptNumber: 1, phase, status: result.status, errorCode: result.errorCode,
    startedAt: `2026-09-03T00:00:0${target.stepOrder}Z`,
    completedAt: result.completed ? `2026-09-03T00:00:0${target.stepOrder + 1}Z` : null,
  })
  if (status === 'QUEUED' || status === 'CANCELLED') return []
  if (status === 'RUNNING') return [event(bras, 'APPLY', { status: 'RUNNING', errorCode: null, completed: false })]
  if (status === 'VERIFYING') return [
    event(bras, 'APPLY', { status: 'SUCCEEDED', errorCode: null, completed: true }),
    event(bras, 'VERIFY', { status: 'RUNNING', errorCode: null, completed: false }),
  ]
  if (status === 'FAILED') return [event(bras, 'VERIFY', { status: 'FAILED', errorCode: 'CIRCUIT_OPEN', completed: true })]
  if (status === 'ROLLING_BACK' || status === 'ROLLED_BACK') return [
    event(bras, 'VERIFY', { status: 'FAILED', errorCode: 'VERIFICATION_MISMATCH', completed: true }),
    event(bras, 'ROLLBACK', { status: status === 'ROLLED_BACK' ? 'SUCCEEDED' : 'RUNNING', errorCode: null, completed: status === 'ROLLED_BACK' }),
  ]
  if (status === 'MANUAL_RECONCILIATION') return [
    event(bras, 'APPLY', { status: 'SUCCEEDED', errorCode: null, completed: true }),
    event(transit, 'VERIFY', { status: 'FAILED', errorCode: 'VERIFICATION_MISMATCH', completed: true }),
    event(transit, 'ROLLBACK', { status: 'FAILED', errorCode: 'ROLLBACK_POLICY_DENIED', completed: true }),
  ]
  return [
    event(bras, 'VERIFY', { status: 'SUCCEEDED', errorCode: null, completed: true }),
    event(transit, 'VERIFY', { status: 'SUCCEEDED', errorCode: null, completed: true }),
    event(olt, 'VERIFY', { status: 'SUCCEEDED', errorCode: null, completed: true }),
  ]
}
