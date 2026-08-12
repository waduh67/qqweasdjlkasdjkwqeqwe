import type { CableAttachmentRole, CableType, CableView } from '@/api/network'
import { canStartCableFrom, layerNodeKind, type NodeKind, type SnappedDevice, type ToolState } from './cableTool'

/**
 * Cara sebuah kabel DISEBUT: kode, nama jenis, panjang, dan badan permintaan yang
 * menyalin keadaannya apa adanya.
 *
 * Semuanya dipakai di lebih dari satu tempat — formulir penarikan, panel kabel,
 * bilah petunjuk saat menggambar — dan bila masing-masing menyusun kalimatnya
 * sendiri, layar yang satu menyebut ruas ini "Distribusi" sementara yang lain
 * "DIST", dan yang membaca keduanya mengira itu dua kabel.
 */

export function cableOriginOf(
  movable: { layer: string; id: string; code: string; lng: number; lat: number } | null,
  allowed: boolean,
): SnappedDevice | null {
  if (!movable || !allowed) return null
  const kind = layerNodeKind(movable.layer)
  if (!kind || !canStartCableFrom(kind)) return null
  return { kind, id: movable.id, code: movable.code, lng: movable.lng, lat: movable.lat }
}
/**
 * Badan permintaan PUT kabel yang menyalin keadaan sekarang apa adanya — dasar
 * untuk suntingan sepotong dari panel. API kabel memakai PUT utuh (bukan PATCH),
 * jadi bidang yang tak disebut akan terhapus; merakitnya di satu tempat menutup
 * kemungkinan satu pemanggil lupa membawa serta rute atau ujung-ujungnya.
 */
export function cableRequestBody(c: CableView) {
  return {
    code: c.code,
    name: c.name,
    cableType: c.cableType,
    coreCount: c.coreCount,
    route: c.route.points,
    fromKind: c.fromKind,
    fromId: c.fromId,
    toKind: c.toKind,
    toId: c.toId,
    fromPonPortId: c.fromPonPortId ?? undefined,
    fromPortNumber: c.fromPortNumber ?? undefined,
    toPortNumber: c.toPortNumber ?? undefined,
    status: c.status,
    installation: c.installation,
    ownership: c.ownership,
  }
}

/**
 * Nama jenis kabel dalam bahasa yang diucapkan orang. "DISTRIBUTION" itu nilai
 * enum, bukan kata yang dipakai teknisi saat menyebut ruas di depannya.
 */
export const TYPE_LABEL: Record<CableType, string> = {
  BACKBONE: 'Backbone',
  FEEDER: 'Feeder',
  DISTRIBUTION: 'Distribusi',
  DROP: 'Drop',
}

/**
 * Tebakan jumlah core per jenis — angka yang paling sering benar, bukan batas.
 * Backbone berkapasitas besar karena ia dipasang sekali untuk belasan tahun ke
 * depan: menariknya ulang jauh lebih mahal daripada membeli core cadangan.
 */
export const DEFAULT_CORES: Record<CableType, number> = { BACKBONE: 96, FEEDER: 24, DISTRIBUTION: 12, DROP: 1 }

/** Awalan kode per jenis — cermin [CableNaming] di server, supaya isian form = yang tersimpan. */
export const CODE_PREFIX: Record<CableType, string> = { BACKBONE: 'BB', FEEDER: 'FDR', DISTRIBUTION: 'DIST', DROP: 'DROP' }

/** Batas panjang kode yang ditegakkan domain; lebih dari ini ditolak server. */
export const CODE_MAX = 40

/**
 * Merakit kode kabel dari jenis + kode kedua ujungnya — bentuk yang bisa DIUCAPKAN
 * lewat radio dan ditulis tangan di label selubung ("DIST-ODC-JKT-01-ODP-07").
 *
 * Aturannya sengaja sama persis dengan `CableNaming` di server: yang tampil di kolom
 * inilah yang dikirim, jadi operator tak pernah melihat satu kode lalu menemukan kode
 * lain tersimpan. Ujung yang kepanjangan dipangkas dari DEPAN — bagian pembeda sebuah
 * kode aset (nomor urutnya) selalu ada di belakang.
 */
export function autoCableCode(type: CableType, parts: string[]): string {
  const prefix = CODE_PREFIX[type]
  const cleaned = parts
    .map((p) => p.toUpperCase().replace(/[^A-Z0-9._/-]/g, '-').replace(/-{2,}/g, '-').replace(/^-+|-+$/g, ''))
    .filter((p) => p !== '')
  if (cleaned.length === 0) return prefix
  const perPart = Math.max(1, Math.floor((CODE_MAX - prefix.length - cleaned.length) / cleaned.length))
  const trimmed = cleaned.map((p) => p.slice(-perPart).replace(/^-+|-+$/g, '')).filter((p) => p !== '')
  return [prefix, ...trimmed].join('-')
}

export function formatLength(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`
}

export function drawHint(state: ToolState): string {
  if (!state.from) return 'Klik perangkat sumber (POP, ODC, atau ODP)'
  if (!state.to) return `Dari ${state.from.code} — klik titik belok, lalu klik perangkat tujuan`
  const singgah = state.waypoints.length
  const mampir = singgah > 0 ? ` · mampir di ${state.waypoints.map((w) => w.code).join(', ')}` : ''
  // Ujung yang tak bisa dibuka orang (rumah pelanggan, POP, badan OLT) memang
  // akhir bentang: dikatakan terang-terangan supaya operator tak menunggu-nunggu
  // gambarnya bisa diteruskan.
  if (!state.canContinue) return `Sampai ${state.to.code} — kabelnya berhenti di sini${mampir}. Isi detail kabelnya.`
  // Kalimat ketiga menyebut cara menerus, sebab di situlah nilai terbesarnya:
  // satu selubung yang mampir di banyak kotak jauh lebih jujur daripada rantai
  // kabel pendek, tapi tak ada yang menebak gerakannya kalau tak diberi tahu.
  return `Sampai ${state.to.code} — isi detail kabelnya${mampir}. Selubungnya menerus? Klik kotak berikutnya atau tekan "Teruskan selubung".`
}

/**
 * Singgahan hasil gambar dalam bentuk yang dikirim server. Peran bawaannya
 * DIKUPAS, sebab kotak yang sengaja diklik operator saat menarik kabel hampir
 * selalu kotak yang memang dibuka untuk mengambil core — yang cuma dilewati
 * biasanya baru ketahuan belakangan, saat kotaknya dibuka teknisi lain.
 */
export function waypointCommands(
  waypoints: SnappedDevice[],
  roles: Record<string, CableAttachmentRole>,
): Array<{ nodeKind: NodeKind; nodeId: string; role: CableAttachmentRole }> {
  return waypoints.map((w) => ({ nodeKind: w.kind, nodeId: w.id, role: roles[w.id] ?? 'TAPPED' }))
}

/** Port keluaran sumber yang dipilih: PON port OLT (ponPortId) atau kaki/slot (portNumber). */
export type SourcePort = { ponPortId: string | null; portNumber: number | null }
