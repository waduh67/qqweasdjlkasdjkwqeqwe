import { Text } from '@fluentui/react-components'
import type {
  ProvisioningTopology,
  RevisionedResource,
  SegmentProfileView,
  ServiceIntentView,
  VlanPoolView,
} from '@/api/provisioning'
import { Badge, EmptyState, StatusBadge } from '@/components/atoms'

type ResourceProps = {
  readonly topology: ProvisioningTopology
  readonly pools: readonly RevisionedResource<VlanPoolView>[]
  readonly profiles: readonly RevisionedResource<SegmentProfileView>[]
  readonly intents: readonly RevisionedResource<ServiceIntentView>[]
}

export function TopologyPanel({ topology }: Pick<ResourceProps, 'topology'>) {
  if (topology.nodes.length === 0) return <EmptyState title="Topologi provisioning belum tersedia" hint="Tambahkan node dan tautan sebelum membuat intent layanan." />
  return (
    <section className="workspace-grid" aria-labelledby="topology-title">
      <div className="card stack">
        <div className="spread wrap"><Text as="h2" size={400} weight="semibold" id="topology-title">Jalur layanan</Text><Badge tone="accent">{topology.links.length} tautan</Badge></div>
        <ol className="workspace-path" aria-label="Urutan jalur layanan">
          {topology.nodes.map((node, index) => (
            <li key={node.id}>
              <span className="workspace-step-index" aria-hidden>{index + 1}</span>
              <div className="grow min-w-0">
                <Text as="strong" block weight="semibold">{node.name}</Text>
                <Text as="span" className="muted" size={200}>{node.role}</Text>
              </div>
              <StatusBadge status={node.administrativeStatus} label={statusLabel(node.administrativeStatus)} />
            </li>
          ))}
        </ol>
      </div>
      <div className="card stack">
        <Text as="h2" size={400} weight="semibold">Port akses</Text>
        {topology.interfaces.map((networkInterface) => (
          <div className="workspace-list-row" key={networkInterface.id}>
            <div><Text as="strong" block weight="semibold">{networkInterface.name}</Text><Text as="span" className="muted" size={200}>{networkInterface.role}</Text></div>
            <StatusBadge status={networkInterface.administrativeStatus} label={statusLabel(networkInterface.administrativeStatus)} />
          </div>
        ))}
      </div>
    </section>
  )
}

export function ProfilesPanel({ pools, profiles }: Pick<ResourceProps, 'pools' | 'profiles'>) {
  const poolById = new Map(pools.map((pool) => [pool.value.id, pool.value]))
  return (
    <section className="workspace-grid" aria-label="Profil segmen dan pool VLAN">
      {profiles.map(({ revision, value }) => {
        const pool = poolById.get(value.poolId)
        return (
          <article className="card stack" key={value.id}>
            <div className="spread wrap"><Text as="h2" size={400} weight="semibold">{value.name}</Text><Badge>Revisi {revision}</Badge></div>
            <div className="workspace-kv"><span>Pool VLAN</span><strong>{pool?.name ?? value.poolId}</strong></div>
            {pool && <div className="workspace-kv"><span>Rentang</span><strong>{pool.range.start}–{pool.range.endInclusive}</strong></div>}
            <Text as="span" className="muted" size={200}>Profil menggunakan pool VLAN tervalidasi server.</Text>
          </article>
        )
      })}
    </section>
  )
}

export function IntentsPanel({ intents, profiles, canOperate = false, onSuspend, onRestore, onDeprovision }: Pick<ResourceProps, 'intents' | 'profiles'> & {
  readonly canOperate?: boolean
  readonly onSuspend?: (intent: RevisionedResource<ServiceIntentView>) => void
  readonly onRestore?: (intent: RevisionedResource<ServiceIntentView>) => void
  readonly onDeprovision?: (intent: RevisionedResource<ServiceIntentView>) => void
}) {
  const profileById = new Map(profiles.map((profile) => [profile.value.id, profile.value]))
  return (
    <section className="workspace-grid" aria-label="Intent layanan">
      {intents.map(({ revision, value }) => {
        const profileName = profileById.get(value.segmentProfileId)?.name ?? 'Profil tidak dikenal'
        const dedicated = value.allocationMode === 'DEDICATED'
        return (
          <article className="card stack" key={value.id}>
            <div className="spread wrap">
              <div className="workspace-title-group"><Text as="h2" size={400} weight="semibold">{profileName}</Text><Text as="span" className="muted" size={200}>Langganan {value.subscriptionId}</Text></div>
              <StatusBadge status={value.status} label={statusLabel(value.status)} />
            </div>
            <div className="row wrap"><Badge tone={dedicated ? 'accent' : 'neutral'}>{dedicated ? 'Enterprise dedicated' : 'Residential shared'}</Badge><Badge>Revisi {revision}</Badge></div>
            {dedicated && <div className="workspace-kv"><span>VLAN dedicated</span><strong>{value.dedicatedVlanId ?? 'Dipilih server'}</strong></div>}
            {canOperate && <div className="row wrap">
              {value.status === 'ACTIVE' && <button type="button" className="ghost small" onClick={() => onSuspend?.({ revision, value })}>Tangguhkan</button>}
              {value.status === 'SUSPENDED' && <button type="button" className="ghost small" onClick={() => onRestore?.({ revision, value })}>Pulihkan</button>}
              {value.status !== 'DECOMMISSIONED' && <button type="button" className="danger small" onClick={() => onDeprovision?.({ revision, value })}>Deprovision</button>}
            </div>}
          </article>
        )
      })}
    </section>
  )
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = { ENABLED: 'Aktif', DISABLED: 'Nonaktif', EXCLUDED: 'Dikecualikan', DRAFT: 'Draf', ACTIVE: 'Aktif', SUSPENDED: 'Ditangguhkan', DECOMMISSIONED: 'Dihentikan' }
  return labels[status] ?? status
}
