/** Tipe respons module monitoring. */

export type CollectorStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED'
export type AlarmSeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type AlarmStatus = 'ACTIVE' | 'ACKNOWLEDGED' | 'CLEARED'

export interface CollectorView {
  id: string
  name: string
  status: CollectorStatus
  pollIntervalSeconds: number
  /** Delapan karakter awal API key; kuncinya sendiri tidak pernah dikirim ulang. */
  apiKeyHint: string
  agentVersion: string | null
  lastSeenAt: string | null
  lastCycleSummary: string | null
  silent: boolean
  assignedOltIds: string[]
}

/** Hanya respons pembuatan yang memuat API key mentah. */
export interface CollectorCreated {
  collector: CollectorView
  apiKey: string
}

export interface AlarmView {
  id: string
  kind: string
  kindDescription: string
  severity: AlarmSeverity
  status: AlarmStatus
  entityType: string
  entityId: string
  entityLabel: string
  message: string
  measuredValue: number | null
  raisedAt: string
  lastSeenAt: string
  clearedAt: string | null
  acknowledgedAt: string | null
  occurrenceCount: number
  openMinutes: number
}

export interface AlarmSummary {
  active: number
  acknowledged: number
  cleared: number
  bySeverity: Record<AlarmSeverity, number>
}

export interface MonitoringDashboard {
  collectors: number
  collectorsSilent: number
  metricsLast24h: number
  alarms: AlarmSummary
  recentAlarms: AlarmView[]
}

/**
 * Sebab putus terakhir ONU dari register OLT. Membedakan gangguan yang di layar
 * tampak sama tapi tindakannya beda — cukup selaras dengan enum `OnuDownCause`
 * di sisi server.
 */
export type OnuDownCause = 'DYING_GASP' | 'LOS' | 'LOB' | 'SIGNAL_FAIL' | 'ADMIN_DOWN' | 'UNKNOWN'

/** Label ringkas manusiawi untuk sebab putus, dipakai di badge "Ldc". */
export const DOWN_CAUSE_LABEL: Record<OnuDownCause, string> = {
  DYING_GASP: 'mati listrik',
  LOS: 'fiber putus',
  LOB: 'burst hilang',
  SIGNAL_FAIL: 'sinyal gagal',
  ADMIN_DOWN: 'dinonaktifkan',
  UNKNOWN: 'tak diketahui',
}

/** Bacaan live terbaru satu ONU — status dan sebab putus terakhir dari OLT. */
export interface OnuMetricView {
  onuId: string
  serialNumber: string
  time: string
  status: string
  rxPowerDbm: number | null
  txPowerDbm: number | null
  distanceMeters: number | null
  /** `DYING_GASP` vs `LOS` — mati listrik pelanggan vs fiber putus. */
  downCause: OnuDownCause | null
  /** Kapan ONU terakhir putus menurut register OLT; null bila tak dilaporkan. */
  lastOffAt: string | null
  /** Kapan ONU terakhir kembali online menurut register OLT. */
  lastOnAt: string | null
}

export interface HistoryPoint {
  time: string
  rxPowerDbm: number | null
  status: string
}

export interface OnuHistoryView {
  onuId: string
  points: HistoryPoint[]
  averageRxPowerDbm: number | null
  minRxPowerDbm: number | null
  maxRxPowerDbm: number | null
  /** Negatif berarti redaman memburuk; dasar pemeliharaan prediktif. */
  trendDbPerDay: number | null
  degrading: boolean
}

/** Tahap sebuah ONU di kotak masuk auto-provisioning. */
export type DiscoveredOnuState = 'DISCOVERED' | 'PROVISIONED' | 'IGNORED'

/** Seberapa yakin saran auto-link — menentukan cara UI menyajikannya. */
export type SuggestionConfidence = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE'

/**
 * Tebakan {pelanggan, ODP, port} untuk sebuah ONU liar. Field `null` berarti
 * bagian itu tak bisa ditebak dan harus diisi operator; `reason` menjelaskannya.
 */
export interface ProvisioningSuggestion {
  confidence: SuggestionConfidence
  customerId: string | null
  customerName: string | null
  odpId: string | null
  odpCode: string | null
  portNumber: number | null
  reason: string
}

/**
 * ONU yang dilaporkan OLT tapi belum terdaftar — perangkat liar yang menunggu
 * dituntaskan operator menjadi pelanggan terpasang.
 */
export interface DiscoveredOnuView {
  id: string
  serialNumber: string
  /** Kode OLT teresolusi ke id inventory; null bila kodenya belum dikenal. */
  oltId: string | null
  oltCode: string
  ponPortLabel: string | null
  lastStatus: string
  lastRxPowerDbm: number | null
  firstSeenAt: string
  lastSeenAt: string
  /** Berapa siklus polling melihat serial ini — sekali lewat vs benar-benar terpasang. */
  seenCount: number
  state: DiscoveredOnuState
  /** Tebakan auto-link; `null` untuk baris yang tak lagi menunggu tindakan. */
  suggestion: ProvisioningSuggestion | null
}
