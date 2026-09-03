import { useMemo } from 'react'
import { useCan } from '@/auth/useCan'

export const NETWORK_PROVISIONING_VIEW_PERMISSION = 'provisioning.segment.view'
export const NETWORK_PROVISIONING_ROUTE = 'network-provisioning'

export type ProvisioningPermissions = {
  readonly view: boolean
  readonly manage: boolean
  readonly apply: boolean
  readonly cancel: boolean
  readonly adopt: boolean
  readonly certification: boolean
}

export function resolveProvisioningPermissions(
  can: (permission: string) => boolean,
  isPlatformAdmin: boolean,
): ProvisioningPermissions {
  return {
    view: can(NETWORK_PROVISIONING_VIEW_PERMISSION),
    manage: can('provisioning.segment.manage'),
    apply: can('provisioning.execution.apply'),
    cancel: can('provisioning.execution.cancel'),
    adopt: can('provisioning.drift.adopt'),
    certification: isPlatformAdmin && can('provisioning.certification.manage'),
  }
}

export function useProvisioningPermissions(): ProvisioningPermissions {
  const { can, isPlatformAdmin } = useCan()
  return useMemo(
    () => resolveProvisioningPermissions(can, isPlatformAdmin),
    [can, isPlatformAdmin],
  )
}
