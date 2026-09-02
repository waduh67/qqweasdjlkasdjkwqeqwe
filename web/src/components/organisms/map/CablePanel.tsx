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
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { CableChainNotice, CableCoreManager, ReleaseDropDialog } from '@/components/organisms'
import { CODE_MAX, TYPE_LABEL, formatLength } from '@/map/cableFormat'
import { CableCauses } from './CableCauses'
import { CablePhysicalFields } from './CablePhysicalFields'
import { OtdrSection } from './OtdrSection'
import { deleteAction } from './mapActions'

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
  onRecordOtdr: (form: RecordOtdrTest) => void
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
  if (releasable)
    actions.push({
      key: 'release',
      label: 'Cabut pelanggan',
      icon: <IconPower size={15} />,
      onClick: () => setReleasing(true),
    })
  if (abandoned && canEdit)
    actions.push({ key: 'reuse', label: 'Pakai lagi', icon: <IconCheck size={15} />, onClick: onReuse })
  if (canSimulate)
    actions.push({ key: 'simulate', label: 'Simulasi putus', icon: <IconFlask size={15} />, onClick: onSimulate })
  if (canDelete) actions.push(deleteAction('Hapus', onDelete))

  return (
    <aside className="map-panel blade blade-detail">
      {/* Nama yang memimpin, kode mengekor: nama ditulis sebagai kalimat ("Distribusi
          ODC-01 → ODP-07"), sedangkan kode dipakai saat menyebut ruas ini ke orang
          lain. Keduanya disebut di sini supaya panel yang terbuka dari peta langsung
          cocok dengan kertas yang dipegang teknisi. */}
      <BladeHead title={cable.name} subtitle={`Kabel ${TYPE_LABEL[cable.cableType]} · ${cable.code}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {causes.length > 0 && <CableCauses causes={causes} />}

        {/* Sebelum apa pun yang lain: ruas kotak-ke-kotak wajib menjelaskan
            dirinya — splitter bertingkat atau selubung yang dipecah. Angka
            panjang & core di bawahnya baru bisa dipercaya setelah itu terjawab. */}
        <CableChainNotice cableId={cable.id} fromKind={cable.fromKind} toKind={cable.toKind} />

        {/* Lencana "Ditinggal" saja tak cukup menjelaskan akibatnya: yang perlu
            diketahui orang yang membuka ruas ini adalah kabelnya masih ada di
            tiang, tapi tak boleh direncanakan untuk pelanggan baru. */}
        {abandoned && (
          <MessageBar intent="info">
            <MessageBarBody>
              Kabel ini ditandai ditinggal — seratnya masih tergantung di tiang, tapi tak dihitung sebagai kabel
              siap pakai. {canEdit && 'Pakai “Pakai lagi” bila rumahnya berlangganan kembali.'}
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
              Kode ruas ini masih bergaya UUID — tak bisa disebut lewat radio maupun ditulis di
              label selubung. Ganti jadi sesuatu seperti DIST-ODC-01-ODP-07 lewat “Ubah” di baris
              Kode; sambungan, core, dan riwayat ukurnya tetap utuh.
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
              hint="Yang tertulis di label selubung & disebut lewat radio. Sambungan, core, dan riwayat ukur tak ikut berubah."
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

        <dl className="essentials">
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

        {/* Kisah lengkap sebuah selubung: berangkat dari mana, dibuka di kotak
            mana saja, berhenti di mana. Cuma muncul untuk kabel yang memang
            mampir di tengah jalan — buat kabel dua ujung, "Dari"/"Ke" di atas
            sudah menceritakan semuanya.

            Sengaja tak bisa disunting dari sini: yang berhak bilang "selubung
            ini saya buka" adalah orang yang berdiri di depan kotaknya, dan ia
            mencatatnya di meja sambung kotak itu — bukan dari panel kabel yang
            bisa dibuka siapa saja dari balik meja kantor. */}
        {cable.attachments.length > 2 && (
          <div className="stack" style={{ gap: '0.3rem' }}>
            <Text as="strong" size={300} weight="semibold">Perjalanan selubung</Text>
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
              Angka meter dihitung menyusuri rute dari pangkal — itu yang dicocokkan dengan hasil
              OTDR saat mencari letak gangguan. Perannya diubah dari meja sambung kotaknya.
            </Text>
          </div>
        )}

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

        {/* Core lebih dulu daripada OTDR: inilah yang dibuka orang tiap hari
            ("core mana yang masih bebas buat pasangan besok"), sedangkan OTDR
            cuma dibuka saat ada gangguan. */}
        <div className="stack" style={{ gap: '0.5rem', borderTop: '1px solid var(--line)', paddingTop: '0.6rem' }}>
          <div className="spread">
            <Text as="strong" size={300} weight="semibold">Core kabel</Text>
            <Text as="span" className="muted" size={100}>{cable.coreCount} core</Text>
          </div>
          <CableCoreManager key={coreEpoch} cableId={cable.id} canEdit={canEdit} />
        </div>

        {canViewOtdr && (
          <OtdrSection
            cable={cable}
            tests={otdrTests}
            canRecord={canRecordOtdr}
            onRecord={onRecordOtdr}
            onDelete={onDeleteOtdr}
            onFocus={onFocusOtdr}
          />
        )}
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
    </aside>
  )
}
