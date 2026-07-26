import { useState } from 'react'
import type { TrafficPoint } from '../api/bng'

/**
 * Grafik tren trafik satu akun PPPoE: dua deret—unduh (Down) & unggah (Up), dalam
 * Mbps—dari waktu ke waktu. Beda dari OpticalChart yang satu deret: di sini ada
 * legenda karena warna garislah pembeda arah, dan sumbu-y selalu berbasis 0 (Mbps
 * tak pernah negatif) sehingga tinggi garis sebanding dengan besar trafik.
 *
 * Laju dihitung server dari selisih penghitung octet; titik yang tak terhitung
 * (reset penghitung, celah data) bernilai null → garis DIPUTUS di situ, bukan
 * ditarik lurus melewati lubang yang menyesatkan.
 *
 * Digambar SVG polos tanpa pustaka chart, selaras token tema — sama pendekatannya
 * dengan OpticalChart.
 */

const DOWN_COLOR = 'var(--accent)'
const UP_COLOR = 'var(--good-ink)'

interface Props {
  points: TrafficPoint[]
}

export function TrafficChart({ points }: Props) {
  const [hover, setHover] = useState<number | null>(null)

  // Butuh minimal dua titik berlaju untuk menggambar garis yang bermakna.
  const drawable = points.filter((p) => p.downMbps != null || p.upMbps != null)
  if (drawable.length < 2) {
    return (
      <p className="muted" style={{ padding: '1rem 0', textAlign: 'center' }}>
        Belum cukup data untuk menggambar tren (perlu ≥ 2 pembacaan trafik).
      </p>
    )
  }

  const width = 640
  const height = 240
  const pad = { top: 16, right: 16, bottom: 26, left: 46 }
  const plotW = width - pad.left - pad.right
  const plotH = height - pad.top - pad.bottom

  const times = points.map((p) => new Date(p.time).getTime())
  const tMin = Math.min(...times)
  const tMax = Math.max(...times)
  const values = points.flatMap((p) => [p.downMbps, p.upMbps].filter((v): v is number => v != null))
  // Sumbu-y dari 0 sampai puncak + sedikit ruang; minimal 1 Mbps agar garis nyaris
  // datar tak menempel ke sumbu.
  const yMax = Math.max(1, ...values) * 1.15

  const x = (t: number) => pad.left + (tMax === tMin ? plotW / 2 : ((t - tMin) / (tMax - tMin)) * plotW)
  const y = (v: number) => pad.top + (1 - v / yMax) * plotH

  const yTicks = niceTicks(yMax, 4)
  const active = hover != null ? points[hover] : null
  const activeX = active ? x(new Date(active.time).getTime()) : 0

  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      {/* Legenda: warna garis satu-satunya pembeda arah, jadi wajib dilabeli. */}
      <div className="row" style={{ gap: '1rem', justifyContent: 'flex-end', fontSize: '0.8rem' }}>
        <LegendKey color={DOWN_COLOR} label="Unduh (Down)" />
        <LegendKey color={UP_COLOR} label="Unggah (Up)" />
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        width="100%"
        style={{ overflow: 'visible' }}
        role="img"
        aria-label="Tren trafik unduh dan unggah"
      >
        {/* Gridline resesif + label sumbu-y (Mbps) */}
        {yTicks.map((tick) => (
          <g key={tick}>
            <line x1={pad.left} x2={width - pad.right} y1={y(tick)} y2={y(tick)} stroke="var(--border)" strokeWidth={1} />
            <text x={pad.left - 8} y={y(tick) + 3.5} textAnchor="end" fontSize="10" fill="var(--muted)">
              {tick}
            </text>
          </g>
        ))}

        {/* Dua deret; tiap rangkaian non-null jadi path sendiri → garis diputus di lubang. */}
        {segments(points, times, (p) => p.downMbps, x, y).map((d, i) => (
          <path key={`down-${i}`} d={d} fill="none" stroke={DOWN_COLOR} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {segments(points, times, (p) => p.upMbps, x, y).map((d, i) => (
          <path key={`up-${i}`} d={d} fill="none" stroke={UP_COLOR} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        ))}

        {/* Crosshair + penanda saat hover */}
        {active && (
          <line x1={activeX} x2={activeX} y1={pad.top} y2={pad.top + plotH} stroke="var(--muted)" strokeWidth={1} strokeDasharray="3 3" />
        )}
        {active?.downMbps != null && (
          <circle cx={activeX} cy={y(active.downMbps)} r={4} fill={DOWN_COLOR} stroke="var(--surface)" strokeWidth={2} />
        )}
        {active?.upMbps != null && (
          <circle cx={activeX} cy={y(active.upMbps)} r={4} fill={UP_COLOR} stroke="var(--surface)" strokeWidth={2} />
        )}

        {/* Lapisan hit transparan untuk hover per titik */}
        {points.map((_, i) => (
          <rect
            key={i}
            x={x(times[i]) - plotW / points.length / 2}
            y={pad.top}
            width={plotW / points.length}
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

      {active && (active.downMbps != null || active.upMbps != null) ? (
        <div className="row" style={{ justifyContent: 'center', gap: '1rem', fontSize: '0.85rem' }}>
          <span className="muted">{new Date(active.time).toLocaleString('id-ID')}</span>
          <strong style={{ color: DOWN_COLOR }}>↓ {fmtMbps(active.downMbps)}</strong>
          <strong style={{ color: UP_COLOR }}>↑ {fmtMbps(active.upMbps)}</strong>
        </div>
      ) : (
        <p className="muted" style={{ textAlign: 'center', fontSize: '0.82rem', margin: 0 }}>
          Arahkan kursor ke grafik untuk melihat laju per waktu.
        </p>
      )}
    </div>
  )
}

function LegendKey({ color, label }: { color: string; label: string }) {
  return (
    <span className="row" style={{ gap: '0.35rem', alignItems: 'center' }}>
      <span style={{ width: 14, height: 3, borderRadius: 2, background: color, display: 'inline-block' }} />
      <span className="muted">{label}</span>
    </span>
  )
}

/**
 * Memecah satu deret menjadi beberapa path — satu per rangkaian titik berlaju
 * (non-null) beruntun — agar garis diputus tepat di titik tak terhitung, bukan
 * ditarik lurus melewatinya. Rangkaian < 2 titik dibuang (tak membentuk garis).
 */
function segments(
  points: TrafficPoint[],
  times: number[],
  pick: (p: TrafficPoint) => number | null,
  x: (t: number) => number,
  y: (v: number) => number,
): string[] {
  const out: string[] = []
  let cur: string[] = []
  points.forEach((p, i) => {
    const v = pick(p)
    if (v == null) {
      if (cur.length >= 2) out.push(cur.join(' '))
      cur = []
      return
    }
    cur.push(`${cur.length === 0 ? 'M' : 'L'} ${x(times[i])} ${y(v)}`)
  })
  if (cur.length >= 2) out.push(cur.join(' '))
  return out
}

function fmtMbps(v: number | null): string {
  return v != null ? `${v.toFixed(1)} Mbps` : '—'
}

/** Tick sumbu-y “bulat” dari 0 sampai max. */
function niceTicks(max: number, count: number): number[] {
  const step = Math.max(1, Math.ceil(max / count))
  const ticks: number[] = []
  for (let v = 0; v <= max; v += step) ticks.push(v)
  return ticks
}

function formatTime(t: number): string {
  return new Date(t).toLocaleString('id-ID', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}
