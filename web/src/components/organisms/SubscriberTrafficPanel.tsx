import { useEffect, useState } from 'react'
import { getBrasTraffic, type TrafficHistoryView } from '@/api/bng'
import { Segmented, TrafficChart } from '@/components/atoms'

/**
 * Panel trafik satu akun jaringan: throughput "sekarang" (Down/Up), total pemakaian data,
 * pemilih rentang, dan grafik tren. Membungkus [TrafficChart] dengan kendali rentang + ringkasan
 * hidup agar dipakai sama di tab "Trafik" (semua akun pelanggan) maupun panel B-ras Check.
 *
 * Murni baca — datanya dari akunting RADIUS yang diserap server; panel tak menyentuh BRAS. Tarik
 * ulang saat akun atau rentang berganti; toleran gagal (satu tarikan kosong tak menutup panel).
 */

const WINDOWS = [
  { label: '6 jam', hours: 6 },
  { label: '24 jam', hours: 24 },
  { label: '7 hari', hours: 168 },
] as const

export function SubscriberTrafficPanel({ accessId }: { accessId: string }) {
  const [hours, setHours] = useState<number>(24)
  const [traffic, setTraffic] = useState<TrafficHistoryView | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true); setTraffic(null)
    void getBrasTraffic(accessId, hours)
      .then((t) => {
        if (!alive) return
        setTraffic(t)
        setLoading(false)
      })
      .catch(() => {
        if (alive) { setTraffic(null); setLoading(false) }
      })
    return () => { alive = false }
  }, [accessId, hours])
  const activeLabel = WINDOWS.find(window => window.hours === hours)?.label ?? `${hours} jam`
  return <section className="customer-section" aria-label="Tren trafik">
    <div className="customer-filters"><Segmented ariaLabel="Rentang waktu tren" value={hours} onChange={setHours}
      options={WINDOWS.map(window => ({ value: window.hours, label: window.label }))} /></div>
    <div className="stat-grid">
      {[['Unduh terkini', fmtMbps(traffic?.currentDownMbps ?? null)], ['Unggah terkini', fmtMbps(traffic?.currentUpMbps ?? null)], [`Pemakaian ${activeLabel}`, fmtDataUsage(traffic?.totalBytes ?? null)]].map(([label, value]) =>
        <div className="stat" key={label}><div className="stat-label">{label}</div><div className="stat-value">{value}</div></div>)}
    </div>
    {loading ? <p role="status">Memuat tren…</p> : traffic ? <div className="customer-chart"><TrafficChart points={traffic.points} /></div> : <p role="alert">Tren trafik gagal dimuat.</p>}
  </section>
}

/** Laju Mbps ringkas; null (akun offline / belum terhitung) → "—". */
function fmtMbps(v: number | null): string {
  return v != null ? `${v.toFixed(1)} Mbps` : '—'
}

/** Total pemakaian data (basis 1000, selaras app) sampai TB; null → "—". */
function fmtDataUsage(bytes: number | null): string {
  if (bytes == null) return '—'
  if (bytes >= 1_000_000_000_000) return `${(bytes / 1_000_000_000_000).toFixed(2)} TB`
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(2)} GB`
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`
  if (bytes >= 1_000) return `${(bytes / 1_000).toFixed(0)} KB`
  return `${bytes} B`
}
