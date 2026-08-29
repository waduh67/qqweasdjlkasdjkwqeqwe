import { useEffect, useState } from 'react'
import { typographyStyles } from '@fluentui/react-components'
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
    setLoading(true)
    void getBrasTraffic(accessId, hours)
      .then((t) => {
        if (!alive) return
        setTraffic(t)
        setLoading(false)
      })
      .catch(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [accessId, hours])

  const activeLabel = WINDOWS.find((w) => w.hours === hours)?.label ?? `${hours} jam`

  return (
    <div className="stack" style={{ gap: '0.6rem' }}>
      <div className="spread" style={{ alignItems: 'center', flexWrap: 'wrap', gap: '0.5rem' }}>
        {/* Ringkasan hidup: throughput terakhir terhitung + pemakaian data pada rentang. */}
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="badge neutral tnum" title="Throughput unduh terakhir terhitung">
            ↓ {fmtMbps(traffic?.currentDownMbps ?? null)}
          </span>
          <span className="badge neutral tnum" title="Throughput unggah terakhir terhitung">
            ↑ {fmtMbps(traffic?.currentUpMbps ?? null)}
          </span>
          <span className="muted" style={{ ...typographyStyles.caption1 }}>
            Pemakaian {activeLabel}: <strong className="tnum">{fmtDataUsage(traffic?.totalBytes ?? null)}</strong>
          </span>
        </div>

        {/* Pemilih rentang: memicu tarik ulang lewat state [hours]. */}
        <Segmented
          ariaLabel="Rentang waktu tren"
          value={hours}
          onChange={setHours}
          options={WINDOWS.map((w) => ({ value: w.hours, label: w.label }))}
        />
      </div>

      {loading && !traffic ? (
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>Memuat tren…</p>
      ) : traffic ? (
        <TrafficChart points={traffic.points} />
      ) : (
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>Tren trafik tak tersedia.</p>
      )}
    </div>
  )
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
