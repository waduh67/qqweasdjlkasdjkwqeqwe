import type { PolicyDetails, PolicyVersion } from '@/api/warehouse/policy'
import { approvalIds as id } from './warehouseApprovalFixture'
import { returnQuarantine } from './warehouseReturnFixture'

export const policyLocation = { ...returnQuarantine, id: id.source, code: 'WAREHOUSE-A', name: 'Gudang A' }
export function policyVersionFixture(revision = 7): PolicyVersion {
  return { id: id.policy, revision, currency: 'IDR', expiryHours: 24, warehouseIds: [id.source], actorId: id.requester, createdAt: '2026-09-25T03:00:00Z',
    rules: [{ operation: 'ADJUSTMENT', tiers: [{ minimumMinor: '100', userIds: [id.checker], roleIds: [] }] }] }
}
export function policyDetailsFixture(current: PolicyVersion | null = policyVersionFixture()): PolicyDetails {
  return { configured: current !== null, current, references: { users: current ? [{ id: id.checker, name: 'Pemeriksa gudang' }] : [], roles: [],
    locations: current ? [{ id: id.source, code: policyLocation.code, name: policyLocation.name }] : [] } }
}
