import { MessageBar, MessageBarBody, Text } from '@fluentui/react-components'
import type { BlastRadiusView } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { AffectedRow } from './AffectedRow'
import { cableAction, deleteAction, relocateAction } from './mapActions'

/** Panel "kalau ODC ini putus, siapa yang kena" — daftar pelanggan hilir + kesiapan broadcast. */
export function BlastRadiusPanel({
  blast,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  blast: BlastRadiusView
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  /** Kosong = operator tak berizin melihat detail ODC. */
  onOpenDetail?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const withPhone = blast.customers.filter((c) => c.phone).length
  // Panel ini menjawab "siapa yang ikut mati"; identitas & kapasitasnya ada di detail —
  // dibuka sebagai blade di atas peta, sama seperti OLT & pelanggan.
  const primary: CommandAction | undefined = onOpenDetail
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus ODC', onDelete))

  return (
    <aside className="map-panel blade">
      <BladeHead title={blast.code} subtitle={`ODC (FDT) · ${blast.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={blast.energized ? 'warning' : 'error'}>
          <MessageBarBody>
            {blast.energized
              ? `Kalau ODC ini putus, ${blast.customerCount} pelanggan kehilangan layanan.`
              : `ODC tanpa uplink — ${blast.customerCount} pelanggan di hilirnya sudah tak punya jalur.`}
          </MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="Uplink">
            <StatusBadge
              status={blast.energized ? 'ACTIVE' : 'INACTIVE'}
              label={blast.energized ? 'Berenergi' : 'Tanpa uplink'}
            />
          </Ess>
          <Ess label="ODP di hilir">{blast.odpCount}</Ess>
          <Ess label="Pelanggan">{blast.customerCount}</Ess>
          <Ess label="Sudah mati">
            {blast.downCount > 0 && <Text as="span" weight="semibold" style={{ color: 'var(--critical-ink)' }}>{blast.downCount}</Text>}
          </Ess>
          <Ess label="Siap broadcast">{withPhone > 0 && `${withPhone} nomor`}</Ess>
        </dl>

        {blast.customers.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Pelanggan terdampak ({blast.customers.length})</p>
            <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
              {blast.customers.map((c) => (
                <AffectedRow key={c.customerId} c={c} />
              ))}
            </div>
          </div>
        )}
      </div>
    </aside>
  )
}
