import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { PonClosureLoadView, PonPortLoadView } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge, Spinner } from '@/components/atoms'
import type { Tone } from '@/components/atoms'

/**
 * Muatan satu port PON terhadap plafon 64 ONU milik GPON.
 *
 * Alasan panel ini ada: batas itu nyata dan keras — yang ke-65 tidak "pelan", ia
 * tak pernah dapat giliran bicara — tapi selama ini tak terlihat di mana pun.
 * Kabinet ditambah satu per satu, tiap penambahan terasa kecil, dan plafonnya
 * baru ketahuan saat ONU pelanggan baru menolak daftar dengan teknisi sudah
 * berdiri di rumahnya.
 *
 * Tiga hal yang sengaja ditampilkan berdampingan:
 *
 * 1. **Muatan terhadap plafon**, sebagai batang — bukan angka telanjang. "48 dari
 *    64" perlu dihitung dulu di kepala; batang yang tinggal seperempat kosong
 *    tidak.
 * 2. **Rantai kotaknya**, urut dari rak POP ke kotak pelanggan. Ini jawaban atas
 *    "port ini sebenarnya menyuapi apa saja" yang selama ini ditebak dari peta.
 * 3. **Dari mana angkanya**. Hitungan yang sumbernya disembunyikan lebih
 *    berbahaya daripada tak ada hitungan: tenant yang belum mendata splicing-nya
 *    berhak tahu bahwa yang ia baca adalah tautan lama, bukan kenyataan serat.
 */

/** Nada muatan. Kuning jauh sebelum penuh — yang perlu diperingatkan adalah orang yang sedang merencanakan ODP berikutnya. */
function loadTone(onuCount: number, limit: number): Tone {
  if (onuCount > limit) return 'critical'
  if (onuCount >= limit - 8) return 'warning'
  if (onuCount >= limit * 0.6) return 'serious'
  return 'good'
}

const TONE_COLOR: Record<Tone, string> = {
  neutral: 'var(--muted)',
  good: 'var(--good)',
  warning: 'var(--warning)',
  serious: 'var(--serious)',
  critical: 'var(--critical)',
  accent: 'var(--accent)',
}

/** Kotak dikelompokkan per jarak dari port: rak, lalu kabinet, lalu kotak pelanggan. */
function byDepth(closures: PonClosureLoadView[]): PonClosureLoadView[][] {
  const groups = new Map<number, PonClosureLoadView[]>()
  closures.forEach((c) => {
    const bucket = groups.get(c.depth)
    if (bucket) bucket.push(c)
    else groups.set(c.depth, [c])
  })
  return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(([, list]) => list)
}

export function PonPortLoadPanel({ ponPortId }: { ponPortId: string }) {
  const { can } = useCan()
  const canView = can('network.splice.view')
  const [load, setLoad] = useState<PonPortLoadView | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!canView) {
      setLoading(false)
      return
    }
    let alive = true
    api
      .get<PonPortLoadView>(`/api/fiber-trace/pon-port/${ponPortId}`)
      .then((d) => {
        if (alive) setLoad(d)
      })
      .catch(() => {
        /* muatan itu tambahan; drill-down ODC di bawahnya tetap berguna tanpanya */
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [canView, ponPortId])

  const chain = useMemo(() => (load ? byDepth(load.closures) : []), [load])

  if (!canView) return null
  if (loading) {
    return (
      <p className="muted row" style={{ margin: 0, fontSize: '0.85rem' }}>
        <Spinner /> Menghitung muatan port…
      </p>
    )
  }
  if (!load) return null

  const tone = loadTone(load.onuCount, load.onuLimit)
  const percent = Math.min(100, Math.round((load.onuCount / Math.max(1, load.onuLimit)) * 100))

  return (
    <div className="stack" style={{ gap: '0.45rem' }}>
      <div className="row wrap" style={{ gap: '0.5rem' }}>
        <Badge tone={tone}>
          {load.onuCount}/{load.onuLimit} ONU
        </Badge>
        <span className="muted tnum" style={{ fontSize: '0.82rem' }}>
          {load.usedLegs}/{load.splitterLegs} kaki splitter terpakai · {load.closures.length} kotak di hilir
        </span>
        {!load.fromSplicing && <Badge tone="neutral">dari tautan lama</Badge>}
      </div>

      {/* Batang muatan: sisa yang kosong itulah yang dicari orang, bukan angkanya. */}
      <div
        className="budget-bar"
        role="img"
        aria-label={`${load.onuCount} dari ${load.onuLimit} ONU terpakai`}
      >
        <span className="budget-block" style={{ width: `${percent}%`, background: TONE_COLOR[tone] }} />
      </div>

      {chain.length > 0 && (
        <div className="row wrap" style={{ gap: '0.3rem', fontSize: '0.82rem' }}>
          {chain.map((group, index) => (
            <span key={group[0].closureId} className="row wrap" style={{ gap: '0.3rem' }}>
              {index > 0 && (
                <span className="dim" aria-hidden>
                  →
                </span>
              )}
              {group.map((c) => (
                <span
                  key={c.closureId}
                  className="badge"
                  title={`${c.closureKind} · ${c.name}${
                    c.splitterLegs > 0 ? ` · ${c.usedLegs}/${c.splitterLegs} kaki` : ''
                  }`}
                >
                  {c.code}
                  {c.onuCount > 0 && <span className="dim"> · {c.onuCount} ONU</span>}
                </span>
              ))}
            </span>
          ))}
        </div>
      )}

      {load.warnings.map((w) => (
        <p
          key={w}
          className={w.startsWith('Port ini sudah') ? 'error' : 'muted'}
          style={{ margin: 0, fontSize: '0.82rem' }}
        >
          {w}
        </p>
      ))}
    </div>
  )
}
