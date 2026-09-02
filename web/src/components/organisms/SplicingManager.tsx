import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import {
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  ToggleButton,
} from '@fluentui/react-components'
import { Zap } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type {
  CableAttachmentRole,
  CableView,
  ClosureKind,
  ConnectionPointRequest,
  FiberConnectionPointView,
  FiberConnectionView,
  SpliceCableView,
  SpliceMethod,
  SpliceWorkbenchView,
} from '@/api/network'
import { CABLE_ATTACHMENT_ROLE_LABEL, SPLICE_METHOD_LABEL } from '@/api/network'
import type { PageResponse } from '@/api/types'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextField } from '@/components/atoms'
import { IconChevronDown } from '@/components/atoms/icons'
import { Combobox } from '@/components/molecules'
import { useOpenWorkOrders } from '@/hooks/useOpenWorkOrders'
import { useConfirm, useToast } from '@/system'
import type { SpliceEnd, WiredPair } from '@/utils/spliceGuard'
import { impossibleSpliceWarning, sameCableWarning } from '@/utils/spliceGuard'
import { timeAgo } from '@/utils/timeAgo'

/**
 * Meja kerja splicing & patching — satu layar untuk SEMUA kotak: ODF, ODC, ODP,
 * joint box.
 *
 * Bentuknya meniru pekerjaan aslinya. Teknisi membuka satu kotak, menaruh dua
 * ujung di hadapannya, lalu menyambungkannya sehelai demi sehelai; jadi layar
 * ini juga dua panel bersisian dengan tombol sambung di antaranya, bukan sebuah
 * formulir "pilih A, pilih B" yang menyembunyikan apa saja yang tersedia.
 *
 * Lima hal yang membedakannya dari daftar sambungan biasa:
 *
 * 1. **Kabel yang cuma LEWAT ikut tampil.** Kabel distribusi 8 core yang
 *    melewati delapan ODP dikupas di tiap kotak untuk mengambil satu core —
 *    kotaknya bukan ujung kabel, tapi core-nya tetap harus bisa disambung dari
 *    sini. Setiap kabel diberi keterangan "berujung di sini" atau jarak titik
 *    kupasnya dari pangkal, angka yang dipakai teknisi mencocokkan hasil OTDR.
 * 2. **Kedua sisi memajang isi kotak yang SAMA, dan namanya Ujung A / Ujung B.**
 *    Arah cahaya melekat pada CORE, bukan pada selubungnya: satu kabel antar-ODC
 *    lazim membawa core turun dan core naik sekaligus, jadi selubung yang sama
 *    sah menjadi ujung mana pun. Menamainya "masuk" dan "keluar" akan
 *    menjanjikan aturan yang tak dikerjakan mesinnya — dan menutup sambung lurus
 *    dua kabel sejenis di joint box, yang justru pekerjaan sehari-hari.
 * 3. **Seisi kotak terbuka sekaligus.** Yang membuka tutup ODP melihat semua
 *    kabel di dalamnya sekali lihat, tanpa membuka laci satu-satu. Kotak ramai
 *    (di atas empat kelompok: ODC bersplitter banyak, rak ODF) melipat sendiri
 *    kelompoknya supaya panelnya tak jadi gulungan tak berujung.
 * 4. **Sambung 1:1 otomatis.** Kabel 8 core masuk, 8 core keluar: memasangkan
 *    core 1↔1 … 8↔8 satu per satu adalah 8 klik yang hasilnya sudah bisa
 *    ditebak. Satu tombol mengerjakannya sekaligus, dan server menerapkannya
 *    sebagai satu transaksi — semua masuk, atau tak ada yang masuk.
 * 5. **Tiap sambungan membawa tiket & nama teknisinya.** Kotak tak pernah dibuka
 *    tanpa alasan: ada work order yang menyuruhnya. Pilih tiketnya sekali di
 *    atas, lalu semua sambungan pada sesi itu ikut terbukukan ke sana — dan
 *    balasannya masuk ke linimasa tiket, jadi penyelia melihat kerja seratnya
 *    tanpa harus membuka peta. Siapa yang menyambung diisi server dari sesi,
 *    tak bisa diketik.
 */

/** Satu titik yang bisa diklik di salah satu panel — core maupun port/kaki. */
interface Slot {
  key: string
  point: ConnectionPointRequest
  label: string
  title: string
  /** Warna selubung serat (TIA-598) untuk core; null untuk port/kaki. */
  colorHex: string | null
  /**
   * Kabel pemilik core ini; null untuk kaki/input/port. Ikut dibawa karena
   * aturan splitter berbicara tentang KABEL, bukan core: kaki tak boleh berbalik
   * ke selubung yang sedang menyuapi input modulnya.
   */
  cableId: string | null
  cableCode: string | null
  /** Sambungan yang memakainya DI KOTAK INI — harus dilepas dulu sebelum dipakai lagi. */
  connectionId: string | null
  /**
   * Ke mana titik ini bermuara menurut serat, mis. "DROP-… · Budi Santoso".
   * Hanya kaki splitter yang punya; core & port ODF null.
   */
  serves: string | null
  /**
   * Ujung seberangnya DI KOTAK INI bila sudah tersambung, sesingkat mungkin
   * ("DIST-02 c5", "SPL-01 kaki 3"). Titik terpakai yang cuma diredupkan
   * memaksa orang turun ke tabel di bawah untuk pertanyaan yang paling sering
   * ditanya sambil memegang seratnya: "yang ini nyambung ke mana?".
   */
  partner: string | null
  /** Core ini sudah tersambung di kotak LAIN; dari sini tak bisa diapa-apakan. */
  blocked: boolean
}

/** Sekelompok titik yang tampil bersama dalam satu lipatan: satu kabel, satu modul, satu rak. */
interface Group {
  key: string
  option: string
  /** Keterangan di bawah dropdown; kosong bila nama kelompoknya sudah cukup. */
  hint: string
  /** Kode kabel/nama kelompok apa adanya — untuk kalimat, bukan untuk dropdown. */
  code: string
  isCable: boolean
  slots: Slot[]
}

const METHODS: SpliceMethod[] = ['FUSION', 'MECHANICAL', 'CONNECTOR']

const free = (slot: Slot) => slot.connectionId == null && !slot.blocked

/** Titik yang sedang dipilih, dalam bentuk yang dimengerti penjaga aturan splitter. */
const toEnd = (slot: Slot): SpliceEnd => ({
  kind: slot.point.kind,
  nodeId: slot.point.nodeId ?? null,
  portNumber: slot.point.portNumber ?? null,
  cableId: slot.cableId,
  cableCode: slot.cableCode,
})

/** Idem, untuk ujung sambungan yang sudah tercatat di kotak ini. */
const wiredEnd = (point: FiberConnectionPointView): SpliceEnd => ({
  kind: point.kind,
  nodeId: point.nodeId,
  portNumber: point.portNumber,
  cableId: point.cableId,
  cableCode: point.cableCode,
})

/** Tanggal singkat pada opsi tiket — saat memilih tugas, jamnya belum penting. */
const shortDate = (iso: string) => new Date(iso).toLocaleDateString('id-ID', { day: 'numeric', month: 'short' })

/**
 * Hitam atau putih di atas warna serat, mengikuti luminansi yang dirasakan mata —
 * nomor core harus terbaca baik di atas "Kuning" maupun "Hitam". Kembaran dari
 * yang dipakai kisi core kabel; sengaja disalin daripada dijadikan util bersama
 * yang cuma dipakai dua tempat.
 */
function inkOn(hex: string): string {
  const v = hex.replace('#', '')
  const r = parseInt(v.slice(0, 2), 16)
  const g = parseInt(v.slice(2, 4), 16)
  const b = parseInt(v.slice(4, 6), 16)
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6 ? '#0f172a' : '#ffffff'
}

/** Kunci sebuah titik — dipakai menyamakan ujung sambungan dengan slot di panel. */
const pointKey = (p: { kind: string; nodeId: string | null; portNumber: number | null; portSide: string | null }) =>
  `point:${p.kind}:${p.nodeId}:${p.portNumber ?? ''}:${p.portSide ?? ''}`

/** Sebutan sesingkat mungkin untuk seberang sebuah sambungan; muat di satu baris kecil. */
function shortEnd(p: FiberConnectionPointView): string {
  return p.coreNumber != null ? `${p.cableCode ?? 'kabel'} c${p.coreNumber}` : p.label
}

/**
 * Peta "titik ini bersambung ke apa", dari sambungan yang sudah ada di kotak ini.
 *
 * Dibangun dari kedua arah sekaligus: sebuah sambungan tak punya sisi depan dan
 * sisi belakang, jadi core mana pun harus bisa menyebut seberangnya tanpa peduli
 * ia tercatat sebagai ujung `a` atau `b`.
 */
function partnersByKey(connections: FiberConnectionView[]): Map<string, string> {
  const keyOf = (p: FiberConnectionPointView) => (p.coreId ? `core:${p.coreId}` : pointKey(p))
  const map = new Map<string, string>()
  for (const row of connections) {
    map.set(keyOf(row.a), shortEnd(row.b))
    map.set(keyOf(row.b), shortEnd(row.a))
  }
  return map
}

/** Susun kabel & titik simpul jadi kelompok yang dipajang berlipat di tiap panel. */
function toGroups(data: SpliceWorkbenchView): Group[] {
  const partners = partnersByKey(data.connections)
  const cables: Group[] = data.cables.map((cable) => {
    // Jarak tap adalah angka lapangan, bukan hiasan: itu yang dicocokkan dengan
    // hasil OTDR saat mencari letak sambungan di sepanjang rute.
    const where = cable.terminatesHere
      ? 'berujung di sini'
      : `${cable.spliceable ? 'dikupas' : 'cuma lewat'} · m-${Math.round(cable.tapDistanceMeters)}`
    // Selubung utuh tak punya core yang terbuka: kabelnya ADA di dalam kotak,
    // tapi tak sehelai pun boleh disentuh dari sini. Core-nya tetap ditampilkan
    // (supaya terlihat kabel ini isinya apa) namun semuanya terkunci.
    return {
      key: `cable:${cable.cableId}`,
      option: `${cable.code} · ${cable.coreCount} core · ${where}`,
      hint: cable.spliceable
        ? `${cable.name} · ${Math.round(cable.lengthMeters)} m`
        : `${cable.name} · selubung utuh — tandai "dikupas di sini" dulu bila baru dibuka`,
      code: cable.code,
      isCable: true,
      slots: cable.cores.map((entry) => {
        const partner = entry.connectionId ? (partners.get(`core:${entry.core.id}`) ?? null) : null
        return {
          key: `core:${entry.core.id}`,
          point: { kind: 'CORE', coreId: entry.core.id },
          label: String(entry.core.coreNumber),
          title:
            `Core ${entry.core.coreNumber} · ${entry.core.color}` +
            (!cable.spliceable
              ? ' · selubung kabel ini utuh di sini, tak ada core yang terbuka'
              : entry.connectionId
                ? ` · tersambung ke ${partner ?? 'titik lain di kotak ini'}`
                : entry.connectedElsewhere
                  ? ' · dipakai sambungan di kotak lain'
                  : ' · bebas') +
            (entry.core.note ? ` · ${entry.core.note}` : ''),
          colorHex: entry.core.colorHex,
          cableId: cable.cableId,
          cableCode: cable.code,
          connectionId: entry.connectionId,
          serves: null,
          partner,
          blocked: entry.connectedElsewhere || !cable.spliceable,
        }
      }),
    }
  })

  const points: Group[] = []
  for (const point of data.points) {
    const key = `point:${point.group}`
    let group = points.find((g) => g.key === key)
    if (!group) {
      group = { key, option: point.group, hint: '', code: point.group, isCable: false, slots: [] }
      points.push(group)
    }
    const partner = point.connectionId ? (partners.get(pointKey(point)) ?? null) : null
    group.slots.push({
      key: pointKey(point),
      point: {
        kind: point.kind,
        nodeId: point.nodeId,
        portNumber: point.portNumber,
        portSide: point.portSide,
      },
      label: point.label,
      // Muara kaki masuk ke tooltip-nya juga, bukan cuma ke baris kecil di bawah
      // labelnya: pil selebar 120px memotong "DROP-ODP-01-P3 · Budi Santoso"
      // hampir selalu, dan nama yang terpotong setengah menyesatkan.
      title:
        `${point.label} · ${point.connectionId ? `tersambung ke ${partner ?? 'titik lain'}` : 'bebas'}` +
        (point.serves ? ` · ${point.serves}` : ''),
      colorHex: null,
      cableId: null,
      cableCode: null,
      connectionId: point.connectionId,
      serves: point.serves,
      partner,
      blocked: false,
    })
  }

  return [...cables, ...points]
}

/**
 * Pencatat singgahan kabel — di tempat kejadiannya, saat kotaknya sedang terbuka.
 *
 * Server tak lagi menebak kabel mana yang boleh disambung di sebuah kotak dari
 * jarak kotak ke garis rute; yang menentukan adalah catatan "selubung kabel ini
 * dibuka di sini". Catatan itu cuma bisa dibuat orang yang melihat isi kotaknya,
 * dan orang itu ada DI SINI, bukan di formulir kabel halaman peta. Karena itu
 * kupasan didaftarkan dari meja sambung, sebaris di atas mejanya.
 *
 * Tiga tombol yang tersedia mencerminkan tiga kejadian nyata:
 * - kabel orang yang ternyata dikupas juga di kotak ini → "Tandai dikupas";
 * - salah catat, ternyata selubungnya utuh → "Ternyata cuma lewat";
 * - keliru orang/kotak → "Bukan di kotak ini" (dicabut sama sekali).
 *
 * Ujung kabel tak diberi tombol apa pun: selubung yang HABIS di sebuah kotak
 * adalah bentuk kabelnya, dan itu diubah lewat formulir kabel di peta — server
 * pun menolaknya dari pintu ini.
 */
function CableAttachmentBar({
  closureKind,
  closureId,
  cables,
  onChanged,
}: {
  closureKind: ClosureKind
  closureId: string
  cables: SpliceCableView[]
  onChanged: () => Promise<void>
}) {
  const toast = useToast()
  const confirm = useConfirm()
  const [pick, setPick] = useState('')
  const [pickRole, setPickRole] = useState<CableAttachmentRole>('TAPPED')
  const [busy, setBusy] = useState(false)

  // Kabel yang sudah tercatat singgah di sini tak muncul lagi di pencarian:
  // perannya diubah lewat tombol di barisan atas, bukan didaftarkan dua kali.
  const fetchCables = useCallback(
    async (term: string) => {
      const page = await api.get<PageResponse<CableView>>(
        `/api/cables?query=${encodeURIComponent(term)}&size=10`,
      )
      return page.content.filter((c) => !cables.some((x) => x.cableId === c.id))
    },
    [cables],
  )

  const attach = async (cableId: string, role: CableAttachmentRole, done: string) => {
    setBusy(true)
    try {
      await api.post(`/api/cables/${cableId}/attachments`, { nodeKind: closureKind, nodeId: closureId, role })
      toast.success(done)
      setPick('')
      await onChanged()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencatat singgahan kabel')
    } finally {
      setBusy(false)
    }
  }

  const detach = async (cableId: string, code: string) => {
    if (
      !(await confirm({
        title: 'Cabut catatan singgahan',
        message:
          `Kabel ${code} dinyatakan tak pernah menyinggahi kotak ini. Sesudah ini core-nya ` +
          'tak bisa disambung dari sini sampai singgahannya dicatat lagi.',
        confirmLabel: 'Cabut',
        danger: true,
      }))
    )
      return
    setBusy(true)
    try {
      await api.del(`/api/cables/${cableId}/attachments/${closureId}`)
      toast.success(`${code} dicabut dari kotak ini`)
      await onChanged()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencabut singgahan kabel')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      {cables.length > 0 && (
        <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'center' }}>
          {cables.map((cable) => (
            <span key={cable.cableId} className="row" style={{ gap: '0.25rem', alignItems: 'center' }}>
              <Badge tone={cable.spliceable ? 'accent' : 'neutral'}>
                {cable.code} · {cable.roleLabel}
              </Badge>
              {!cable.terminatesHere && (
                <>
                  {cable.spliceable ? (
                    <Button
                      variant="subtle"
                      disabled={busy}
                      title="Ternyata selubungnya utuh di kotak ini — cuma numpang lewat."
                      onClick={() =>
                        void attach(cable.cableId, 'PASSING', `${cable.code} ditandai cuma lewat`)
                      }
                    >
                      Cuma lewat
                    </Button>
                  ) : (
                    <Button
                      variant="subtle"
                      disabled={busy}
                      title="Selubungnya baru dibuka di kotak ini — core-nya jadi bisa disambung."
                      onClick={() =>
                        void attach(cable.cableId, 'TAPPED', `${cable.code} ditandai dikupas di sini`)
                      }
                    >
                      Tandai dikupas
                    </Button>
                  )}
                  <Button
                    variant="subtle"
                    disabled={busy}
                    title="Kabel ini sama sekali tak lewat kotak ini — salah catat."
                    onClick={() => void detach(cable.cableId, cable.code)}
                  >
                    Bukan di sini
                  </Button>
                </>
              )}
            </span>
          ))}
        </div>
      )}

      <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
        <div className="stack" style={{ flex: 2, minWidth: 220, gap: '0.25rem' }}>
          <Text as="span" size={200}>Ada kabel lain di dalam kotak?</Text>
          <Combobox
            value={pick}
            onChange={(id) => setPick(id)}
            fetchOptions={fetchCables}
            toId={(c) => c.id}
            toLabel={(c) => `${c.code} · ${c.coreCount} core`}
            toMeta={(c) => c.name}
            placeholder="Cari kode atau nama kabel…"
            emptyText="Tak ada kabel lain yang cocok"
          />
        </div>
        <SelectField
          label="Keadaannya"
          value={pickRole}
          onChange={(_, d) => setPickRole(d.value as CableAttachmentRole)}
        >
          <option value="TAPPED">{CABLE_ATTACHMENT_ROLE_LABEL.TAPPED}</option>
          <option value="PASSING">{CABLE_ATTACHMENT_ROLE_LABEL.PASSING}</option>
        </SelectField>
        <Button
          disabled={pick === '' || busy}
          onClick={() => void attach(pick, pickRole, 'Singgahan kabel dicatat')}
        >
          Catat singgahan
        </Button>
      </div>
      <Text as="span" size={100} className="muted">
        Yang dicatat di sini adalah apa yang benar-benar ada di dalam kotak. Kabel yang cuma
        melintas pun perlu tercatat — supaya orang berikutnya tak mengira itu kabel kotak ini
        lalu memotongnya.
      </Text>
    </div>
  )
}

/**
 * Di atas berapa kelompok sebuah kotak dianggap ramai dan isinya mulai terlipat.
 *
 * Empat karena itu batas kotak lapangan yang lazim: ODP berisi kabel distribusi,
 * satu-dua drop, dan sebuah splitter masih muat dipandang sekaligus. Yang di
 * atasnya adalah ODC bersplitter banyak dan rak ODF — di sana daftar yang
 * terbuka semua justru menyembunyikan, karena tak ada yang terbaca tanpa
 * menggulung.
 */
const CROWDED = 4

/** Kisi titik satu kelompok: kotak core berwarna, atau pil untuk kaki/port. */
function SlotGrid({
  group,
  picked,
  onPick,
  disabled,
}: {
  group: Group
  picked: string | null
  onPick: (slot: Slot) => void
  disabled: boolean
}) {
  return (
    <div className={group.isCable ? 'core-grid splice-core-grid' : 'splice-slots'}>
      {group.slots.map((slot) => {
        const taken = !free(slot)
        const on = picked === slot.key
        const style = slot.colorHex ? { background: slot.colorHex, color: inkOn(slot.colorHex) } : undefined
        // Muara ("punya Budi") menang atas seberang ("SPL-01 kaki 3") saat
        // keduanya ada: yang berdiri di depan kotak mencari pelanggannya, bukan
        // topologinya. Seberangnya tetap terbaca di tooltip.
        const caption = slot.serves ?? (slot.partner ? `↔ ${slot.partner}` : null)
        return (
          <button
            type="button"
            key={slot.key}
            className={
              (group.isCable ? 'core-chip' : 'splice-slot') +
              (on ? ' is-selected' : '') +
              (taken ? ' is-used' : '') +
              (!group.isCable && caption ? ' has-serves' : '')
            }
            aria-pressed={on}
            style={style}
            title={slot.title}
            disabled={disabled || taken}
            onClick={() => onPick(slot)}
          >
            {/* Kaki yang sudah berisi memakai dua baris: nomornya, lalu jalur
                yang dilayaninya — pertanyaan pertama orang yang membuka kotak
                bukan "kaki berapa yang kosong" melainkan "kaki mana punya
                Budi". Core tetap sekeping angka; warnanya yang bicara, dan
                seberangnya lewat tooltip — kotak 24 piksel tak muat kalimat. */}
            {!group.isCable && caption ? (
              <>
                <span className="splice-slot-label">{slot.label}</span>
                <span className="splice-slot-serves">{caption}</span>
              </>
            ) : (
              slot.label
            )}
          </button>
        )
      })}
    </div>
  )
}

/**
 * Satu sisi meja: seisi kotak, terpajang sekaligus.
 *
 * Tak ada dropdown — yang membuka tutup ODP melihat semua kabel di dalamnya
 * tanpa membuka laci satu per satu, dan layar yang menyembunyikannya membuat
 * orang menebak isi kotak yang sedang ada di tangannya. Yang dilipat hanyalah
 * kotak ramai, dan lipatannya bisa dibuka sendiri-sendiri.
 */
function SidePanel({
  title,
  groups,
  picked,
  onPick,
  disabled,
}: {
  title: string
  groups: Group[]
  picked: string | null
  onPick: (slot: Slot) => void
  disabled: boolean
}) {
  // Hanya lipatan yang DIUBAH orangnya yang dicatat; sisanya ikut bawaan, jadi
  // kotak yang berubah isinya (kabel baru dicatat) tak mewarisi keadaan basi.
  const [toggled, setToggled] = useState<Record<string, boolean>>({})
  const crowded = groups.length > CROWDED
  const opened = (g: Group) => toggled[g.key] ?? (!crowded || g.slots.some((s) => s.key === picked))
  const available = groups.reduce((n, g) => n + g.slots.filter(free).length, 0)
  const capacity = groups.reduce((n, g) => n + g.slots.length, 0)

  return (
    <div className="splice-side stack" style={{ gap: '0.45rem' }}>
      <div className="spread" style={{ alignItems: 'baseline', gap: '0.4rem' }}>
        <Text as="strong" size={200} weight="semibold">{title}</Text>
        <Text as="span" size={100} className="muted tnum">
          {available}/{capacity} bebas
        </Text>
      </div>

      <div className="splice-groups">
        {groups.map((group) => {
          const open = opened(group)
          return (
            <div key={group.key} className="splice-group">
              <ToggleButton
                className="splice-group-head"
                checked={open}
                aria-expanded={open}
                onClick={() => setToggled((prev) => ({ ...prev, [group.key]: !open }))}
              >
                <span className="chev" aria-hidden>
                  <IconChevronDown size={13} />
                </span>
                <span className="splice-group-name">{group.option}</span>
                <span className="muted tnum splice-group-count">
                  {group.slots.filter(free).length}/{group.slots.length}
                </span>
          </ToggleButton>
              {open && (
                <>
                  {group.hint && <p className="splice-group-hint muted">{group.hint}</p>}
                  <SlotGrid group={group} picked={picked} onPick={onPick} disabled={disabled} />
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

export function SplicingManager({
  closureKind,
  closureId,
  onChanged,
}: {
  closureKind: ClosureKind
  closureId: string
  /** Dipanggil seusai sambungan berubah — pemanggil menyegarkan ringkasan detailnya. */
  onChanged?: () => void
}) {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const canView = can('network.splice.view')
  const canManage = can('network.splice.manage')
  const {
    canPick: canPickWorkOrder,
    searchesAll: canSeeAllWorkOrders,
    fetchWorkOrders,
  } = useOpenWorkOrders()

  const [data, setData] = useState<SpliceWorkbenchView | null>(null)
  const [loading, setLoading] = useState(true)
  const [leftPick, setLeftPick] = useState<Slot | null>(null)
  const [rightPick, setRightPick] = useState<Slot | null>(null)
  const [method, setMethod] = useState<SpliceMethod>('FUSION')
  const [lossDb, setLossDb] = useState('')
  const [busy, setBusy] = useState(false)
  // Tiket yang menaungi sesi ini; dipilih sekali lalu menempel ke tiap sambungan
  // yang dibuat sesudahnya — satu kali buka kotak biasanya satu tiket.
  const [workOrderId, setWorkOrderId] = useState('')
  // Baris sambungan yang sedang disunting keterangannya (hasil ukur menyusul besoknya).
  const [editing, setEditing] = useState<string | null>(null)
  const [editMethod, setEditMethod] = useState<SpliceMethod>('FUSION')
  const [editLoss, setEditLoss] = useState('')
  const [editNote, setEditNote] = useState('')
  // Tiket yang ditempelkan menyusul ke baris lama; kosong = biarkan apa adanya.
  const [editWorkOrder, setEditWorkOrder] = useState('')

  const load = useCallback(async () => {
    try {
      setData(
        await api.get<SpliceWorkbenchView>(
          `/api/fiber-connections/workbench?closureKind=${closureKind}&closureId=${closureId}`,
        ),
      )
    } catch {
      // Panel pelengkap: kegagalannya tak boleh menutup detail kotak yang sudah tampil.
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [closureKind, closureId])

  useEffect(() => {
    if (!canView) {
      setLoading(false)
      return
    }
    void load()
  }, [canView, load])

  const groups = useMemo(() => (data ? toGroups(data) : []), [data])

  // Kelompok yang "sedang aktif" di tiap sisi mengikuti titik yang dipilih.
  // Sejak kedua panel memajang seluruh isi kotak, tak ada lagi arti lain untuk
  // frasa itu — dan sebuah dropdown yang harus disetel terpisah dari core yang
  // diklik cuma dua tempat untuk menyatakan hal yang sama, yang bisa berselisih.
  const groupKeyOf = (slot: Slot | null) =>
    slot == null ? '' : (groups.find((g) => g.slots.some((s) => s.key === slot.key))?.key ?? '')
  const leftKey = groupKeyOf(leftPick)
  const rightKey = groupKeyOf(rightPick)

  // Pilihan yang sudah tak ada lagi (habis disambung/dilepas) tak boleh menggantung.
  useEffect(() => {
    const alive = (slot: Slot | null) =>
      slot != null && groups.some((g) => g.slots.some((s) => s.key === slot.key && free(s)))
    setLeftPick((prev) => (alive(prev) ? prev : null))
    setRightPick((prev) => (alive(prev) ? prev : null))
  }, [groups])

  /** Sambungan yang sudah ada di kotak ini — bahan penilai arah cahaya. */
  const wired = useMemo<WiredPair[]>(
    () => (data?.connections ?? []).map((row) => ({ a: wiredEnd(row.a), b: wiredEnd(row.b) })),
    [data],
  )

  /**
   * Pasangan yang akan dibuat "sambung 1:1": urutan bebas lawan urutan bebas —
   * dikurangi yang mustahil secara fisik.
   *
   * Penyaringnya ikut menghitung pasangan yang BARU saja diterima dalam borongan
   * yang sama, sebab di ODP justru urutan itu yang menjebak: core 1 ke input
   * splitter benar, lalu core 2 ke kaki 1 salah — dan salahnya baru kelihatan
   * setelah pasangan pertama dianggap jadi.
   */
  const { autoPairs, autoSkipped } = useMemo(() => {
    if (leftKey === '' || leftKey === rightKey) return { autoPairs: [] as Array<{ a: Slot; b: Slot }>, autoSkipped: 0 }
    const a = groups.find((g) => g.key === leftKey)?.slots.filter(free) ?? []
    const b = groups.find((g) => g.key === rightKey)?.slots.filter(free) ?? []
    const sofar = [...wired]
    const pairs: Array<{ a: Slot; b: Slot }> = []
    let skipped = 0
    for (let i = 0; i < Math.min(a.length, b.length); i += 1) {
      const ends = { a: toEnd(a[i]), b: toEnd(b[i]) }
      if (impossibleSpliceWarning(ends.a, ends.b, sofar) != null) {
        skipped += 1
        continue
      }
      pairs.push({ a: a[i], b: b[i] })
      sofar.push(ends)
    }
    return { autoPairs: pairs, autoSkipped: skipped }
  }, [groups, leftKey, rightKey, wired])

  // Tiket sengaja TIDAK ikut dibersihkan: satu kali kotak dibuka biasanya menghasilkan
  // beberapa sambungan berturut-turut, semuanya milik tugas yang sama.
  const afterChange = async () => {
    setLeftPick(null)
    setRightPick(null)
    setLossDb('')
    await load()
    onChanged?.()
  }

  const connect = async () => {
    if (!leftPick || !rightPick || busy) return
    setBusy(true)
    try {
      await api.post('/api/fiber-connections', {
        closureKind,
        closureId,
        a: leftPick.point,
        b: rightPick.point,
        method,
        lossDb: lossDb.trim() === '' ? null : Number(lossDb),
        workOrderId: workOrderId || null,
      })
      toast.success(`${leftPick.label} ↔ ${rightPick.label} tersambung`)
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyambung')
    } finally {
      setBusy(false)
    }
  }

  const connectAll = async () => {
    if (autoPairs.length === 0 || busy) return
    const left = groups.find((g) => g.key === leftKey)
    const right = groups.find((g) => g.key === rightKey)
    if (
      !(await confirm({
        title: 'Sambung 1:1 otomatis',
        message:
          `Buat ${autoPairs.length} sambungan sekaligus antara ${left?.option} dan ${right?.option}, ` +
          'dipasangkan berurutan dari yang paling kecil. Semua masuk atau tak ada yang masuk.' +
          // Yang dilewati disebutkan apa adanya: daftar yang diam-diam menyusut
          // membuat orang mengira sisanya gagal lalu mengulang borongannya.
          (autoSkipped > 0
            ? ` ${autoSkipped} pasangan dilewati karena mustahil secara fisik — kaki splitter ` +
              'tak boleh berbalik ke kabel yang menyuapi inputnya sendiri.'
            : ''),
        confirmLabel: `Sambung ${autoPairs.length} pasang`,
      }))
    )
      return
    setBusy(true)
    try {
      await api.post('/api/fiber-connections/bulk', {
        closureKind,
        closureId,
        pairs: autoPairs.map((pair) => ({ a: pair.a.point, b: pair.b.point, method })),
        workOrderId: workOrderId || null,
      })
      toast.success(`${autoPairs.length} sambungan dibuat`)
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyambung sekaligus')
    } finally {
      setBusy(false)
    }
  }

  const disconnect = async (id: string, label: string) => {
    if (
      !(await confirm({
        title: 'Lepas sambungan',
        message: `Lepas ${label}? Kedua ujungnya kembali bebas dan layanan yang lewat situ akan mati.`,
        confirmLabel: 'Lepas',
        danger: true,
      }))
    )
      return
    try {
      await api.del(`/api/fiber-connections/${id}`)
      toast.success('Sambungan dilepas')
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal melepas sambungan')
    }
  }

  const saveDetail = async (id: string) => {
    setBusy(true)
    try {
      await api.put(`/api/fiber-connections/${id}`, {
        method: editMethod,
        lossDb: editLoss.trim() === '' ? null : Number(editLoss),
        note: editNote.trim() === '' ? null : editNote.trim(),
        // Kosong = jangan diapa-apakan. Tiket hanya bisa DITEMPELKAN, tak bisa dipindah:
        // sambungan adalah bukti siapa membuka kotak apa karena tugas mana.
        workOrderId: editWorkOrder || null,
      })
      setEditing(null)
      toast.success('Keterangan sambungan disimpan')
      await load()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan keterangan')
    } finally {
      setBusy(false)
    }
  }

  if (!canView || loading || !data) return null

  const capacityFull = data.spliceCapacity != null && data.spliceCount >= data.spliceCapacity
  // Kedua dropdown menunjuk kabel yang sama — pasangan yang pasti ditolak server.
  // Dikenali dari KELOMPOKNYA, bukan dari core yang sudah terlanjur diklik, supaya
  // peringatannya muncul saat kabelnya dipilih, bukan setelah orangnya salah jalan.
  const leftGroup = groups.find((g) => g.key === leftKey)
  const sameCable = leftKey !== '' && leftKey === rightKey && (leftGroup?.isCable ?? false)
  // Aturan splitter baru bisa dinilai setelah kedua titiknya dipilih — yang
  // menentukan bukan kelompoknya melainkan kaki/input yang mana, dan kabel apa
  // yang sedang menyuapi modul itu.
  const pairWarning = impossibleSpliceWarning(
    leftPick ? toEnd(leftPick) : null,
    rightPick ? toEnd(rightPick) : null,
    wired,
  )
  const warning =
    sameCableWarning(sameCable ? (leftGroup?.code ?? '') : null, groups.length === 1) ?? pairWarning
  const summary =
    data.spliceCapacity != null
      ? `${data.spliceCount}/${data.spliceCapacity} sambungan · ${data.cables.length} kabel singgah`
      : `${data.spliceCount} sambungan · ${data.cables.length} kabel singgah`

  return (
    <div className="card stack">
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <h3 style={{ margin: 0 }}>Sambungan serat</h3>
        {capacityFull && <Badge tone="warning">tray penuh</Badge>}
      </div>
      <Text as="p" size={200} className="muted" style={{ margin: 0 }}>{summary}</Text>

      {canManage && (
        <CableAttachmentBar
          closureKind={closureKind}
          closureId={closureId}
          cables={data.cables}
          onChanged={load}
        />
      )}

      {data.cables.length === 0 ? (
        <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
          Belum ada kabel yang tercatat singgah di kotak ini. Kalau kotaknya sedang terbuka dan
          kabelnya memang ada di dalam, catat dulu singgahannya di atas — sesudah itu core-nya
          bisa disambung. Kalau kabelnya sendiri belum ada, tarik dulu di peta.
        </Text>
      ) : (
        <>
          {canManage && canPickWorkOrder && (
            <div className="stack" style={{ gap: '0.25rem' }}>
              <Text as="span" size={200}>Work order (opsional)</Text>
              <Combobox
                value={workOrderId}
                onChange={(id) => setWorkOrderId(id)}
                fetchOptions={fetchWorkOrders}
                toId={(wo) => wo.id}
                toLabel={(wo) => `${wo.code} · ${wo.title}`}
                toMeta={(wo) => [wo.customerName, wo.scheduledAt ? shortDate(wo.scheduledAt) : null].filter(Boolean).join(' · ')}
                placeholder={canSeeAllWorkOrders ? 'Cari kode atau judul tiket…' : 'Pilih dari tugas saya…'}
                emptyText="Tak ada work order terbuka"
              />
              <Text as="span" size={100} className="muted">
                Sambungan yang dibuat setelah ini dibukukan ke tiket tersebut, dan tercatat di linimasanya.
              </Text>
            </div>
          )}

          <div className="splice-bench">
            {/* A dan B, bukan masuk dan keluar: sambungan las tak punya arah, dan
                kabel yang sama sah jadi ujung mana pun. */}
            <SidePanel
              title="Ujung A"
              groups={groups}
              picked={leftPick?.key ?? null}
              onPick={setLeftPick}
              disabled={!canManage || busy}
            />
            <SidePanel
              title="Ujung B"
              groups={groups}
              picked={rightPick?.key ?? null}
              onPick={setRightPick}
              disabled={!canManage || busy}
            />
          </div>

          {warning && (
            <Text as="p" size={100} className="muted" style={{ margin: 0 }}>
              {warning}
            </Text>
          )}

          {canManage && (
            <div className="splice-actions">
              <SelectField
                label="Metode"
                value={method}
                onChange={(_, d) => setMethod(d.value as SpliceMethod)}
              >
                {METHODS.map((m) => (
                  <option key={m} value={m}>
                    {SPLICE_METHOD_LABEL[m]}
                  </option>
                ))}
              </SelectField>
              <TextField
                label="Rugi (dB)"
                value={lossDb}
                onChange={(_, d) => setLossDb(d.value)}
                placeholder="kosong = belum diukur"
                inputMode="decimal"
              />
              <Button
                variant="primary"
                disabled={!leftPick || !rightPick || sameCable || pairWarning != null || busy}
                onClick={() => void connect()}
                title={sameCable || pairWarning != null ? (warning ?? undefined) : undefined}
              >
                {leftPick && rightPick ? `Sambung ${leftPick.label} ↔ ${rightPick.label}` : 'Sambung'}
              </Button>
              <Button
                icon={<Zap size={16} />}
                disabled={autoPairs.length === 0 || busy}
                onClick={() => void connectAll()}
                title={
                  autoPairs.length === 0
                    ? 'Pilih satu titik di tiap sisi, dari dua kelompok berbeda yang masih punya titik bebas'
                    : undefined
                }
              >
                Sambung 1:1 otomatis{autoPairs.length > 0 ? ` (${autoPairs.length} pasang)` : ''}
              </Button>
            </div>
          )}
        </>
      )}

      {data.connections.length > 0 && (
        <div className="table-wrap">
          <Table>
            <TableHeader>
              <TableRow>
                {/* Kolomnya bukan "masuk vs tujuan" — yang tersimpan cuma ujung
                    mana yang kebetulan diklik duluan. Menamainya begitu membuat
                    dua sambungan yang fisiknya sama tampak berlawanan arah. */}
                <TableHeaderCell>Ujung A</TableHeaderCell>
                <TableHeaderCell>Ujung B</TableHeaderCell>
                <TableHeaderCell>Metode</TableHeaderCell>
                <TableHeaderCell>Rugi</TableHeaderCell>
                <TableHeaderCell>Dikerjakan</TableHeaderCell>
                {canManage && <TableHeaderCell />}
              </TableRow>
            </TableHeader>
          <TableBody>{data.connections.map((row) => {
            const label = `${row.a.label} ↔ ${row.b.label}`
            return (
              <Fragment key={row.id}>
                <TableRow><TableCell ><span className="splice-end">
                  {row.a.colorHex && (
                    <span className="splice-dot" style={{ background: row.a.colorHex }} />
                  )}
                  {row.a.label}
                </span></TableCell>
                <TableCell ><span className="splice-end">
                  {row.b.colorHex && (
                    <span className="splice-dot" style={{ background: row.b.colorHex }} />
                  )}
                  {row.b.label}
                </span></TableCell>
                <TableCell >{row.methodLabel}</TableCell>
                {/* Kosong berarti BELUM DIUKUR, bukan nol — jangan ditulis "0 dB". */}
                <TableCell className="tnum">{row.lossDb == null ? '—' : `${row.lossDb.toFixed(2)} dB`}</TableCell>
                {/* Jejak pekerjaannya: tiket yang menyuruh kotak ini dibuka, tangan yang
                    mengerjakannya, dan kapan. Sambungan lama (dibuat sebelum ini dicatat)
                    tak punya pelaksana — waktunya pun ikut disembunyikan, sebab yang
                    tersimpan cuma saat kolomnya ditambahkan, bukan saat serat dilas. */}
                <TableCell ><span className="stack" style={{ gap: '0.15rem' }}>
                  {row.workOrderCode ? (
                    <Badge tone="accent">{row.workOrderCode}</Badge>
                  ) : (
                    <Text as="span" size={100} className="muted">tanpa tiket</Text>
                  )}
                  {row.splicedById ? (
                    <Text
                      as="span"
                      size={100}
                      className="muted"
                      title={new Date(row.splicedAt).toLocaleString('id-ID')}
                    >
                      {row.splicedByName ?? 'pengguna terhapus'} · {timeAgo(row.splicedAt)}
                    </Text>
                  ) : (
                    <Text as="span" size={100} className="muted">pelaksana tak tercatat</Text>
                  )}
                </span></TableCell>
                {canManage && (
                  <TableCell ><div className="row" style={{ gap: '0.3rem', justifyContent: 'flex-end' }}>
                    <Button
                      variant="subtle"
                      onClick={() => {
                        setEditing(row.id)
                        setEditMethod(row.method)
                        setEditLoss(row.lossDb == null ? '' : String(row.lossDb))
                        setEditNote(row.note ?? '')
                        setEditWorkOrder('')
                      }}
                      disabled={editing === row.id}
                    >
                      Ubah
                    </Button>
                    <Button variant="danger" onClick={() => void disconnect(row.id, label)}>
                      Lepas
                    </Button>
                  </div></TableCell>
                )}</TableRow>
                {editing === row.id && (
                  <TableRow><TableCell colSpan={canManage ? 6 : 5}><div className="splice-actions">
                    <SelectField
                      label="Metode"
                      value={editMethod}
                      onChange={(_, d) => setEditMethod(d.value as SpliceMethod)}
                    >
                      {METHODS.map((m) => (
                        <option key={m} value={m}>
                          {SPLICE_METHOD_LABEL[m]}
                        </option>
                      ))}
                    </SelectField>
                    <TextField
                      label="Rugi (dB)"
                      value={editLoss}
                      onChange={(_, d) => setEditLoss(d.value)}
                      placeholder="hasil ukur OTDR/splicer"
                      inputMode="decimal"
                    />
                    <TextField
                      label="Catatan"
                      value={editNote}
                      onChange={(_, d) => setEditNote(d.value)}
                      maxLength={200}
                    />
                    {/* Tiket boleh menyusul (hasil ukur kerap baru masuk keesokan harinya),
                        tapi yang sudah punya tiket tak ditawari pindah — server pun menolak. */}
                    {canPickWorkOrder && !row.workOrderId && (
                      <div className="stack" style={{ flex: 1, minWidth: 200, gap: '0.25rem' }}>
                        <Text as="span" size={200}>Bukukan ke work order</Text>
                        <Combobox
                          value={editWorkOrder}
                          onChange={(id) => setEditWorkOrder(id)}
                          fetchOptions={fetchWorkOrders}
                          toId={(wo) => wo.id}
                          toLabel={(wo) => `${wo.code} · ${wo.title}`}
                          toMeta={(wo) => wo.customerName ?? undefined}
                          placeholder="Biarkan kosong bila tak perlu"
                          emptyText="Tak ada work order terbuka"
                        />
                      </div>
                    )}
                    <Button
                      variant="primary"
                      disabled={busy}
                      onClick={() => void saveDetail(row.id)}
                    >
                      Simpan
                    </Button>
                    <Button variant="subtle" onClick={() => setEditing(null)}>
                      Batal
                    </Button>
                  </div></TableCell></TableRow>
                )}
              </Fragment>
            )
          })}</TableBody></Table>
        </div>
      )}
    </div>
  )
}
