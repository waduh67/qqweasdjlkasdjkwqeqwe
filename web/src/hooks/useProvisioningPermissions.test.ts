import { describe, expect, it } from 'vitest'
import {
  NETWORK_PROVISIONING_ROUTE,
  NETWORK_PROVISIONING_VIEW_PERMISSION,
  resolveProvisioningPermissions,
} from './useProvisioningPermissions'

describe('izin provisioning', () => {
  it('menggunakan izin view yang sama untuk gerbang rute', () => {
    expect(`/${NETWORK_PROVISIONING_ROUTE}`).toBe('/network-provisioning')
    expect(NETWORK_PROVISIONING_VIEW_PERMISSION).toBe('provisioning.segment.view')
  })

  it('memodelkan setiap kemampuan mutasi secara independen', () => {
    const granted = new Set([
      'provisioning.segment.view',
      'provisioning.execution.apply',
      'provisioning.drift.adopt',
    ])
    const permissions = resolveProvisioningPermissions((permission) => granted.has(permission), false)

    expect(permissions).toEqual({
      view: true,
      manage: false,
      plan: false,
      apply: true,
      cancel: false,
      drift: false,
      adopt: true,
      certification: false,
    })
  })

  it('membatasi sertifikasi pada platform admin', () => {
    const can = () => true
    expect(resolveProvisioningPermissions(can, false).certification).toBe(false)
    expect(resolveProvisioningPermissions(can, true).certification).toBe(true)
  })
})
