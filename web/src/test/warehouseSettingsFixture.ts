import { approvalIds as id } from './warehouseApprovalFixture'
import { policyDetailsFixture } from './warehousePolicyFixture'
import type { WarehouseDelegation } from '@/api/warehouse/settings'

export function delegationFixture(revision = 3): WarehouseDelegation {
  return { id: id.effect, approverId: id.checker, delegateId: id.other, sourceRoleId: null, locationId: id.source, operation: 'ADJUSTMENT', validFrom: '2026-09-25T03:00:00Z', validUntil: '2026-09-26T03:00:00Z', revokedAt: null, revision }
}
export function delegationViewFixture(revision = 3) {
  return { delegation: delegationFixture(revision), state: 'ACTIVE', approver: { id: id.checker, name: 'Pemeriksa gudang' }, delegate: { id: id.other, name: 'Pemeriksa pengganti' }, sourceRole: null, location: { id: id.source, code: 'WAREHOUSE-A', name: 'Gudang A' } }
}
export function delegationPolicyFixture() {
  const result = policyDetailsFixture()
  result.current!.rules[0].tiers[0].roleIds = [id.target]
  result.references.roles = [{ id: id.target, name: 'Supervisor gudang' }]
  result.references.users.push({ id: id.requester, name: 'Pengatur kebijakan' })
  return result
}
