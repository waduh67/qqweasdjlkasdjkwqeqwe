import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from './client'
import {
  adoptProvisioningDrift,
  applyProvisioningPlan,
  cancelProvisioningExecution,
  getProvisioningExecution,
  listServiceIntents,
  previewProvisioning,
  revokeAdapterCertification,
} from './provisioning'

afterEach(() => vi.restoreAllMocks())

describe('provisioning API', () => {
  it('mengirim revisi dan kunci idempotensi apply sebagai header', async () => {
    const request = vi.spyOn(api, 'request').mockResolvedValue({
      id: 'execution-1',
      planId: 'plan-1',
      revision: 1,
      status: 'QUEUED',
    })

    await applyProvisioningPlan('plan-1', 7, 'request-1')

    expect(request).toHaveBeenCalledWith('/api/provisioning/plans/plan-1/apply', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'request-1', 'If-Match': '"7"' },
    })
  })

  it('mengirim If-Match untuk cancel, adopsi, dan pencabutan sertifikasi', async () => {
    const request = vi.spyOn(api, 'request')
      .mockResolvedValueOnce({ id: 'execution-1', planId: 'plan-1', revision: 5, status: 'CANCELLED' })
      .mockResolvedValue({})

    await cancelProvisioningExecution('execution-1', 4)
    await adoptProvisioningDrift('drift-1', 5)
    await revokeAdapterCertification('tenant-1', 'cert-1', 2)

    expect(request).toHaveBeenNthCalledWith(1, '/api/provisioning/executions/execution-1/cancel', {
      method: 'POST',
      headers: { 'If-Match': '"4"' },
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/provisioning/drift/drift-1/adopt', {
      method: 'POST',
      headers: { 'If-Match': '"5"' },
    })
    expect(request).toHaveBeenNthCalledWith(
      3,
      '/api/platform/tenants/tenant-1/provisioning/certifications/cert-1/revoke',
      { method: 'POST', headers: { 'If-Match': '"2"' } },
    )
  })

  it('meneruskan AbortSignal pada preview dan daftar intent', async () => {
    const request = vi.spyOn(api, 'request').mockResolvedValue([])
    const controller = new AbortController()

    await previewProvisioning('plan-1', 'DRY_RUN', controller.signal)
    await listServiceIntents(controller.signal)

    expect(request).toHaveBeenNthCalledWith(1, '/api/provisioning/plans/plan-1/preview?mode=DRY_RUN', {
      method: 'POST',
      signal: controller.signal,
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/provisioning/intents', {
      signal: controller.signal,
    })
  })

  it('mem-parse status eksekusi tertutup tanpa menerima konfigurasi mentah', async () => {
    vi.spyOn(api, 'request').mockResolvedValue({
      id: 'execution-1',
      planId: 'plan-1',
      revision: 3,
      status: 'VERIFYING',
      rawConfiguration: 'interface vlan 110',
    })

    await expect(getProvisioningExecution('execution-1')).resolves.toEqual({
      id: 'execution-1',
      planId: 'plan-1',
      revision: 3,
      status: 'VERIFYING',
    })
  })

  it.each([
    'STALE_PLAN',
    'STALE_REVISION',
    'UNCERTIFIED_CAPABILITY',
    'PROTECTED_MANAGEMENT_RESOURCE',
  ])('mempertahankan kode kegagalan terstruktur %s', async (code) => {
    const structured = new ApiError(409, 'Aksi ditolak', undefined, code)
    vi.spyOn(api, 'request').mockRejectedValue(structured)

    const error = await applyProvisioningPlan('plan-1', 7, 'request-1').catch((cause: unknown) => cause)

    expect(error).toBe(structured)
    expect(error).toMatchObject({ status: 409, code })
  })
})
