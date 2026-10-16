import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import type { SiteInspection } from '@/api/network'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { cableAction, deleteAction, relocateAction } from './mapActions'
import { DataTable } from '../DataTable'

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
      {actions.length > 0 && <CommandBar layout="wrap" actions={actions} />}

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
        {site.olts.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">OLT di site ini ({site.olts.length})</p>
            <DataTable rows={site.olts} rowKey={olt => olt.id} presentation="resource" columns={[
              { key: 'name', header: 'Nama', minWidth: 180, cell: olt => olt.name },
              { key: 'code', header: 'Kode', minWidth: 140, cell: olt => olt.code },
              { key: 'vendor', header: 'Vendor', minWidth: 100, cell: olt => olt.vendor },
              { key: 'status', header: 'Status', minWidth: 100, cell: olt => olt.active ? 'Aktif' : 'Nonaktif' },
            ]} />
          </div>
        )}
      </div>
    </aside>
  )
}
