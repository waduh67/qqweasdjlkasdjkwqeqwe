import { api } from './client'

/**
 * Katalog paket internet — SUMBER TUNGGAL harga + kecepatan + QoS + FUP + siklus billing.
 *
 * Server merakit atribut Mikrotik-Rate-Limit dari field terstruktur dan mengembalikannya
 * sebagai `rateLimit`/`fupRateLimit`, jadi operator tak pernah mengetik string profil.
 * [buildRateLimit] di sini menirukan generator server agar form bisa menampilkan preview
 * live SEBELUM disimpan; nilai final tetap datang dari server.
 */

/** Nama enum ServiceType server (ketersediaan paket; semua tipe kini di-enforce ke RADIUS). */
export type ServiceType = 'PPPOE' | 'STATIC' | 'HOTSPOT' | 'DHCP'

export const SERVICE_TYPES: ServiceType[] = ['PPPOE', 'STATIC', 'HOTSPOT', 'DHCP']

export const SERVICE_TYPE_LABEL: Record<ServiceType, string> = {
  PPPOE: 'PPPoE',
  STATIC: 'IP Statis',
  HOTSPOT: 'Hotspot',
  DHCP: 'DHCP',
}

/** Prioritas default MikroTik (1=tertinggi…8=terendah); nilai default tak ditulis ke string. */
export const DEFAULT_PRIORITY = 8

export type PlanView = {
  id: string
  name: string
  description: string | null
  price: number
  downMbps: number
  upMbps: number
  downBurstMbps: number | null
  upBurstMbps: number | null
  downThresholdMbps: number | null
  upThresholdMbps: number | null
  burstTimeSec: number | null
  downMinMbps: number | null
  upMinMbps: number | null
  priority: number
  connectionLimit: number | null
  fupEnabled: boolean
  fupQuotaMb: number | null
  fupDownMbps: number | null
  fupUpMbps: number | null
  serviceTypes: ServiceType[]
  prorateOnActivation: boolean | null
  billingDayOfMonth: number | null
  dueDays: number | null
  graceDays: number | null
  autoIsolir: boolean | null
  active: boolean
  /** Atribut Mikrotik-Rate-Limit siap-tulis yang dirakit server. */
  rateLimit: string
  /** Throttle grup FUP; null bila paket tak ber-FUP. */
  fupRateLimit: string | null
}

export type SavePlanRequest = {
  name: string
  description: string | null
  price: number
  downMbps: number
  upMbps: number
  downBurstMbps: number | null
  upBurstMbps: number | null
  downThresholdMbps: number | null
  upThresholdMbps: number | null
  burstTimeSec: number | null
  downMinMbps: number | null
  upMinMbps: number | null
  priority: number
  connectionLimit: number | null
  fupEnabled: boolean
  fupQuotaMb: number | null
  fupDownMbps: number | null
  fupUpMbps: number | null
  serviceTypes: ServiceType[]
  prorateOnActivation: boolean | null
  billingDayOfMonth: number | null
  dueDays: number | null
  graceDays: number | null
  autoIsolir: boolean | null
  active: boolean
}

export const listPlans = () => api.get<PlanView[]>('/api/catalog/plans')
export const getPlan = (id: string) => api.get<PlanView>(`/api/catalog/plans/${id}`)
export const createPlan = (body: SavePlanRequest) => api.post<PlanView>('/api/catalog/plans', body)
export const updatePlan = (id: string, body: SavePlanRequest) =>
  api.put<PlanView>(`/api/catalog/plans/${id}`, body)

/** Field numerik untuk preview (undefined = kosong/belum diisi). */
export type RateLimitInput = {
  downMbps?: number
  upMbps?: number
  downBurstMbps?: number
  upBurstMbps?: number
  downThresholdMbps?: number
  upThresholdMbps?: number
  burstTimeSec?: number
  downMinMbps?: number
  upMinMbps?: number
  priority?: number
}

const defined = (x?: number): number | undefined =>
  x != null && Number.isFinite(x) ? x : undefined

/**
 * Merakit string Mikrotik-Rate-Limit (urutan up/down = rx/tx) persis seperti server:
 * `rate  burst  threshold  time  priority  min-rate`, dipangkas dari kanan, dengan
 * grup kosong di tengah diisi placeholder. Kembalikan '' bila rate dasar belum lengkap.
 */
export function buildRateLimit(v: RateLimitInput): string {
  const down = defined(v.downMbps)
  const up = defined(v.upMbps)
  if (down == null || up == null) return ''

  const pair = (u?: number, d?: number): string | null => {
    const uu = defined(u)
    const dd = defined(d)
    return uu != null && dd != null ? `${uu}M/${dd}M` : null
  }

  const rate = `${up}M/${down}M`
  const burst = pair(v.upBurstMbps, v.downBurstMbps)
  const threshold = pair(v.upThresholdMbps, v.downThresholdMbps)
  const t = defined(v.burstTimeSec)
  const time = t != null ? `${t}/${t}` : null
  const p = defined(v.priority)
  const priorityToken = p != null && p !== DEFAULT_PRIORITY ? String(p) : null
  const minRate = pair(v.upMinMbps, v.downMinMbps)

  const groups = [rate, burst, threshold, time, priorityToken, minRate]
  const placeholders = [rate, '0M/0M', '0M/0M', '0/0', String(DEFAULT_PRIORITY), '0M/0M']
  let lastSet = 0
  groups.forEach((g, i) => {
    if (g != null) lastSet = i
  })
  return groups
    .slice(0, lastSet + 1)
    .map((g, i) => g ?? placeholders[i])
    .join(' ')
}

/** Throttle FUP (up/down); '' bila FUP mati atau kecepatan belum lengkap. */
export function buildFupRateLimit(v: {
  fupEnabled?: boolean
  fupDownMbps?: number
  fupUpMbps?: number
}): string {
  if (!v.fupEnabled) return ''
  const down = defined(v.fupDownMbps)
  const up = defined(v.fupUpMbps)
  return down != null && up != null ? `${up}M/${down}M` : ''
}
