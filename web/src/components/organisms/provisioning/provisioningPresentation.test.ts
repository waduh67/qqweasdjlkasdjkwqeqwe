import { describe, expect, it } from 'vitest'
import type { AdapterCertificationView, CapabilityEvidenceView, ManagementProtectionView, PlanPreview } from '@/api/provisioning'
import { productionReadiness } from './provisioningPresentation'

const preview: PlanPreview = {
  plan: { id: 'plan-1', tenantId: 'tenant-1', intentId: 'intent-1', revision: 1, status: 'VALIDATED', contentHash: 'hash', preconditionHash: 'before', steps: [{ id: 'step-1', order: 1, device: { kind: 'OLT', id: 'olt-1' }, operation: 'ENSURE_ACCESS_PORT', attributes: {}, preconditionHash: 'before' }] },
  decision: { allowed: true, code: 'ALLOWED', warnings: [], evidenceIds: [] },
}
const capability: CapabilityEvidenceView = { id: 'cap-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'HUAWEI', model: 'MA5800', firmware: 'R019', transport: 'SSH', operationClass: 'ENSURE_ACCESS_PORT', supported: true, observedAt: '2026-01-01T00:00:00Z', expiresAt: '2027-01-01T00:00:00Z' }
const protection: ManagementProtectionView = { id: 'protection-1', deviceKind: 'OLT', deviceId: 'olt-1', complete: true, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'observation-1', validUntil: '2027-01-01T00:00:00Z' }
const certification: AdapterCertificationView = { id: 'cert-1', tenantId: 'tenant-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'HUAWEI', model: 'MA5800', firmware: 'R019', transport: 'SSH', operationClass: 'ENSURE_ACCESS_PORT', status: 'CERTIFIED', validUntil: '2027-01-01T00:00:00Z', evidenceId: 'cap-1', revokedAt: null, revision: 1 }

describe('productionReadiness', () => {
  it('rejects expired capability and certification evidence', () => {
    const now = new Date('2028-01-01T00:00:00Z')
    const readiness = productionReadiness(preview, [capability], [protection], [certification], now)

    expect(readiness.ready).toBe(false)
    expect(readiness.capabilityReady).toBe(false)
    expect(readiness.certificationReady).toBe(false)
  })

  it('does not combine a current capability with certification for another fingerprint', () => {
    const current = { ...capability, firmware: 'R020', expiresAt: '2029-01-01T00:00:00Z' }
    const readiness = productionReadiness(
      preview,
      [capability, current],
      [protection],
      [certification],
      new Date('2028-01-01T00:00:00Z'),
    )

    expect(readiness.capabilityReady).toBe(true)
    expect(readiness.certificationReady).toBe(false)
    expect(readiness.ready).toBe(false)
  })

  it('rejects expired management protection evidence', () => {
    const readiness = productionReadiness(
      preview,
      [{ ...capability, expiresAt: '2029-01-01T00:00:00Z' }],
      [protection],
      [{ ...certification, validUntil: '2029-01-01T00:00:00Z' }],
      new Date('2028-01-01T00:00:00Z'),
    )

    expect(readiness.protectionReady).toBe(false)
    expect(readiness.ready).toBe(false)
  })
})
