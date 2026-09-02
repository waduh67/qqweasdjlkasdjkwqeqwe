import { describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { applyProvisioningPlan, previewProvisioning, revokeAdapterCertification } from './provisioning'

describe('provisioning API', () => {
  it('preserves preview mode and apply revision idempotency', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    await previewProvisioning('plan-1', 'DRY_RUN')
    await applyProvisioningPlan('plan-1', 7, 'request-1')
    expect(post).toHaveBeenNthCalledWith(1, '/api/provisioning/plans/plan-1/preview?mode=DRY_RUN')
    expect(post).toHaveBeenNthCalledWith(2, '/api/provisioning/plans/plan-1/apply', { revision: 7, idempotencyKey: 'request-1' })
  })

  it('uses the platform tenant certification revoke route', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    await revokeAdapterCertification('tenant-1', 'cert-1')
    expect(post).toHaveBeenCalledWith('/api/platform/tenants/tenant-1/provisioning/certifications/cert-1/revoke')
  })
})
