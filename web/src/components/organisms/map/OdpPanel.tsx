import { MessageBar, MessageBarBody, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { onuStatusLabel, type OdpInspection } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { HEALTH_COLOR } from '@/map/mapStyle'
import { cableAction, deleteAction, relocateAction } from './mapActions'

/** Panel jawaban atas pertanyaan lapangan: "di ODP ini ada siapa saja, port mana yang kosong?" */
export function OdpPanel({
  inspection,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  inspection: OdpInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  /** Kosong = operator tak berizin melihat detail ODP. */
  onOpenDetail?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const { upstream } = inspection
  // Sama seperti site: server menolak hapus ODP yang masih dihuni, jadi dikunci di sini.
  const deleteBlocked = inspection.occupants.length > 0
  // Panel ini soal port & penghuni; identitas, kapasitas, dan suntingnya ada di detail.
  const primary: CommandAction | undefined = onOpenDetail
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus ODP', onDelete, deleteBlocked))

  return (
    <aside className="map-panel blade">
      <BladeHead title={inspection.code} subtitle={`ODP (FAT) · ${inspection.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {!upstream.complete && (
          <MessageBar intent="warning">
            <MessageBarBody>Jalur hulu belum lengkap — ODP ini belum tersambung utuh sampai OLT.</MessageBarBody>
          </MessageBar>
        )}
        {canDelete && deleteBlocked && (
          <MessageBar intent="info">
            <MessageBarBody>
              ODP tak bisa dihapus selama masih ada {inspection.occupants.length} pelanggan tersambung.
            </MessageBarBody>
          </MessageBar>
        )}

        <div className="stack" style={{ gap: '0.35rem' }}>
          <div className="spread">
            <Text as="span" size={200}>
              {inspection.usedPorts}/{inspection.capacity} port terpakai
            </Text>
            <Text as="span" className="tnum" size={200} weight="semibold">
              {inspection.utilizationPercent}%
            </Text>
          </div>
          <div className="meter">
            <div
              className={`meter-fill ${
                inspection.utilizationPercent >= 90 ? 'crit' : inspection.utilizationPercent >= 70 ? 'warn' : ''
              }`}
              style={{ width: `${inspection.utilizationPercent}%` }}
            />
          </div>
        </div>

        <dl className="essentials">
          <Ess label="Port kosong">
            {inspection.availablePortNumbers.length > 0 ? (
              <span className="tnum">{inspection.availablePortNumbers.join(', ')}</span>
            ) : (
              <span className="muted">Penuh</span>
            )}
          </Ess>
          <Ess label="ODC induk">{upstream.odcCode}</Ess>
          <Ess label="PON">{upstream.ponPortLabel}</Ess>
          <Ess label="OLT">{upstream.oltCode}</Ess>
          <Ess label="Site">{upstream.siteCode}</Ess>
          <Ess label="Rugi splitter">
            <span className="tnum">{upstream.splitterLossDb.toFixed(1)} dB</span>
          </Ess>
        </dl>

        <div className="stack" style={{ gap: '0.45rem' }}>
          <p className="blade-section-title">Pelanggan ({inspection.occupants.length})</p>
          {inspection.occupants.length === 0 ? (
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              Belum ada pelanggan tersambung.
            </Text>
          ) : (
            <Table><TableHeader><TableRow ><TableHeaderCell >Port</TableHeaderCell>
            <TableHeaderCell >Pelanggan</TableHeaderCell>
            <TableHeaderCell >ONU</TableHeaderCell>
            <TableHeaderCell >Optik</TableHeaderCell></TableRow></TableHeader>
            <TableBody>{inspection.occupants.map((occupant) => (
              <TableRow key={occupant.portNumber}><TableCell className="tnum">{occupant.portNumber}</TableCell>
              <TableCell >{occupant.customerName}
              <br />
              <Text as="span" className="muted" size={200}>
                {occupant.phone ?? occupant.customerCode}
              </Text></TableCell>
              <TableCell ><Text as="span" className="muted tnum" size={200}>
                {occupant.onuSerialNumber}
              </Text>
              <br />
              <StatusBadge status={occupant.onuStatus} label={onuStatusLabel(occupant.onuStatus)} /></TableCell>
              <TableCell ><Text as="span" className="tnum" weight="semibold" style={{ color: HEALTH_COLOR[occupant.opticalHealth] }}>
                {occupant.installRxPowerDbm != null ? `${occupant.installRxPowerDbm} dBm` : occupant.opticalHealth}
              </Text></TableCell></TableRow>
            ))}</TableBody></Table>
          )}
        </div>
      </div>
    </aside>
  )
}
