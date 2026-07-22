/** Tipe respons module network, customer, dan gis. */

export interface Coordinate {
  longitude: number
  latitude: number
}

export type AssetStatus = 'PLANNED' | 'ACTIVE' | 'MAINTENANCE' | 'INACTIVE'

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
  pollable: boolean
  ponPortCount: number
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

export interface TraceHop {
  kind: string
  code: string
  name: string
  location: Coordinate | null
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
  hops: TraceHop[]
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
  status: AssetStatus
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

export interface ImpactedOverlay {
  cables: ImpactedCable[]
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
