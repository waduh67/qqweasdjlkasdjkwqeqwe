import { MessageBar, MessageBarBody, Text } from '@fluentui/react-components'
import type { SiteInspection, SiteOlt } from '@/api/network'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { cableAction, deleteAction, relocateAction } from './mapActions'

/**
 * Panel isi sebuah site/POP: OLT yang berdiri di sini plus rekap seluruh
 * perangkat & pelanggan di hilirnya — "seberapa besar site ini". Menghapus site
 * ditolak server selama masih ada OLT terpasang, jadi tombolnya dikunci lebih dulu.
 */
export function SitePanel({
  site,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onDelete,
  onClose,
}: {
  site: SiteInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  // Server menolak hapus site selama masih ada OLT berdiri di sini, jadi tombolnya
  // dikunci lebih dulu — lebih jujur daripada membiarkan operator kena galat.
  const deleteBlocked = site.oltCount > 0
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus site', onDelete, deleteBlocked))

  return (
    <aside className="map-panel blade">
      <BladeHead title={site.code} subtitle={`Site/POP · ${site.name}`} onClose={onClose} />
      {actions.length > 0 && <CommandBar actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {canDelete && deleteBlocked && (
          <MessageBar intent="info">
            <MessageBarBody>Site tak bisa dihapus selama masih ada {site.oltCount} OLT terpasang.</MessageBarBody>
          </MessageBar>
        )}

        <dl className="essentials">
          <Ess label="Alamat">{site.address}</Ess>
          <Ess label="OLT">{site.oltCount}</Ess>
          <Ess label="ODC">{site.odcCount}</Ess>
          <Ess label="ODP">{site.odpCount}</Ess>
          <Ess label="Pelanggan">{site.customerCount}</Ess>
        </dl>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Seluruh perangkat &amp; pelanggan yang bergantung pada site ini.
        </Text>

        {site.olts.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">OLT di site ini ({site.olts.length})</p>
            {site.olts.map((olt) => (
              <SiteOltRow key={olt.id} olt={olt} />
            ))}
          </div>
        )}
      </div>
    </aside>
  )
}

function SiteOltRow({ olt }: { olt: SiteOlt }) {
  return (
    <div className="spread" style={{ gap: '0.45rem', alignItems: 'center' }}>
      <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flexShrink: 0,
            background: olt.active ? '#34d399' : 'var(--muted)',
          }}
        />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{olt.name}</span>
      </span>
      <Text as="span" className="muted tnum" size={200} style={{ flexShrink: 0 }}>
        {olt.code} · {olt.vendor}
      </Text>
    </div>
  )
}
