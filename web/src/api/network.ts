/** Tipe respons module network, customer, dan gis. */

export interface Coordinate {
  longitude: number
  latitude: number
}

export type AssetStatus = 'PLANNED' | 'ACTIVE' | 'MAINTENANCE' | 'INACTIVE'

export type SnmpVersion = 'V1' | 'V2C' | 'V3'
export type WebProtocol = 'HTTP' | 'HTTPS'

export interface SiteView {
  id: string
  code: string
  name: string
  address: string | null
  location: Coordinate
  areaId: string | null
  oltCount: number
}

export interface OltView {
  id: string
  code: string
  name: string
  siteId: string
  siteName: string | null
  vendor: string
  model: string | null
  managementIp: string | null
  status: AssetStatus
  /** Server tidak pernah mengirim community string-nya, hanya ada/tidaknya. */
  snmpConfigured: boolean
  snmpPort: number
  pollable: boolean
  ponPortCount: number
  location: Coordinate
  areaId: string | null
  description: string | null
  snmpEnabled: boolean
  snmpVersion: SnmpVersion
  webEnabled: boolean
  webProtocol: WebProtocol
  webPort: number | null
  webUsername: string | null
  /** Server tidak pernah mengirim password Web-nya, hanya ada/tidaknya. */
  webPasswordConfigured: boolean
}

export interface PonPortView {
  id: string
  oltId: string
  label: string
  description: string | null
  status: AssetStatus
  odcCount: number
}

/**
 * Rak terminasi di dalam POP — tempat kabel luar BERHENTI.
 *
 * Serat dari lapangan tak pernah menempel langsung ke badan OLT: ia mati di rak,
 * dilas ke pigtail di sisi BELAKANG sebuah port, lalu sehelai patchcord pendek dari
 * sisi DEPAN port itu yang mencolok ke PON port. Karena itu satu port punya dua sisi,
 * dan dua angka di bawah menjawab dua pertanyaan berbeda: [usedPortCount] "masih ada
 * adapter kosong?", [spliceCount] "berapa banyak sambungan di dalamnya".
 */
export interface OdfView {
  id: string
  code: string
  name: string
  siteId: string
  siteName: string | null
  location: Coordinate
  areaId: string | null
  portCount: number
  /** Port yang salah satu sisinya sudah tersentuh — satu port dihitung sekali. */
  usedPortCount: number
  /** Sambungan di dalam rak; sisi belakang & depan dihitung sendiri-sendiri. */
  spliceCount: number
  status: AssetStatus
}

export interface OdcView {
  id: string
  code: string
  name: string
  address: string | null
  location: Coordinate
  areaId: string | null
  ponPortId: string | null
  ponPortLabel: string | null
  oltName: string | null
  /** Ringkasan isi kabinet, mis. "1:8", "1:8 ×2 · 1:16", atau "—" bila tanpa splitter. */
  splitterRatio: string
  /** Jumlah modul splitter di dalamnya — 0 berarti kabinet cross-connect. */
  splitterCount: number
  /** Total kaki keluaran seluruh modul; inilah kapasitas cabang yang sebenarnya. */
  splitterLegs: number
  capacity: number
  odpCount: number
  status: AssetStatus
  energized: boolean
}

export interface OdpView {
  id: string
  code: string
  name: string
  address: string | null
  location: Coordinate
  areaId: string | null
  odcId: string | null
  odcName: string | null
  /** Lihat [OdcView.splitterRatio] — ODP pun boleh berisi lebih dari satu modul. */
  splitterRatio: string
  splitterCount: number
  splitterLegs: number
  capacity: number
  status: AssetStatus
}

/**
 * Kotak sambung di tengah jalur — tempat dua haspel kabel bertemu, jalur bercabang
 * di persimpangan, atau kabel putus disambung darurat.
 *
 * Bedanya dengan ODC/ODP bukan sekadar nama: DI DALAMNYA TAK ADA SPLITTER, jadi tak
 * ada `splitterRatio` dan tak ada port keluaran yang bisa dicolok — serat masuk
 * disambung langsung ke serat keluar. Karena itu ukurannya dinyatakan dalam tray &
 * jumlah sambungan yang muat, bukan dalam kaki splitter.
 */
export interface JointBoxView {
  id: string
  code: string
  name: string
  address: string | null
  location: Coordinate
  areaId: string | null
  /** Jumlah tray (kaset) di dalam kotak — wadah fisik tempat sambungan ditata. */
  trayCount: number
  /** Batas jumlah sambungan yang muat di kotak ini. */
  capacity: number
  /** Sambungan yang sudah terpasang di dalamnya. */
  spliceCount: number
  status: AssetStatus
}

/**
 * Kotak tempat serat disambung. Bukan sekadar penamaan: ODC & ODP BOLEH berisi
 * modul splitter, ODF & joint box tidak — di dalam keduanya serat langsung
 * bertemu serat.
 */
export type ClosureKind = 'ODC' | 'ODP' | 'JOINT_BOX' | 'ODF'

/**
 * Satu MODUL splitter di dalam sebuah kabinet — benda yang bisa dipegang, bukan
 * kolom di baris ODC.
 *
 * Kabinet lapangan sungguhan sering berisi lebih dari satu: satu modul 1:8 untuk
 * cabang perumahan lama plus 1:16 untuk yang baru, atau splitter bertingkat
 * (kaki modul pertama jadi input modul kedua). Karena itu "kaki 3" baru punya
 * arti setelah jelas kaki modul yang mana — dan itu pula yang ditunjuk sebuah
 * sambungan serat, bukan kabinetnya.
 */
export interface SplitterView {
  id: string
  ownerKind: ClosureKind
  ownerId: string
  ownerCode: string | null
  /** Penanda modul di dalam kabinetnya: SPL-1, SPL-2, … */
  code: string
  /** Rasio siap-baca, mis. "1:8". */
  ratio: string
  legCount: number
  /** Redaman sisip modul ini (dB) — bawaan rasionya, bukan angka yang diketik. */
  insertionLossDb: number
  /** Nomor kaki yang sudah tersambung; sisanya bebas. */
  usedLegs: number[]
  /** Sisi masukan sudah dapat serat dari hulu. */
  inputConnected: boolean
  note: string | null
}

/** Isi sebuah kabinet: identitasnya plus seluruh modul di dalamnya. */
export interface ClosureSplitterView {
  ownerKind: ClosureKind
  ownerId: string
  ownerCode: string
  ownerName: string
  splitters: SplitterView[]
}

/** Rasio yang dijual di pasaran — dipakai semua form yang memasang splitter. */
export const SPLITTER_RATIOS = ['1:2', '1:4', '1:8', '1:16', '1:32', '1:64']

export type CustomerStatus = 'PROSPECT' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'
export type OnuStatus = 'PENDING' | 'ONLINE' | 'OFFLINE' | 'LOS' | 'DISMANTLED'
export type OpticalHealth = 'GOOD' | 'WARNING' | 'CRITICAL' | 'UNKNOWN'

/**
 * Label status ONU. `PENDING` sengaja dibaca "Belum terpantau", bukan "Pending":
 * status ONU hanya lahir dari pengamatan SNMP OLT, jadi PENDING berarti kabarnya
 * belum pernah sampai (OLT belum dipoll, atau serial yang terdaftar tak pernah
 * muncul di walk-nya) — bukan "mati". Membedakannya dari OFFLINE penting: yang
 * satu tak diketahui, yang satu benar-benar terpantau padam.
 */
export const ONU_STATUS_LABEL: Record<OnuStatus, string> = {
  PENDING: 'Belum terpantau',
  ONLINE: 'Online',
  OFFLINE: 'Offline',
  LOS: 'LOS',
  DISMANTLED: 'Dibongkar',
}

export function onuStatusLabel(status: string): string {
  return ONU_STATUS_LABEL[status as OnuStatus] ?? status
}

export interface OnuView {
  id: string
  customerId: string
  serialNumber: string
  model: string | null
  odpId: string | null
  odpCode: string | null
  odpPortNumber: number | null
  installRxPowerDbm: number | null
  opticalHealth: OpticalHealth
  status: OnuStatus
  installedAt: string | null
}

export interface SubscriptionView {
  id: string
  customerId: string
  /** Paket katalog sumber snapshot; null untuk langganan warisan. */
  planId: string | null
  packageName: string
  bandwidthMbps: number
  monthlyFee: string
  status: 'PENDING' | 'ACTIVE' | 'ISOLATED' | 'TERMINATED'
  activatedAt: string | null
  terminatedAt: string | null
}

export interface CustomerView {
  id: string
  code: string
  name: string
  phone: string | null
  email: string | null
  address: string
  location: Coordinate
  areaId: string | null
  idCardNumber: string | null
  status: CustomerStatus
  subscriptions: SubscriptionView[]
  onus: OnuView[]
  awaitingInstallation: boolean
}

/**
 * Pelanggan yang belum punya titik di peta — impor massal menaruhnya di koordinat
 * penampung (0,0). Sengaja bukan [CustomerView]: pemilih di peta hanya perlu cukup
 * untuk mengenali orangnya, bukan langganan & ONU-nya.
 */
export interface UnmappedCustomer {
  id: string
  code: string
  name: string
  address: string
  phone: string | null
  status: CustomerStatus
}

export interface OdpOccupant {
  portNumber: number
  customerId: string
  customerCode: string
  customerName: string
  phone: string | null
  location: Coordinate
  onuId: string
  onuSerialNumber: string
  onuStatus: OnuStatus
  opticalHealth: OpticalHealth
  installRxPowerDbm: number | null
  subscriptionPackage: string | null
  subscriptionStatus: string | null
}

export interface UpstreamView {
  odcCode: string | null
  odcName: string | null
  ponPortLabel: string | null
  oltCode: string | null
  oltName: string | null
  siteCode: string | null
  siteName: string | null
  complete: boolean
  splitterLossDb: number
}

export interface OdpInspection {
  odpId: string
  code: string
  name: string
  location: Coordinate
  capacity: number
  usedPorts: number
  availablePortNumbers: number[]
  utilizationPercent: number
  upstream: UpstreamView
  occupants: OdpOccupant[]
}

export interface SiteOlt {
  id: string
  code: string
  name: string
  vendor: string
  active: boolean
}

export interface SiteInspection {
  siteId: string
  code: string
  name: string
  address: string | null
  location: Coordinate
  oltCount: number
  odcCount: number
  odpCount: number
  customerCount: number
  olts: SiteOlt[]
}

export interface TraceHop {
  kind: string
  code: string
  name: string
  location: Coordinate | null
  /** Khusus hop BRAS: apakah sesi sedang online. `null` untuk hop non-BRAS. */
  online: boolean | null
  /** Keterangan inline siap-tampil (mis. "IP 100.64.0.5 · uptime 2j", "Rx −21.4 dBm"). */
  detail: string | null
}

/** Hop BRAS: identitas jaringan pelanggan (akun PPPoE) + keadaan sesi terkininya. */
export interface BrasHopView {
  /** Id akun jaringan — dipakai panel peta untuk menembak aksi (Reset Login) langsung. */
  accessId: string
  username: string
  accessStatus: string
  rateProfileName: string | null
  online: boolean
  framedIp: string | null
  nasName: string | null
  nasIp: string | null
  uptimeSeconds: number | null
  /** Mulai sesi yang sedang berjalan; `null` bila tak sedang online. */
  startedAt: string | null
  /** Terakhir kali BRAS melaporkan akun ini — saat offline, inilah "putus sejak kapan". */
  lastSeenAt: string | null
}

export interface CustomerTrace {
  customerId: string
  customerCode: string
  customerName: string
  location: Coordinate
  onuSerialNumber: string | null
  onuStatus: OnuStatus | null
  installRxPowerDbm: number | null
  opticalHealth: OpticalHealth | null
  odpPortNumber: number | null
  upstream: UpstreamView | null
  estimatedLossDb: number | null
  /** Puncak jalur (tempat sesi PPPoE ditutup, di atas OLT); `null` bila belum diprovisi PPPoE. */
  bras: BrasHopView | null
  /** Bacaan optik HIDUP ONU pelanggan dari monitoring; `null` bila belum pernah terbaca. */
  liveOnuStatus: OnuStatus | null
  liveRxPowerDbm: number | null
  distanceMeters: number | null
  hops: TraceHop[]
  /** Perangkat TR-069 pelanggan bila ada di ACS; `null` bila tak ada CPE tertaut. */
  cpeDeviceId: string | null
  cpeOnline: boolean | null
}

/** Satu tetangga sejalur: identitas + kondisi terpasang + bacaan hidup ONU-nya. */
export interface NeighborView {
  customerId: string
  customerCode: string
  customerName: string
  odpCode: string
  portNumber: number
  onuSerialNumber: string
  onuStatus: OnuStatus
  opticalHealth: OpticalHealth
  installRxPowerDbm: number | null
  liveStatus: OnuStatus | null
  liveRxPowerDbm: number | null
  distanceMeters: number | null
  downCause: string | null
  /** Baris pelanggan yang sedang ditelusur — untuk disorot di daftar. */
  self: boolean
}

/**
 * Tetangga sejalur seorang pelanggan dalam dua lingkup: se-ODP (paling dekat) dan
 * se-PON port (lebih luas, superset dari se-ODP). Kosong bila belum tersambung.
 */
export interface SubscriberNeighbors {
  customerId: string
  odpCode: string | null
  ponPortLabel: string | null
  sameOdp: NeighborView[]
  samePonPort: NeighborView[]
}

export type CableType = 'FEEDER' | 'DISTRIBUTION' | 'DROP'
export type NodeKind = 'SITE' | 'OLT' | 'ODF' | 'ODC' | 'ODP' | 'JOINT_BOX' | 'CUSTOMER'

/**
 * Cara kabel terpasang di lapangan. Bukan hiasan data: ia yang menentukan siapa
 * yang dikirim saat putus — tim tangga untuk jalur udara, tim galian untuk jalur
 * tanam. `null` berarti BELUM DISURVEI, dan sengaja tak ditebak.
 */
export type CableInstallation = 'AERIAL' | 'BURIED' | 'DUCT'

/** Milik sendiri atau numpang/sewa milik pihak lain (PLN, Telkom, sesama ISP). */
export type CableOwnership = 'OWNED' | 'LEASED'

/**
 * Label untuk PILIHAN di form — saat kabel belum ada, belum ada view dari server
 * yang bisa dipinjam labelnya. Untuk MENAMPILKAN kabel yang sudah tersimpan,
 * pakai `installationLabel`/`ownershipLabel` bawaan view supaya kata yang dibaca
 * operator hanya punya satu sumber.
 */
export const CABLE_INSTALLATION_LABEL: Record<CableInstallation, string> = {
  AERIAL: 'Udara (tiang)',
  BURIED: 'Tanam langsung',
  DUCT: 'Duct / HDPE',
}

export const CABLE_OWNERSHIP_LABEL: Record<CableOwnership, string> = {
  OWNED: 'Milik sendiri',
  LEASED: 'Sewa',
}

/** Bentuk `route` dari server: LineString sebagai daftar titik. */
export interface RoutePath {
  points: Coordinate[]
}

export interface CableView {
  id: string
  code: string
  name: string
  cableType: CableType
  coreCount: number
  route: RoutePath
  lengthMeters: number
  fromKind: NodeKind
  fromId: string
  toKind: NodeKind
  toId: string
  /** FEEDER: PON port OLT sumber; null bila feeder dari SITE / kabel legacy. */
  fromPonPortId: string | null
  /** Sumber: kaki splitter ODC / slot ODP; null bila legacy. */
  fromPortNumber: number | null
  /** Input tujuan; null bila tak dipilih (input tunggal). */
  toPortNumber: number | null
  /** Label siap-tampil port keluaran sumber, mis. "PON 1/1/1" / "Kaki 3" / "Slot 5". */
  fromPortLabel: string | null
  status: AssetStatus
  /** Cara pasang; null = belum disurvei (bukan "tak terpasang"). */
  installation: CableInstallation | null
  installationLabel: string | null
  ownership: CableOwnership
  ownershipLabel: string
}

export type CoreStatus = 'FREE' | 'USED' | 'RESERVED' | 'DAMAGED'

export const CORE_STATUS_LABEL: Record<CoreStatus, string> = {
  FREE: 'Bebas',
  USED: 'Terpakai',
  RESERVED: 'Dicadangkan',
  DAMAGED: 'Rusak',
}

/**
 * Sehelai core di dalam kabel — unit yang sesungguhnya menyalurkan layanan
 * (satu ODP = satu core). Warna datang dari server: itu warna FISIK selubung
 * serat menurut TIA-598, bukan token tema, jadi tak boleh ditebak ulang di sini.
 */
export interface CableCoreView {
  id: string
  tubeNumber: number
  coreNumber: number
  /** Posisi core dalam tube-nya — penentu warna; core 13 kembali ke posisi 1. */
  positionInTube: number
  color: string
  colorHex: string
  tubeColor: string
  tubeColorHex: string
  status: CoreStatus
  note: string | null
}

/** Barisan core sebuah kabel plus hitungan per status. */
export interface CableCoreList {
  cableId: string
  cableCode: string
  cableName: string
  coreCount: number
  coresPerTube: number
  free: number
  used: number
  reserved: number
  damaged: number
  cores: CableCoreView[]
}

/**
 * Satu pilihan port KELUARAN pada simpul sumber, untuk picker "colok dari port
 * mana" saat menarik kabel. `ponPortId` terisi untuk OLT (PON port), `portNumber`
 * untuk kaki splitter ODC / slot ODP. `occupied` menandai port yang sudah dipakai
 * kabel lain sehingga tak boleh dipilih lagi.
 */
export interface CablePortOption {
  ponPortId: string | null
  portNumber: number | null
  label: string
  occupied: boolean
  occupiedByCable: string | null
}

/**
 * Jenis titik yang bisa jadi ujung sebuah sambungan di dalam kotak. Yang penting
 * bukan namanya, melainkan bahwa keenamnya SETARA: sambungan menghubungkan dua
 * titik, apa pun jenisnya — core ke kaki splitter, core ke port ODF, port ODF ke
 * PON. Itu sebabnya satu layar bisa melayani ODF, ODC, ODP, dan joint box.
 */
export type ConnectionPointKind = 'CORE' | 'ODF_PORT' | 'SPLITTER_IN' | 'SPLITTER_OUT' | 'PON_PORT' | 'ONU'

/** Sisi port ODF: belakang menghadap kabel luar, depan menghadap perangkat. */
export type OdfPortSide = 'BACK' | 'FRONT'

export type SpliceMethod = 'FUSION' | 'MECHANICAL' | 'CONNECTOR'

export const SPLICE_METHOD_LABEL: Record<SpliceMethod, string> = {
  FUSION: 'Fusion (dilebur)',
  MECHANICAL: 'Mekanik',
  CONNECTOR: 'Konektor (patch)',
}

/** Satu ujung sambungan, sudah berlabel dari server — layar tak merangkai namanya sendiri. */
export interface FiberConnectionPointView {
  kind: ConnectionPointKind
  kindLabel: string
  label: string
  coreId: string | null
  cableId: string | null
  cableCode: string | null
  coreNumber: number | null
  colorHex: string | null
  nodeId: string | null
  portNumber: number | null
  portSide: OdfPortSide | null
}

export interface FiberConnectionView {
  id: string
  closureKind: ClosureKind
  closureId: string
  a: FiberConnectionPointView
  b: FiberConnectionPointView
  method: SpliceMethod
  methodLabel: string
  /** Rugi hasil ukur; null = belum diukur, bukan nol. */
  lossDb: number | null
  note: string | null
}

/**
 * Sehelai core dilihat DARI DALAM sebuah kotak: selain identitas seratnya,
 * yang menentukan bisa-tidaknya ia dipakai adalah apakah ia sudah tersambung —
 * di sini (`connectionId`) atau di kotak lain (`connectedElsewhere`).
 */
export interface SpliceCoreView {
  core: CableCoreView
  connectionId: string | null
  connectedElsewhere: boolean
}

/**
 * Kabel yang bisa dijangkau dari dalam kotak ini — termasuk yang cuma LEWAT di
 * depannya, bukan berujung di sini. Justru itu kejadian yang paling sering:
 * satu kabel distribusi 8 core melewati delapan ODP dan dikupas di tiap kotak
 * untuk mengambil satu core.
 */
export interface SpliceCableView {
  cableId: string
  code: string
  name: string
  cableType: CableType
  coreCount: number
  lengthMeters: number
  /** Kotak ini adalah ujung kabel (bukan sekadar dilewati). */
  terminatesHere: boolean
  /** Jarak titik kupas dari pangkal kabel, diukur menyusuri rutenya. */
  tapDistanceMeters: number
  /** Meleset berapa meter kotak ini dari garis rute — penanda survei kasar. */
  offsetMeters: number
  cores: SpliceCoreView[]
}

/**
 * Titik non-core di dalam kotak: kaki splitter (ODC/ODP), port ODF & PON port
 * (ODF). Daftarnya menyesuaikan jenis kotaknya, jadi joint box memang kosong —
 * di sana serat cuma bertemu serat.
 */
export interface SplicePointView {
  kind: ConnectionPointKind
  nodeId: string
  portNumber: number | null
  portSide: OdfPortSide | null
  label: string
  /** Judul kelompok, mis. "SPL-1 · 1:8" atau "Rak ODF-01". */
  group: string
  /** Sambungan yang memakainya; null = titik masih bebas. */
  connectionId: string | null
}

/** Seisi meja kerja splicing sebuah kotak — satu panggilan untuk satu layar. */
export interface SpliceWorkbenchView {
  closureKind: ClosureKind
  closureId: string
  closureCode: string
  closureName: string
  /** Batas jumlah sambungan yang muat; null = tak dibatasi (ODC/ODP). */
  spliceCapacity: number | null
  spliceCount: number
  cables: SpliceCableView[]
  points: SplicePointView[]
  connections: FiberConnectionView[]
}

/** Satu ujung sambungan dalam bentuk yang dikirim ke server. */
export interface ConnectionPointRequest {
  kind: ConnectionPointKind
  coreId?: string | null
  nodeId?: string | null
  portNumber?: number | null
  portSide?: OdfPortSide | null
}

/** Jenis satu langkah dalam jalur serat — apa yang dilewati cahaya, bukan di mana ia berada. */
export type FiberHopKind = 'PON_PORT' | 'FIBER' | 'SPLICE' | 'SPLITTER' | 'ODF_PORT' | 'ONU'

/**
 * Kenapa penelusuran berhenti. Yang penting bukan berhasil/gagal melainkan APA
 * yang ditemukan di ujung — jalur buntu adalah temuan, bukan galat.
 */
export type FiberTraceEnd =
  | 'SOURCE'
  | 'SUBSCRIBER'
  | 'DEAD_END'
  | 'AMBIGUOUS'
  | 'LOOP'
  | 'TOO_LONG'

export interface FiberHopView {
  kind: FiberHopKind
  kindLabel: string
  label: string
  detail: string
  lossDb: number
  cumulativeLossDb: number
  /** Rugi sambungan ini hasil UKUR, bukan angka tipikal cara pasangnya. */
  measured: boolean
  closureKind: ClosureKind | null
  closureId: string | null
  closureCode: string | null
  cableId: string | null
  nodeId: string | null
}

export interface FiberPathView {
  startLabel: string
  end: FiberTraceEnd
  endLabel: string
  /** Terurut SEARAH CAHAYA: PON port lebih dulu, ujung hilir paling belakang. */
  hops: FiberHopView[]
  totalLossDb: number
  budgetDb: number
  marginDb: number
  fiberMeters: number
  splitterCount: number
  spliceCount: number
  /** Sambungan yang rugimya masih perkiraan — penanda seberapa bisa dipercaya angkanya. */
  estimatedHops: number
  warnings: string[]
}

/** Satu kotak di hilir sebuah port PON beserta sumbangannya pada muatan port itu. */
export interface PonClosureLoadView {
  closureKind: ClosureKind
  closureId: string
  code: string
  name: string
  /** Berapa ruas serat dari port PON; 0 = rak POP, 1 = kabinet pertama. */
  depth: number
  splitterLegs: number
  usedLegs: number
  onuCount: number
}

/**
 * Muatan satu port PON terhadap plafon 64 ONU milik GPON.
 *
 * `fromSplicing` menentukan seberapa jauh angkanya boleh dipercaya: true berarti
 * dirangkai dari catatan sambungan yang sesungguhnya, false berarti dari tautan
 * ODC→PON port yang diisi tangan saat kabinet dibuat.
 */
export interface PonPortLoadView {
  ponPortId: string
  label: string
  oltId: string
  oltCode: string | null
  oltName: string | null
  closures: PonClosureLoadView[]
  splitterLegs: number
  usedLegs: number
  onuCount: number
  onuLimit: number
  loadPercent: number
  fromSplicing: boolean
  warnings: string[]
}

/** Satu alarm hidup yang membuat sebuah kabel merah — jawaban "kenapa" saat diklik. */
export interface ImpactCause {
  label: string
  kind: string
  severity: string
}

export interface ImpactedCable {
  id: string
  code: string
  cableType: CableType
  severity: string
  points: Coordinate[]
  causes: ImpactCause[]
}

/** Satu simpul (OLT/ODC/ODP/pelanggan) yang ikut terdampak — untuk mewarnai markernya merah. */
export interface ImpactedNode {
  /** Sama dengan id fitur pada vector tile, sehingga cukup dicocokkan lintas layer. */
  id: string
  severity: string
}

export interface ImpactedOverlay {
  cables: ImpactedCable[]
  nodes: ImpactedNode[]
}

/** Satu pelanggan di hilir sebuah ODC — sasaran blast radius & broadcast. */
export interface AffectedCustomer {
  customerId: string
  code: string
  name: string
  phone: string | null
  odpCode: string
  onuStatus: OnuStatus
  opticalHealth: OpticalHealth
}

/** Blast radius sebuah ODC: "kalau perangkat ini putus, siapa yang kena". */
export interface BlastRadiusView {
  odcId: string
  code: string
  name: string
  energized: boolean
  odpCount: number
  customerCount: number
  downCount: number
  customers: AffectedCustomer[]
}

/** Ruas kabel yang ikut lenyap dalam simulasi putus — untuk disorot di peta. */
export interface SeveredCable {
  id: string
  code: string
  cableType: CableType
  points: Coordinate[]
}

/** Utilisasi port satu ODP — dasar warna titik heatmap (hijau→kuning→merah). */
export interface OdpUtilization {
  odpId: string
  code: string
  name: string
  location: Coordinate
  capacity: number
  used: number
  /** Port terpakai / kapasitas, dibulatkan ke persen. */
  utilizationPercent: number
}

/** Heatmap utilisasi port seluruh ODP dalam jangkauan — untuk perencanaan kapasitas. */
export interface UtilizationHeatmap {
  odps: OdpUtilization[]
}

/** Satu ODC di bawah PON port dengan rekap utilisasi + ODP (FAT) anaknya. */
export interface PonOdcBranch {
  odcId: string
  code: string
  name: string
  /** Aktif dan punya uplink. */
  energized: boolean
  /** Kaki splitter ODC — kapasitas cabang distribusi. */
  legCapacity: number
  odpCount: number
  /** Total & terpakai port pelanggan di seluruh ODP anak. */
  capacity: number
  used: number
  utilizationPercent: number
  odps: OdpUtilization[]
}

/**
 * Drill-down utilisasi satu PON port: total port pelanggan di seluruh ODP di
 * bawahnya, plus rincian per ODC → ODP. Untuk perencanaan kapasitas dari detail OLT.
 */
export interface PonPortInspection {
  ponPortId: string
  label: string
  oltId: string
  odcCount: number
  odpCount: number
  capacity: number
  used: number
  utilizationPercent: number
  odcs: PonOdcBranch[]
}

/**
 * Satu ONU pelanggan di bawah sebuah OLT — perangkat + pemiliknya + di ODP/port mana.
 * `onuStatus` adalah status tercatat (disegarkan monitoring lewat write-back), bukan
 * tarikan hidup; jadi baris ini murni dari network + customer tanpa memanggil monitoring.
 */
export interface OltOnuRow {
  onuId: string
  serialNumber: string
  customerId: string
  customerCode: string
  customerName: string
  odpId: string
  odpCode: string
  portNumber: number
  onuStatus: OnuStatus
  opticalHealth: OpticalHealth
  /** Redaman baseline saat instalasi; null bila tak dicatat. */
  installRxPowerDbm: number | null
  subscriptionPackage: string | null
  subscriptionStatus: string | null
}

/** Daftar ONU pelanggan di bawah satu OLT — pandangan per-OLT untuk halaman detail OLT. */
export interface OltOnuList {
  oltId: string
  onuCount: number
  onus: OltOnuRow[]
}

/** Ujung kabel tempat pengukuran OTDR dimulai — hulu (awal jalur) atau hilir (akhir jalur). */
export type CableEnd = 'FROM' | 'TO'

/** Jenis peristiwa yang terbaca reflektometer OTDR. */
export type OtdrEventType = 'BREAK' | 'HIGH_LOSS' | 'REFLECTION' | 'SPLICE' | 'END'

/** Sebuah kotak yang berdiri di sepanjang kabel, dengan jaraknya dari pangkal (meter serat). */
export interface OtdrLandmark {
  closureKind: ClosureKind
  closureId: string
  code: string
  name: string
  distanceMeters: number
  /** Kotak ini salah satu ujung kabelnya, bukan sadapan di tengah bentang. */
  endpoint: boolean
}

/**
 * Angka OTDR yang sudah diterjemahkan jadi tempat — "jatuh di JB-03", bukan
 * "1.847 m". Pin di peta menuntun ke lokasi; nama kotak menentukan apa yang
 * dibawa tim, dan apakah perlu menggali sama sekali.
 */
export interface OtdrPlacement {
  summary: string
  /** Saran tindakan; terpisah dari ringkasan supaya daftar tetap enak dipindai. */
  advice: string | null
  atClosure: boolean
  nearestKind: ClosureKind | null
  nearestId: string | null
  nearestCode: string | null
  /** Selisih ke kotak terdekat; positif = sesudahnya (menjauh dari pangkal kabel). */
  offsetMeters: number | null
  beforeCode: string | null
  afterCode: string | null
  landmarks: OtdrLandmark[]
}

/** Satu hasil uji OTDR pada kabel, dengan titik perkiraan gangguan di jalurnya. */
export interface OtdrTest {
  id: string
  cableId: string
  distanceMeters: number
  measuredFrom: CableEnd
  eventType: OtdrEventType
  lossDb: number | null
  note: string | null
  recordedBy: string
  recordedByName: string | null
  recordedAt: string
  /** Titik perkiraan peristiwa di jalur kabel; `null` bila geometri tak bisa diresolusi. */
  estimatedPoint: Coordinate | null
  /** Jarak uji melampaui panjang kabel — titik dijepit ke ujung. */
  beyondCable: boolean
  cableLengthMeters: number
  /** Titik itu jatuh di kotak mana — jawaban yang dibawa ke lapangan. */
  placement: OtdrPlacement
}

/** Badan request untuk mencatat satu uji OTDR. */
export interface RecordOtdrTest {
  distanceMeters: number
  measuredFrom: CableEnd
  eventType: OtdrEventType
  lossDb?: number | null
  note?: string | null
}

/** Simulasi "kalau kabel ini putus, siapa yang kena" — hasil dari klik sebuah kabel. */
export interface CableCutView {
  cableId: string
  cableCode: string
  cableType: CableType
  /** Jenis simpul di ujung hilir yang terputus: ODC/ODP/CUSTOMER. */
  severedRootKind: NodeKind
  odcCount: number
  odpCount: number
  customerCount: number
  downCount: number
  customers: AffectedCustomer[]
  severedCables: SeveredCable[]
}
