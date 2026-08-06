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
  splitterRatio: string
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
  splitterRatio: string
  capacity: number
  status: AssetStatus
}

export type CustomerStatus = 'PROSPECT' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'
export type OnuStatus = 'PENDING' | 'ONLINE' | 'OFFLINE' | 'LOS' | 'DISMANTLED'
export type OpticalHealth = 'GOOD' | 'WARNING' | 'CRITICAL' | 'UNKNOWN'

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
  username: string
  accessStatus: string
  rateProfileName: string | null
  online: boolean
  framedIp: string | null
  nasName: string | null
  nasIp: string | null
  uptimeSeconds: number | null
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
export type NodeKind = 'SITE' | 'OLT' | 'ODC' | 'ODP' | 'CUSTOMER'

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

/** Ujung kabel tempat pengukuran OTDR dimulai — hulu (awal jalur) atau hilir (akhir jalur). */
export type CableEnd = 'FROM' | 'TO'

/** Jenis peristiwa yang terbaca reflektometer OTDR. */
export type OtdrEventType = 'BREAK' | 'HIGH_LOSS' | 'REFLECTION' | 'SPLICE' | 'END'

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
