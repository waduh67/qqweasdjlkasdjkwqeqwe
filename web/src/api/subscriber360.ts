import { api } from './client'

/**
 * Tipe & panggilan endpoint agregat Subscriber-360. Cermin `Subscriber360Controller`/
 * `Subscriber360View` di server: satu panggilan menyusun pandangan 360° pelanggan
 * (identitas + langganan + penempatan + sesi PPPoE + rekening + CPE + work order),
 * menggantikan fan-out lintas-modul di sisi klien.
 *
 * Tiap facet lintas-modul digating izin modulnya sendiri di server. Facet yang tak
 * diizinkan bernilai null/kosong dan ditandai di [access] — UI membedakan "tak boleh
 * lihat" (kartu terkunci) dari "memang kosong".
 */

/** Titik WGS-84, urutan [lon, lat] mengikuti GeoJSON/PostGIS. */
export interface Coordinate {
  longitude: number
  latitude: number
}

/** Identitas ringkas pelanggan (facet inti, selalu ada). */
export interface Sub360Customer {
  id: string
  code: string
  name: string
  phone: string | null
  location: Coordinate
  status: string
}

/** Satu langganan pelanggan; `planId` merujuk paket di modul catalog. */
export interface Sub360Subscription {
  id: string
  customerId: string
  planId: string | null
  packageName: string
  bandwidthMbps: number
  status: string
}

/** Penempatan fisik ONU pelanggan (di ODP mana, port berapa) + kondisi optik. */
export interface Sub360Placement {
  onuId: string
  odpId: string
  portNumber: number
  onuSerialNumber: string
  onuStatus: string
  opticalHealth: string
  installRxPowerDbm: number | null
}

/**
 * Sesi PPPoE terkini pelanggan. `rateProfileName` = nama paket yang menempel pada akun.
 * Waktu berformat ISO UTC — UI menyesuaikan zona.
 */
export interface Sub360Session {
  subscriberAccessId: string
  username: string
  accessStatus: string
  rateProfileName: string | null
  online: boolean
  framedIp: string | null
  nasId: string | null
  nasName: string | null
  nasIp: string | null
  uptimeSeconds: number | null
  startedAt: string | null
  lastSeenAt: string | null
}

/**
 * Ringkasan rekening — tunggakan dihitung di SERVER (bukan lagi di klien). `outstanding`
 * = OVERDUE atau ISSUED yang lewat jatuh tempo. `outstandingAmount` string (BigDecimal),
 * pakai `Number(...)` saat perlu berhitung. `oldestDueDate` tanggal (YYYY-MM-DD),
 * `lastPaidAt` ISO UTC.
 */
export interface Sub360BillingSummary {
  customerId: string
  outstandingAmount: string
  outstandingCount: number
  unpaidCount: number
  oldestDueDate: string | null
  lastPaidAt: string | null
}

/** Status ringkas satu CPE; `online` dihitung server dari inform terakhir. */
export interface Sub360CpeDevice {
  deviceId: string
  serialNumber: string
  manufacturer: string | null
  model: string | null
  softwareVersion: string | null
  ipAddress: string | null
  lastInformAt: string | null
  online: boolean
}

/** Work order pasang-baru yang masih terbuka untuk pelanggan ini. */
export interface Sub360WorkOrder {
  id: string
  code: string
  customerId: string
  areaId: string | null
  scheduledAt: string | null
}

/**
 * Facet lintas-modul mana yang boleh dilihat pemanggil — bedakan null "ditolak" dari
 * null "kosong" pada [Subscriber360View].
 */
export interface Subscriber360Access {
  subscriptions: boolean
  placement: boolean
  session: boolean
  billing: boolean
  cpe: boolean
  workOrder: boolean
}

/**
 * Rakitan 360° pelanggan. Facet opsional bernilai null/kosong bila tak diizinkan (lihat
 * [access]) ATAU bila memang belum ada datanya. `cpeDevices` sengaja null (bukan `[]`)
 * saat ditolak, agar bisa dibedakan dari "tak punya CPE".
 */
export interface Subscriber360View {
  customer: Sub360Customer
  subscriptions: Sub360Subscription[]
  placement: Sub360Placement | null
  session: Sub360Session | null
  billing: Sub360BillingSummary | null
  cpeDevices: Sub360CpeDevice[] | null
  openWorkOrder: Sub360WorkOrder | null
  access: Subscriber360Access
}

/** Pandangan 360° satu pelanggan dalam satu panggilan. Izin: `customer.customer.view`. */
export const getSubscriber360 = (customerId: string) =>
  api.get<Subscriber360View>(`/api/subscriber-360/${customerId}`)
