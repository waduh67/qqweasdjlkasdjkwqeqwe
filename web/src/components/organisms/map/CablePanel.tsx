import { useState } from 'react'
import { MessageBar, MessageBarBody, Text } from '@fluentui/react-components'
import type {
  CableInstallation,
  CableOwnership,
  CableView,
  ImpactCause,
  OtdrTest,
  RecordOtdrTest,
} from '@/api/network'
import { Button, StatusBadge, TextField } from '@/components/atoms'
import { IconCheck, IconFlask, IconPower, IconRoute } from '@/components/atoms/icons'
import { CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { Blade, CableChainNotice, CableCoreManager, ReleaseDropDialog } from '@/components/organisms'
import { CODE_MAX, TYPE_LABEL, formatLength } from '@/map/cableFormat'
import { CableCauses } from './CableCauses'
import { CablePhysicalFields } from './CablePhysicalFields'
import { OtdrSection } from './OtdrSection'
import { deleteAction } from './mapActions'
import { NetworkResourceSections } from '../NetworkResourceSections'
import * as NavIcons from '@/components/molecules/navigationIcons'

export function CablePanel({
  cable,
  causes,
  canEdit,
  canDelete,
  canSimulate,
  canReleaseDrop,
  canViewOtdr,
  canRecordOtdr,
  otdrTests,
  onRecordOtdr,
  onDeleteOtdr,
  onFocusOtdr,
  onEdit,
  onDelete,
  onSimulate,
  onReleased,
  onReuse,
  onPhysicalChange,
  onRename,
  onClose,
}: {
  cable: CableView
  causes: ImpactCause[]
  canEdit: boolean
  canDelete: boolean
  canSimulate: boolean
  canReleaseDrop: boolean
  canViewOtdr: boolean
  canRecordOtdr: boolean
  otdrTests: OtdrTest[] | null
  onRecordOtdr: (form: RecordOtdrTest) => void | Promise<void>
  onDeleteOtdr: (testId: string) => void
  onFocusOtdr: (test: OtdrTest) => void
  onEdit: () => void
  onDelete: () => void
  onSimulate: () => void
  /** Pelanggan sudah dicabut — panel & peta perlu membaca ulang keadaannya. */
  onReleased: () => void
  /** Kabel yang tadinya ditinggal dinyatakan siap pakai lagi. */
  onReuse: () => void
  /** Simpan seketika; hanya bidang yang disebut yang berubah. */
  onPhysicalChange: (patch: { installation?: CableInstallation | null; ownership?: CableOwnership }) => void
  /** Ganti kode di label selubung — jalur perapian kabel lama yang kodenya UUID. */
  onRename: (code: string) => void
  onClose: () => void
}) {
  // Hanya drop yang punya tombol ini: ruas distribusi menyuapi banyak rumah, dan
  // "lepas semua" di sana adalah pemadaman satu klaster dengan satu klik. Server
  // menolaknya juga — tapi tombol yang tak pernah boleh ditekan lebih baik tak
  // ditawarkan sejak awal.
  const abandoned = cable.status === 'ABANDONED'
  const releasable = canReleaseDrop && cable.cableType === 'DROP' && !abandoned
  const [releasing, setReleasing] = useState(false)
  // Null = tak sedang diganti. Kode disunting di tempat, bukan lewat "Edit jalur":
  // merapikan label tak semestinya menyeret rute yang sudah benar ke dalam risiko
  // tergeser satu titik.
  const [renaming, setRenaming] = useState<string | null>(null)
  // Kabel dari versi lama: kodenya UUID hasil generate, mustahil dieja lewat radio.
  const legacyCode = /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(cable.code)
  // Membebaskan core mengubah isi "Kelola core" yang memuat dirinya sendiri;
  // menaikkan angka ini memaksanya membaca ulang dari server.
  const [coreEpoch, setCoreEpoch] = useState(0)

  const primary: CommandAction | undefined = canEdit
    ? { key: 'edit', label: 'Edit jalur', icon: <IconRoute size={15} />, onClick: onEdit }
    : undefined
  const actions: CommandAction[] = []
  if (canSimulate)
    actions.push({ key: 'simulate', label: 'Simulasi putus', icon: <IconFlask size={15} />, onClick: onSimulate })
  if (releasable)
    actions.push({
      key: 'release',
      label: 'Cabut pelanggan',
      icon: <IconPower size={15} />,
      onClick: () => setReleasing(true),
    })
  if (abandoned && canEdit)
    actions.push({ key: 'reuse', label: 'Pakai lagi', icon: <IconCheck size={15} />, onClick: onReuse })
  if (canDelete) actions.push(deleteAction('Hapus', onDelete))

  return (
    <Blade
      open={true}
      title={cable.name}
      subtitle={`Kabel ${TYPE_LABEL[cable.cableType]} · ${cable.code}`}
      size="full"
      className="blade-detail"
      layout="resource"
      onClose={onClose}
    >
      <div className="network-resource">
      {(primary || actions.length > 0) && <CommandBar layout="wrap" primary={primary} actions={actions} />}
      <NetworkResourceSections label="Bagian kabel" sections={[{ key: 'overview', label: 'Ringkasan', icon: NavIcons.Dashboard, content: <div className="stack">
        {causes.length > 0 && <CableCauses causes={causes} />}

        {/* Lencana "Ditinggal" saja tak cukup menjelaskan akibatnya: yang perlu
            diketahui orang yang membuka ruas ini adalah kabelnya masih ada di
            tiang, tapi tak boleh direncanakan untuk pelanggan baru. */}
        {abandoned && (
          <MessageBar intent="info">
            <MessageBarBody>
              Kabel ini belum tersedia untuk digunakan kembali.
            </MessageBarBody>
          </MessageBar>
        )}


        {/* Kabel warisan: kodenya UUID, dan itu bukan sekadar jelek dipandang —
            tak ada yang sanggup mengejanya lewat radio ke teknisi di tiang. Yang
            berhak menyunting ditawari merapikannya di tempat, tanpa menggambar
            ulang ruasnya (yang berarti membuang meja sambung & riwayat OTDR-nya). */}
        {legacyCode && canEdit && renaming == null && (
          <MessageBar intent="warning">
            <MessageBarBody>
              Ganti kode kabel agar mudah dikenali.
            </MessageBarBody>
          </MessageBar>
        )}

        {/* Penyuntingan kode dibentangkan penuh, di luar daftar ringkas: kode bisa
            sepanjang 40 karakter dan kolom nilai di daftar itu cuma selebar dua
            kata — mengetik di sana berarti mengetik yang tak terbaca. */}
        {renaming != null && (
          <div className="stack" style={{ gap: '0.4rem' }}>
            <TextField
              label="Kode kabel"
              value={renaming}
              maxLength={CODE_MAX}
              hint="Kode pada label kabel."
              onChange={(_, data) => setRenaming(data.value)}
            />
            <div className="row" style={{ gap: '0.4rem' }}>
              <Button
                variant="primary"
                size="small"
                onClick={() => {
                  onRename(renaming)
                  setRenaming(null)
                }}
              >
                Simpan kode
              </Button>
              <Button variant="subtle" size="small" onClick={() => setRenaming(null)}>
                Batal
              </Button>
            </div>
          </div>
        )}

        <dl className="network-properties">
          <Ess label="Kode">
            <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
              <span>{cable.code}</span>
              {canEdit && renaming == null && (
                <Button variant="subtle" size="small" onClick={() => setRenaming(cable.code)}>
                  Ubah
                </Button>
              )}
            </span>
          </Ess>
          <Ess label="Status">
            <StatusBadge status={cable.status} />
          </Ess>
          <Ess label="Jenis">{TYPE_LABEL[cable.cableType]}</Ess>
          <Ess label="Jumlah core">{cable.coreCount}</Ess>
          <Ess label="Panjang">
            <span className="tnum">{formatLength(cable.lengthMeters)}</span>
          </Ess>
          <Ess label="Dari">
            {cable.fromKind}
            {cable.fromPortLabel && <span className="muted"> · {cable.fromPortLabel}</span>}
          </Ess>
          <Ess label="Ke">
            {cable.toKind}
            {cable.toPortNumber != null && <span className="muted"> · port {cable.toPortNumber}</span>}
          </Ess>
          <Ess label="Titik jalur">{cable.route.points.length}</Ess>
          {!canEdit && (
            <>
              <Ess label="Cara pasang">
                {cable.installationLabel ?? <span className="muted">Belum disurvei</span>}
              </Ess>
              <Ess label="Kepemilikan">{cable.ownershipLabel}</Ess>
            </>
          )}
        </dl>

        {/* Yang berhak mengubah langsung dapat dropdown-nya — hasil survei sering
            baru masuk berhari-hari setelah jalurnya digambar, dan tersimpan
            begitu dipilih tanpa tombol simpan terpisah. */}
        {canEdit && (
          <CablePhysicalFields
            installation={cable.installation ?? ''}
            ownership={cable.ownership}
            onInstallation={(installation) => onPhysicalChange({ installation })}
            onOwnership={(ownership) => onPhysicalChange({ ownership })}
          />
        )}

      </div> },
      { key: 'cores', label: 'Core kabel', group: 'Jaringan', icon: NavIcons.Cable, content: <CableCoreManager key={coreEpoch} cableId={cable.id} canEdit={canEdit} /> },
      { key: 'route', label: 'Jalur kabel', group: 'Jaringan', icon: NavIcons.Route, content: <div className="stack">
        <CableChainNotice cableId={cable.id} fromKind={cable.fromKind} toKind={cable.toKind} />
        {cable.attachments.length > 0 && (
          <div className="stack" style={{ gap: '0.3rem' }}>
            <Text as="strong" size={300} weight="semibold">Titik pada jalur kabel</Text>
            <ol className="stack" style={{ gap: '0.2rem', margin: 0, paddingLeft: '1.1rem' }}>
              {cable.attachments.map((stop) => (
                <li key={stop.id}>
                  <Text as="span" size={200}>{stop.nodeCode ?? stop.nodeKind}</Text>
                  <span className="muted"> · {stop.roleLabel}</span>
                  {stop.distanceMeters != null && stop.distanceMeters > 0 && (
                    <span className="muted tnum"> · m-{Math.round(stop.distanceMeters)}</span>
                  )}
                </li>
              ))}
            </ol>
            <Text as="span" className="muted" size={100}>
              Jarak diukur dari pangkal kabel.
            </Text>
          </div>
        )}

      </div> },
        ...(canViewOtdr ? [{ key: 'otdr', label: 'OTDR', group: 'Pemantauan', icon: NavIcons.Flask, content: (
          <OtdrSection
            cable={cable}
            tests={otdrTests}
            canRecord={canRecordOtdr}
            onRecord={onRecordOtdr}
            onDelete={onDeleteOtdr}
            onFocus={onFocusOtdr}
          />
        ) }] : []),
      ]} />
      </div>

      {releasing && (
        <ReleaseDropDialog
          cable={cable}
          onClose={() => setReleasing(false)}
          onDone={() => {
            setCoreEpoch((n) => n + 1)
            onReleased()
          }}
        />
      )}
    </Blade>
  )
}
