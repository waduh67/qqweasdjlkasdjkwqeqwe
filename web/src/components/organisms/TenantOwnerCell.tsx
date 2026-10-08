import type { PlatformTenant } from '@/api/tenant'
import { Button, StatusBadge } from '@/components/atoms'

export function TenantOwnerCell({ tenant, editable, onSelect }: {
  readonly tenant: PlatformTenant
  readonly editable: boolean
  readonly onSelect: (tenant: PlatformTenant) => void
}) {
  if (tenant.slug === 'platform') return <span className="muted">Akun sistem</span>
  return <div className="tenant-owner-cell">
    {editable ? <Button aria-label={'Kelola owner ' + tenant.name} onClick={() => onSelect(tenant)}>
      {tenant.owner?.name ?? 'Tentukan owner'}
    </Button> : <span>{tenant.owner?.name ?? 'Belum ditentukan'}</span>}
    {tenant.owner && <>
      <span className="muted">{tenant.owner.email}</span>
      <StatusBadge status={tenant.owner.status} />
    </>}
  </div>
}
