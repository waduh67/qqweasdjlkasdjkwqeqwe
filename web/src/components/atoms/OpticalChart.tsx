import { useState } from 'react'
import type { HistoryPoint } from '@/api/monitoring'

/**
 * Grafik tren redaman terima satu ONU dari waktu ke waktu.
 *
 * Deret tunggal, jadi tidak perlu legenda (judulnya sudah menamainya) maupun
 * palet kategorikal — garis memakai warna seri biru. Yang membuat grafik ini
 * berguna secara operasional adalah dua garis ambang berkode status
 * (peringatan −25 dBm, kritis −27 dBm): titik yang jatuh ke bawahnya langsung
 * terbaca sebagai masalah, bukan sekadar angka. Ambang dipasangkan label, tidak
 * mengandalkan warna saja.
 *
 * Digambar dengan SVG polos — tanpa pustaka chart — agar tetap sejalan dengan
 * token tema dan tidak menambah beban bundel.
 */

const WARN_DBM = -25
const CRIT_DBM = -27

interface Props {
  points: HistoryPoint[]
}

export function OpticalChart({ points }: Props) {
  const [hover, setHover] = useState<number | null>(null)

  const measured = points.filter((p) => p.rxPowerDbm != null)
  if (measured.length < 2) {
    return (
      <p className="muted" style={{ padding: '1rem 0', textAlign: 'center' }}>
        Belum cukup data untuk menggambar tren (perlu ≥ 2 pengukuran).
      </p>
    )
  }

  const width = 640
  const height = 240
  const pad = { top: 16, right: 16, bottom: 26, left: 42 }
  const plotW = width - pad.left - pad.right
  const plotH = height - pad.top - pad.bottom

  const times = measured.map((p) => new Date(p.time).getTime())
  const values = measured.map((p) => p.rxPowerDbm as number)
  const tMin = Math.min(...times)
  const tMax = Math.max(...times)
  // Sumbu-y dipaksa mencakup kedua ambang agar zona bahaya selalu terlihat
  // meski semua pembacaan kebetulan sehat.
  const yMin = Math.min(...values, CRIT_DBM) - 1.5
  const yMax = Math.max(...values, -18) + 1.5

  const x = (t: number) => pad.left + (tMax === tMin ? plotW / 2 : ((t - tMin) / (tMax - tMin)) * plotW)
  const y = (v: number) => pad.top + (1 - (v - yMin) / (yMax - yMin)) * plotH

  const line = measured.map((_, i) => `${i === 0 ? 'M' : 'L'} ${x(times[i])} ${y(values[i])}`).join(' ')
  const area = `${line} L ${x(tMax)} ${y(yMin)} L ${x(tMin)} ${y(yMin)} Z`

  const yTicks = niceTicks(yMin, yMax, 4)
  const active = hover != null ? measured[hover] : null

  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        width="100%"
        style={{ overflow: 'visible' }}
        role="img"
        aria-label="Tren redaman terima ONU"
      >
        <defs>
          <linearGradient id="rxfill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.18" />
            <stop offset="100%" stopColor="var(--accent)" stopOpacity="0" />
          </linearGradient>
        </defs>

        {/* Gridline resesif + label sumbu-y */}
        {yTicks.map((tick) => (
          <g key={tick}>
            <line
              x1={pad.left}
              x2={width - pad.right}
              y1={y(tick)}
              y2={y(tick)}
              stroke="var(--border)"
              strokeWidth={1}
            />
            <text x={pad.left - 8} y={y(tick) + 3.5} textAnchor="end" fontSize="10" fill="var(--muted)">
              {tick}
            </text>
          </g>
        ))}

        {/* Ambang berkode status, dengan label — tak mengandalkan warna saja */}
        <ThresholdLine y={y(WARN_DBM)} x2={width - pad.right} xLabel={width - pad.right} color="var(--warning)" label="peringatan −25" />
        <ThresholdLine y={y(CRIT_DBM)} x2={width - pad.right} xLabel={width - pad.right} color="var(--critical)" label="kritis −27" />

        {/* Area + garis 2px */}
        <path d={area} fill="url(#rxfill)" />
        <path d={line} fill="none" stroke="var(--accent)" strokeWidth={2} strokeLinejoin="round" />

        {/* Crosshair + penanda saat hover */}
        {active?.rxPowerDbm != null && (
          <g>
            <line
              x1={x(new Date(active.time).getTime())}
              x2={x(new Date(active.time).getTime())}
              y1={pad.top}
              y2={pad.top + plotH}
              stroke="var(--muted)"
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            <circle
              cx={x(new Date(active.time).getTime())}
              cy={y(active.rxPowerDbm)}
              r={4.5}
              fill="var(--accent)"
              stroke="var(--surface)"
              strokeWidth={2}
            />
          </g>
        )}

        {/* Lapisan hit transparan untuk hover per titik */}
        {measured.map((_, i) => (
          <rect
            key={i}
            x={x(times[i]) - plotW / measured.length / 2}
            y={pad.top}
            width={plotW / measured.length}
            height={plotH}
            fill="transparent"
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover((h) => (h === i ? null : h))}
          />
        ))}

        {/* Label sumbu-x: awal & akhir */}
        <text x={pad.left} y={height - 8} fontSize="10" fill="var(--muted)">
          {formatTime(tMin)}
        </text>
        <text x={width - pad.right} y={height - 8} textAnchor="end" fontSize="10" fill="var(--muted)">
          {formatTime(tMax)}
        </text>
      </svg>

      {active?.rxPowerDbm != null ? (
        <div className="row" style={{ justifyContent: 'center', gap: '1rem', fontSize: '0.85rem' }}>
          <span className="muted">{new Date(active.time).toLocaleString('id-ID')}</span>
          <strong style={{ color: zoneColor(active.rxPowerDbm) }}>{active.rxPowerDbm} dBm</strong>
          <span className="badge">{active.status}</span>
        </div>
      ) : (
        <p className="muted" style={{ textAlign: 'center', fontSize: '0.82rem', margin: 0 }}>
          Arahkan kursor ke grafik untuk melihat nilai per waktu.
        </p>
      )}
    </div>
  )
}

function ThresholdLine({
  y,
  x2,
  xLabel,
  color,
  label,
}: {
  y: number
  x2: number
  xLabel: number
  color: string
  label: string
}) {
  return (
    <g>
      <line x1={42} x2={x2} y1={y} y2={y} stroke={color} strokeWidth={1.25} strokeDasharray="5 4" opacity={0.8} />
      <text x={xLabel} y={y - 4} textAnchor="end" fontSize="9.5" fill={color} fontWeight={600}>
        {label}
      </text>
    </g>
  )
}

function zoneColor(dbm: number): string {
  if (dbm <= CRIT_DBM) return 'var(--critical-ink)'
  if (dbm <= WARN_DBM) return 'var(--warning-ink)'
  return 'var(--good-ink)'
}

/** Tick sumbu-y yang “bulat” secukupnya. */
function niceTicks(min: number, max: number, count: number): number[] {
  const step = Math.ceil((max - min) / count)
  const start = Math.ceil(min / step) * step
  const ticks: number[] = []
  for (let v = start; v <= max; v += step) ticks.push(v)
  return ticks
}

function formatTime(t: number): string {
  return new Date(t).toLocaleString('id-ID', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}
