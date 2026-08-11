import { useCallback, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type { AssetStatus, Coordinate, JointBoxView, OdcView, OdpView } from '@/api/network'
import { SPLITTER_RATIOS } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Spinner, StatusBadge, TextField } from '@/components/atoms'
import { IconInventory, IconMap } from '@/components/atoms/icons'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { mapFocusState, type MapFocusState } from '@/map/mapFocus'
import { Blade } from './Blade'
import { LocationPicker } from './LocationPicker'
import { SplitterPanel } from './SplitterPanel'

const STATUS_OPTIONS: { value: AssetStatus; label: string }[] = [
  { value: 'PLANNED', label: 'Rencana' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'MAINTENANCE', label: 'Perawatan' },
  { value: 'INACTIVE', label: 'Nonaktif' },
]

/** Satu sel info berlabel di panel detail (pola `.stat` yang sama dipakai detail OLT). */
function DetailField({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div style={{ fontSize: '0.9rem', color: 'var(--text-2)', wordBreak: 'break-word' }}>{value}</div>
    </div>
  )
}

/**
 * Panel detail read-only sebuah aset jaringan di dalam blade lebar (`blade-detail`) —
 * kembar perilaku dengan detail OLT: klik baris membuka panel ini, tombol Edit membuka
 * drawer sunting yang lebih sempit (`blade-edit`) DI ATAS-nya (non-modal, tak menutup
 * penuh). Sengaja read-only: semua perubahan—termasuk lokasi—lewat drawer Edit yang
 * sudah punya peta pemilih, jadi detail = baca, edit = tulis (tanpa duplikasi form).
 * Presentasional murni: `badges`/`fields` datang dari pemanggil.
 */
export function AssetDetailPanel({
  badges,
  subtitle,
  fields,
  address,
  location,
  canUpdate,
  canDelete,
  onEdit,
  onDelete,
  onShowOnMap,
  children,
}: {
  badges: ReactNode
  subtitle?: string
  fields: Array<{ label: string; value: string }>
  address?: string | null
  location: Coordinate
  canUpdate: boolean
  canDelete: boolean
  /** Kosongkan bila aset ini memang tak bisa disunting dari sini (mis. site). */
  onEdit?: () => void
  onDelete: () => void
  /** Kosongkan bila operator tak berizin membuka peta. */
  onShowOnMap?: () => void
  /**
   * Kartu khas aset ini — mis. isi kabinet untuk ODC/ODP. Ditaruh SETELAH
   * "Informasi" dan sebelum "Lokasi": isi kotaknya lebih sering dicari daripada
   * koordinatnya, yang toh sudah kelihatan di peta.
   */
  children?: ReactNode
}) {
  // Aksi tingkat-aset duduk di command bar blade, sejajar dengan detail pelanggan:
  // satu tempat yang sama untuk "apa yang bisa kulakukan pada benda ini".
  const commands: CommandAction[] = []
  if (onShowOnMap)
    commands.push({ key: 'map', label: 'Lihat di peta', icon: <IconMap size={16} />, onClick: onShowOnMap })
  if (canUpdate && onEdit)
    commands.push({ key: 'edit', label: 'Edit', icon: <Pencil size={16} />, onClick: onEdit, dividerBefore: commands.length > 0 })
  if (canDelete)
    commands.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: onDelete, dividerBefore: commands.length > 0 })

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="stack" style={{ gap: '0.35rem' }}>
        <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>{badges}</div>
        {subtitle && <p className="page-sub" style={{ margin: 0 }}>{subtitle}</p>}
      </div>

      {commands.length > 0 && <CommandBar actions={commands} />}

      <div className="card stack">
        <h3 style={{ margin: 0 }}>Informasi</h3>
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
          {fields.map((f) => (
            <DetailField key={f.label} label={f.label} value={f.value} />
          ))}
        </div>
        {address && <DetailField label="Alamat" value={address} />}
      </div>

      {children}

      <div className="card stack">
        <h3 style={{ margin: 0 }}>Lokasi</h3>
        <p className="muted tnum" style={{ margin: 0, fontSize: '0.85rem' }}>
          {location.latitude.toFixed(6)}, {location.longitude.toFixed(6)}
        </p>
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          {onEdit ? 'Ubah identitas & lokasi lewat tombol Edit.' : 'Lihat penempatannya lewat tombol Lihat di peta.'}
        </p>
      </div>
    </div>
  )
}

/**
 * Simpul jaringan berkotak yang detailnya dibaca lewat panel ini: ODC (distribusi),
 * ODP (terminasi), dan joint box (kotak sambung di tengah jalur).
 *
 * Nilainya sengaja sama persis dengan nama layer petanya — dengan begitu ia bisa
 * dioper langsung ke [mapFocusState] tanpa tabel penerjemah yang gampang meleset.
 */
export type AccessNodeKind = 'odc' | 'odp' | 'joint_box'

/**
 * Perbedaan ketiganya tinggal kata-katanya: bentuk datanya (kode, nama, alamat,
 * kapasitas, status, titik) identik, jadi satu komponen melayani semuanya dan tabel
 * inilah yang menampung selisihnya. Satu selisih yang BUKAN sekadar kata:
 * [hasSplitter] — joint box tak berisi splitter, isinya cuma tray dan sambungan
 * serat ke serat, jadi di formnya rasio splitter berganti jumlah tray.
 */
const KIND = {
  odc: {
    label: 'ODC',
    path: 'odcs',
    updatePerm: 'network.odc.update',
    deletePerm: 'network.odc.delete',
    capacityLabel: 'Kapasitas',
    hasSplitter: true,
    editHint: 'Ubah identitas, kapasitas & status ODC. Uplink diatur di peta lewat kabel.',
  },
  odp: {
    label: 'ODP',
    path: 'odps',
    updatePerm: 'network.odp.update',
    deletePerm: 'network.odp.delete',
    capacityLabel: 'Jumlah port',
    hasSplitter: true,
    editHint: 'Ubah identitas, kapasitas & status ODP. ODC induk diatur di peta lewat kabel.',
  },
  joint_box: {
    label: 'Joint box',
    path: 'joint-boxes',
    updatePerm: 'network.jointbox.update',
    deletePerm: 'network.jointbox.delete',
    capacityLabel: 'Kapasitas sambungan',
    hasSplitter: false,
    editHint: 'Ubah identitas, jumlah tray & kapasitas. Kabel yang masuk-keluar diatur di peta.',
  },
} as const

/** Bentuk formulir sunting — semua string karena datang dari input. */
interface NodeDraft {
  code: string
  name: string
  address: string
  longitude: string
  latitude: string
  /** Rasio modul TUNGGAL kabinet; kosong = tanpa splitter, dan itu bentuk yang sah. */
  splitterRatio: string
  /** Berapa modul yang ada sekarang — penentu apakah isian di atas masih mewakili isi kabinet. */
  splitterCount: number
  /** Hanya dipakai joint box; simpul bersplitter mengirimnya sebagai 0. */
  trayCount: string
  capacity: string
  status: AssetStatus
}

type NodeView = OdcView | OdpView | JointBoxView

/**
 * Isi splitter sebuah kabinet dalam satu kalimat. "1:8" saja menyesatkan begitu
 * kabinet berisi lebih dari satu modul, dan kabinet TANPA splitter bukan data
 * yang kurang — itu ODC cross-connect yang cuma meneruskan serat.
 */
function describeSplitter(node: OdcView | OdpView): string {
  if (node.splitterCount === 0) return 'Tanpa splitter (cross-connect)'
  const legs = `${node.splitterLegs} kaki`
  return node.splitterCount === 1
    ? `${node.splitterRatio} · ${legs}`
    : `${node.splitterRatio} · ${node.splitterCount} modul, ${legs}`
}

function toDraft(node: NodeView): NodeDraft {
  const splitterCount = 'splitterCount' in node ? node.splitterCount : 0
  return {
    code: node.code,
    name: node.name,
    address: node.address ?? '',
    longitude: String(node.location.longitude),
    latitude: String(node.location.latitude),
    // Ringkasan hanya sama dengan rasio saat modulnya PERSIS satu; kabinet berisi
    // banyak modul memang tak bisa diwakili satu isian, jadi isiannya dikosongkan
    // dan formnya menyerahkan urusan itu ke panel "Isi kabinet".
    splitterRatio: splitterCount === 1 ? (node as OdcView | OdpView).splitterRatio : '',
    splitterCount,
    trayCount: 'trayCount' in node ? String(node.trayCount) : '',
    capacity: String(node.capacity),
    status: node.status,
  }
}

/**
 * Detail satu ODC/ODP — SATU implementasi yang dipakai di mana pun simpul itu dibuka.
 *
 * Memuat sendiri lewat `GET /api/{odcs|odps}/{id}` dan mengurus sunting & hapusnya,
 * jadi pemanggil cukup menyodorkan id: daftar Inventory punya viewnya, panel peta cuma
 * punya id — keduanya tetap melihat panel yang sama persis. Pola & kontraknya kembar
 * dengan [OltDetail]; lihat juga [CustomerDetailBlade].
 */
export function AccessNodeDetail({
  kind,
  nodeId,
  onChanged,
  onDeleted,
  onShowOnMap,
}: {
  kind: AccessNodeKind
  nodeId: string
  /** Dipanggil seusai sunting tersimpan — pemanggil menyegarkan daftar/tile-nya. */
  onChanged?: () => void
  /** Dipanggil seusai simpul dihapus — pemanggil menutup panelnya. */
  onDeleted?: () => void
  /**
   * Perilaku aksi "Lihat di peta". Pesan sorotnya sudah disiapkan, jadi pemanggil yang
   * belum menampilkan peta tinggal meneruskannya ke `navigate('/map', focus)`; pemanggil
   * yang PETANYA sudah terbentang di belakang panel cukup menutup panel. Kosong = aksi
   * disembunyikan (operator tak berizin membuka peta).
   */
  onShowOnMap?: (focus: MapFocusState) => void
}) {
  const meta = KIND[kind]
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const canUpdate = can(meta.updatePerm)
  const canDelete = can(meta.deletePerm)

  const [node, setNode] = useState<NodeView | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [draft, setDraft] = useState<NodeDraft | null>(null)
  const [initialDraft, setInitialDraft] = useState<NodeDraft | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      setNode(await api.get<NodeView>(`/api/${meta.path}/${nodeId}`))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setNotFound(true)
      else toast.error(err instanceof ApiError ? err.message : `Gagal memuat detail ${meta.label}`)
    } finally {
      setLoading(false)
    }
  }, [meta.label, meta.path, nodeId, toast])

  useEffect(() => {
    void load()
  }, [load])

  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }

  const save = async () => {
    if (!draft) return
    setSaving(true)
    try {
      await api.put(`/api/${meta.path}/${nodeId}`, {
        code: draft.code,
        name: draft.name,
        address: draft.address || null,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
        // Simpul bersplitter mengirim rasionya; joint box mengirim jumlah tray.
        // Mengirim keduanya sekaligus berarti menuliskan angka karangan ke salah
        // satunya — server menolak field yang tak dikenalnya pun tak menolongnya.
        // Kabinet berisi BANYAK modul tak mengirim rasio sama sekali: satu isian
        // tak bisa mewakili tiga modul, jadi isinya diurus panel "Isi kabinet".
        ...(meta.hasSplitter
          ? draft.splitterCount > 1
            ? {}
            : { splitterRatio: draft.splitterRatio || null }
          : { trayCount: Number(draft.trayCount) }),
        capacity: Number(draft.capacity),
        status: draft.status,
      })
      closeDraft()
      await load()
      onChanged?.()
      toast.success(`${meta.label} diperbarui`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal menyimpan ${meta.label}`)
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!node) return
    if (
      !(await confirm({
        title: `Hapus ${meta.label}`,
        message: `Hapus ${meta.label} ${node.code}?`,
        confirmLabel: 'Hapus',
        danger: true,
      }))
    )
      return
    try {
      await api.del(`/api/${meta.path}/${nodeId}`)
      toast.success(`${meta.label} ${node.code} dihapus`)
      onDeleted?.()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal menghapus ${meta.label}`)
    }
  }

  if (loading) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
        <Spinner />
      </div>
    )
  }

  if (notFound || !node) {
    return (
      <div className="card">
        <EmptyState
          title={`${meta.label} tidak ditemukan`}
          hint="Mungkin sudah dihapus atau kamu tak berizin melihatnya."
          icon={<IconInventory size={32} />}
        />
      </div>
    )
  }

  const odc = kind === 'odc' ? (node as OdcView) : null
  const odp = kind === 'odp' ? (node as OdpView) : null
  const jointBox = kind === 'joint_box' ? (node as JointBoxView) : null
  const cabinet = odc ?? odp
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  // Ringkasan sebaris di bawah lencana: apa yang paling ingin diketahui orang yang
  // baru membuka kotak ini. ODC/ODP → hulunya; joint box → seberapa penuh traynya,
  // sebab kotak sambung tak punya hulu logis (ia cuma menerus).
  const subtitle = jointBox
    ? `${jointBox.spliceCount}/${jointBox.capacity} sambungan terpakai`
    : odc
      ? odc.oltName
        ? `Hulu: ${odc.oltName} · ${odc.ponPortLabel}`
        : 'Belum di-uplink'
      : odp?.odcName
        ? `ODC induk: ${odp.odcName}`
        : 'Belum tersambung ke ODC'

  return (
    <>
      <AssetDetailPanel
        badges={
          <>
            {odc?.energized ? <StatusBadge status="ACTIVE" label="teraliri" /> : <StatusBadge status={node.status} />}
            <Badge>
              {jointBox
                ? `${jointBox.trayCount} tray`
                : cabinet!.splitterCount === 0
                  ? 'tanpa splitter'
                  : `${cabinet!.splitterRatio} · ${cabinet!.splitterLegs} kaki`}
            </Badge>
          </>
        }
        subtitle={subtitle}
        fields={
          jointBox
            ? [
                { label: 'Nama', value: jointBox.name },
                { label: 'Jumlah tray', value: String(jointBox.trayCount) },
                { label: meta.capacityLabel, value: String(jointBox.capacity) },
                { label: 'Sambungan terpasang', value: String(jointBox.spliceCount) },
              ]
            : odc
              ? [
                  { label: 'Nama', value: odc.name },
                  {
                    label: 'Hulu (OLT · PON)',
                    value: odc.oltName ? `${odc.oltName} · ${odc.ponPortLabel}` : 'belum di-uplink',
                  },
                  { label: 'Splitter', value: describeSplitter(odc) },
                  { label: meta.capacityLabel, value: String(odc.capacity) },
                  { label: 'Jumlah ODP', value: String(odc.odpCount) },
                ]
              : [
                  { label: 'Nama', value: node.name },
                  { label: 'ODC induk', value: odp?.odcName ?? '—' },
                  { label: 'Splitter', value: odp ? describeSplitter(odp) : '—' },
                  { label: meta.capacityLabel, value: String(node.capacity) },
                ]
        }
        address={node.address}
        location={node.location}
        canUpdate={canUpdate}
        canDelete={canDelete}
        onEdit={() => {
          const d = toDraft(node)
          setDraft(d)
          setInitialDraft(d)
        }}
        onDelete={() => void remove()}
        onShowOnMap={onShowOnMap ? () => onShowOnMap(mapFocusState(kind, nodeId, node.location)) : undefined}
      >
        {/* Joint box tak kebagian panel ini bukan karena disembunyikan: di dalamnya
            memang tak ada splitter, serat langsung disambung ke serat. */}
        {cabinet && (
          <SplitterPanel
            ownerKind={kind === 'odc' ? 'ODC' : 'ODP'}
            ownerId={nodeId}
            onChanged={() => {
              void load()
              onChanged?.()
            }}
          />
        )}
      </AssetDetailPanel>

      {/* Drawer sunting lebih sempit yang menumpang DI ATAS detail — panel induknya tetap
          mengintip di kiri supaya operator tahu benda mana yang sedang ia ubah. */}
      <Blade
        open={draft != null}
        title={`Edit ${node.code}`}
        subtitle={meta.editHint}
        size="full"
        className="blade-edit"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <Button variant="primary" disabled={saving} onClick={() => void save()}>
              Simpan
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <div style={{ flex: 1 }}>
                <TextField
                  label="Kode"
                  value={draft.code}
                  onChange={(_, data) => setDraft({ ...draft, code: data.value })}
                  disabled
                />
              </div>
              <div style={{ flex: 2 }}>
                <TextField
                  label="Nama"
                  value={draft.name}
                  onChange={(_, data) => setDraft({ ...draft, name: data.value })}
                />
              </div>
            </div>
            <TextField
              label={<>Alamat <span className="muted">(opsional)</span></>}
              value={draft.address}
              onChange={(_, data) => setDraft({ ...draft, address: data.value })}
            />
            <div className="row">
              <div style={{ flex: 1 }}>
                {meta.hasSplitter ? (
                  draft.splitterCount > 1 ? (
                    // Kabinet berisi banyak modul: satu isian tak bisa mewakilinya,
                    // jadi form ini melapor apa adanya dan menunjuk panel yang benar.
                    <TextField
                      label="Splitter"
                      value={`${draft.splitterCount} modul — atur di "Isi kabinet"`}
                      disabled
                    />
                  ) : (
                    <SelectField
                      label="Splitter"
                      value={draft.splitterRatio}
                      onChange={(_, data) => setDraft({ ...draft, splitterRatio: data.value })}
                    >
                      {/* Kabinet tanpa splitter itu benda nyata (cross-connect), bukan
                          isian yang lupa diisi — jadi ia punya pilihannya sendiri. */}
                      <option value="">Tanpa splitter</option>
                      {SPLITTER_RATIOS.map((ratio) => (
                        <option key={ratio}>{ratio}</option>
                      ))}
                    </SelectField>
                  )
                ) : (
                  <TextField
                    label="Jumlah tray"
                    value={draft.trayCount}
                    onChange={(_, data) => setDraft({ ...draft, trayCount: data.value })}
                  />
                )}
              </div>
              <div style={{ flex: 1 }}>
                <TextField
                  label={meta.capacityLabel}
                  value={draft.capacity}
                  onChange={(_, data) => setDraft({ ...draft, capacity: data.value })}
                />
              </div>
              <div style={{ flex: 1 }}>
                <SelectField
                  label="Status"
                  value={draft.status}
                  onChange={(_, data) => setDraft({ ...draft, status: data.value as AssetStatus })}
                >
                  {STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </SelectField>
              </div>
            </div>
            <label>
              <span>Lokasi</span>
              <LocationPicker
                longitude={draft.longitude}
                latitude={draft.latitude}
                onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
                height={240}
              />
            </label>
          </div>
        )}
      </Blade>
    </>
  )
}
