import type { OdfView } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { ODF_COLOR } from '@/map/mapStyle'
import { uplinkLabel } from '@/utils/odfUplinks'
import { cableAction, deleteAction, relocateAction } from './mapActions'

/**
 * Panel ODF saat markernya diklik.
 *
 * Pertanyaan lapangan di depan sebuah rak cuma satu: "masih ada adapter kosong buat
 * kabel yang baru datang?". Karena itu yang digambar port TERPAKAI, bukan jumlah
 * sambungan — satu port memuat dua sambungan (belakang ke core kabel luar, depan ke
 * patchcord OLT), jadi bilah berbasis sambungan akan berbohong dua kali lipat penuh.
 */
export function OdfPanel({
  odf,
  canView,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  odf: OdfView
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
  if (canDelete) actions.push(deleteAction('Hapus', onDelete, odf.spliceCount > 0))

  const used = odf.portCount > 0 ? Math.min(100, (odf.usedPortCount / odf.portCount) * 100) : 0
  const free = Math.max(0, odf.portCount - odf.usedPortCount)

  return (
    <aside className="map-panel blade">
      <BladeHead title={odf.code} subtitle={`ODF · ${odf.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <dl className="essentials">
          <Ess label="Status">
            <StatusBadge status={odf.status} />
          </Ess>
          <Ess label="POP">{odf.siteName}</Ess>
          {/* Dibaca dari patchcord, bukan diketik — makanya boleh lebih dari satu
              dan boleh kosong. Rak yang belum dicolok apa pun mengaku belum tahu
              ketimbang menebak OLT satu-satunya di POP ini. */}
          <Ess label="OLT terkait">
            {odf.olts.length === 0 ? (
              <span className="muted">belum ada patchcord tercatat</span>
            ) : (
              <span className="stack" style={{ gap: '0.1rem' }}>
                {odf.olts.map((o) => (
                  <span key={o.oltId}>{uplinkLabel(o)}</span>
                ))}
              </span>
            )}
          </Ess>
          <Ess label="Port terpakai">
            <span className="tnum">
              {odf.usedPortCount}/{odf.portCount}
            </span>
          </Ess>
          <Ess label="Sambungan">
            <span className="tnum">{odf.spliceCount}</span>
          </Ess>
        </dl>

        {/* Bilah adapter kosong: angka yang menentukan boleh-tidaknya kabel berikutnya
            diterima di rak ini, jadi ia digambar, bukan cuma ditulis. */}
        <div className="stack" style={{ gap: '0.3rem' }}>
          <div
            style={{
              height: 6,
              borderRadius: 999,
              background: 'var(--surface-3, rgba(255,255,255,0.08))',
              overflow: 'hidden',
            }}
          >
            <div style={{ width: `${used}%`, height: '100%', background: ODF_COLOR }} />
          </div>
          <span className="muted" style={{ fontSize: '0.78rem' }}>
            {free === 0
              ? 'Rak penuh — kabel berikutnya butuh rak atau panel tambahan.'
              : `Sisa ${free} port kosong.`}
          </span>
        </div>

        <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
          Kabel luar berhenti di sini: seratnya dilas ke pigtail di sisi BELAKANG port, lalu
          patchcord dari sisi DEPAN-nya yang mencolok ke port PON. Patchcord itu sambungan,
          bukan kabel bergeometri — ia tak digambar di peta.
        </p>
        {odf.spliceCount > 0 && canDelete && (
          <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
            Tak bisa dihapus selama masih ada sambungan di dalamnya — lepas dulu isinya.
          </p>
        )}
      </div>
    </aside>
  )
}
