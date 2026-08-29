import { Text } from '@fluentui/react-components'
import type { JointBoxView } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { JOINT_BOX_COLOR } from '@/map/mapStyle'
import { cableAction, deleteAction, relocateAction } from './mapActions'

/**
 * Panel joint box saat markernya diklik.
 *
 * Kotak sambung tak punya "hilir" yang bisa diringkas seperti ODC/ODP: ia meneruskan
 * apa pun yang lewat, jadi pertanyaan lapangan di sini berbeda — "masih muat tidak?"
 * dan "kabel mana saja yang ketemu di sini?". Yang pertama dijawab bilah kapasitas di
 * bawah; yang kedua lahir sendiri begitu operator menarik kabel dari/ke kotak ini.
 */
export function JointBoxPanel({
  jointBox,
  canView,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  jointBox: JointBoxView
  canView: boolean
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  onOpenDetail: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const primary: CommandAction | undefined = canView
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus', onDelete, jointBox.spliceCount > 0))

  const used = jointBox.capacity > 0 ? Math.min(100, (jointBox.spliceCount / jointBox.capacity) * 100) : 0

  return (
    <aside className="map-panel blade">
      <BladeHead title={jointBox.code} subtitle={`Joint box · ${jointBox.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <dl className="essentials">
          <Ess label="Status">
            <StatusBadge status={jointBox.status} />
          </Ess>
          <Ess label="Tray">{jointBox.trayCount}</Ess>
          <Ess label="Sambungan">
            <span className="tnum">
              {jointBox.spliceCount}/{jointBox.capacity}
            </span>
          </Ess>
          <Ess label="Alamat">{jointBox.address}</Ess>
        </dl>

        {/* Bilah isi kotak: satu-satunya angka yang menentukan boleh-tidaknya sambungan
            berikutnya dikerjakan di sini, jadi ia digambar, bukan cuma ditulis. */}
        <div className="stack" style={{ gap: '0.3rem' }}>
          <div
            style={{
              height: 6,
              borderRadius: 999,
              background: 'var(--surface-3, rgba(255,255,255,0.08))',
              overflow: 'hidden',
            }}
          >
            <div style={{ width: `${used}%`, height: '100%', background: JOINT_BOX_COLOR }} />
          </div>
          <Text as="span" className="muted" size={200}>
            {jointBox.spliceCount >= jointBox.capacity
              ? 'Kotak penuh — sambungan baru harus pindah ke kotak lain.'
              : `Sisa ${jointBox.capacity - jointBox.spliceCount} tempat sambungan.`}
          </Text>
        </div>

        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Di dalam joint box tak ada splitter — serat masuk disambung langsung ke serat keluar,
          jadi ia tak menambah redaman pembagian, hanya redaman sambungan.
        </Text>
        {jointBox.spliceCount > 0 && canDelete && (
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
            Tak bisa dihapus selama masih berisi sambungan — lepas dulu isinya.
          </Text>
        )}
      </div>
    </aside>
  )
}
