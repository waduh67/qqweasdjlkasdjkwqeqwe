import { useCallback, useEffect, useMemo, useState } from 'react'
import { Route } from 'lucide-react'
import { api } from '@/api/client'
import type { ClosureKind, FiberHopKind, FiberPathView, FiberTraceEnd } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge, SelectField, Spinner } from '@/components/atoms'
import type { Tone } from '@/components/atoms'

/**
 * Jalur serat dari OLT sampai kotak ini, beserta ke mana anggaran redamannya habis.
 *
 * Ini jawaban atas dua pertanyaan yang selama ini dijawab dengan menebak dari
 * gambar peta: "kotak ini disuapi PON port yang mana" dan "berapa redaman sampai
 * ke sini". Keduanya sebenarnya sudah tercatat sejak meja kerja splicing dipakai —
 * yang belum ada cuma yang merangkainya jadi satu rantai.
 *
 * Yang membedakannya dari daftar sambungan biasa:
 *
 * 1. **Batang anggaran.** 28 dB jatah kelas B+ digambar sebagai satu track, dan
 *    tiap hop mengambil lebar sesuai rugi yang benar-benar ia sumbang. Orang
 *    langsung melihat bahwa satu splitter 1:8 memakan sepertiga jatah sementara
 *    dua kilometer serat cuma seujung kuku — pemahaman yang tak pernah muncul
 *    dari kolom angka.
 * 2. **Rantai searah cahaya.** PON port di atas, kotak ini di bawah; itulah arah
 *    yang dipakai orang saat menyusuri gangguan, bukan sebaliknya.
 * 3. **Perkiraan diakui sebagai perkiraan.** Sambungan yang belum diukur ditandai
 *    terang-terangan. Anggaran yang seluruhnya angka tipikal boleh jadi meleset
 *    beberapa dB, dan menyembunyikan itu jauh lebih berbahaya daripada menyebutnya.
 */

/** Bagian anggaran yang disumbang tiap jenis hop — pembeda blok di batang. */
const HOP_COLOR: Record<FiberHopKind, string> = {
  PON_PORT: 'var(--accent)',
  FIBER: 'var(--good)',
  SPLICE: 'var(--serious)',
  SPLITTER: 'var(--warning)',
  ODF_PORT: 'var(--accent)',
  ONU: 'var(--muted)',
}

/**
 * Nada ujung jalur. Buntu itu KUNING, bukan merah: kotak yang baru dipasang dan
 * belum disambung memang belum punya jalur, dan menyalakan alarm merah untuk
 * pekerjaan yang belum dikerjakan cuma melatih orang mengabaikan warna merah.
 */
const END_TONE: Record<FiberTraceEnd, Tone> = {
  SOURCE: 'good',
  SUBSCRIBER: 'good',
  DEAD_END: 'warning',
  AMBIGUOUS: 'critical',
  LOOP: 'critical',
  TOO_LONG: 'serious',
}

/** Sisa anggaran: di bawah 0 jalurnya mustahil, di bawah 3 dB ia hidup di ujung tanduk. */
function marginTone(marginDb: number): Tone {
  if (marginDb < 0) return 'critical'
  if (marginDb < 3) return 'warning'
  if (marginDb < 6) return 'serious'
  return 'good'
}

const db = (value: number) => `${value.toFixed(2)} dB`

const meters = (value: number) =>
  value >= 1_000 ? `${(value / 1_000).toFixed(2)} km` : `${Math.round(value)} m`

interface Props {
  closureKind: ClosureKind
  closureId: string
  /** Ikut dimuat ulang setiap kali sambungan di kotak ini berubah. */
  reloadKey?: number
}

export function FiberTracePanel({ closureKind, closureId, reloadKey }: Props) {
  const { can } = useCan()
  const canView = can('network.splice.view')
  const [paths, setPaths] = useState<FiberPathView[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [picked, setPicked] = useState(0)

  const load = useCallback(async () => {
    if (!canView) {
      setLoading(false)
      return
    }
    try {
      setPaths(
        await api.get<FiberPathView[]>(
          `/api/fiber-trace/closure?closureKind=${closureKind}&closureId=${closureId}`,
        ),
      )
    } catch {
      setPaths(null)
    } finally {
      setLoading(false)
    }
  }, [canView, closureKind, closureId])

  useEffect(() => {
    void load()
  }, [load, reloadKey])

  // Kotak yang splitternya dilepas bisa membuat pilihan menunjuk jalur yang
  // sudah tak ada — kembalikan ke jalur pertama daripada menampilkan kosong.
  useEffect(() => {
    if (paths && picked >= paths.length) setPicked(0)
  }, [paths, picked])

  const path = paths?.[picked]

  /**
   * Lebar tiap blok dihitung terhadap ANGGARAN, bukan terhadap total rugi:
   * batang yang selalu penuh tak memberi tahu apa pun. Yang ingin dilihat orang
   * adalah seberapa banyak jatah yang tersisa, dan itu cuma terlihat kalau
   * sisanya dibiarkan kosong.
   */
  const blocks = useMemo(() => {
    if (!path || path.budgetDb <= 0) return []
    return path.hops
      .filter((hop) => hop.lossDb > 0)
      .map((hop, index) => ({
        key: `${index}-${hop.label}`,
        percent: Math.min(100, (hop.lossDb / path.budgetDb) * 100),
        color: HOP_COLOR[hop.kind],
        title: `${hop.kindLabel} · ${hop.label} · ${db(hop.lossDb)}`,
      }))
  }, [path])

  if (!canView) return null

  if (loading) {
    return (
      <div className="card stack">
        <h3>Jalur & anggaran redaman</h3>
        <p className="muted row">
          <Spinner /> Menelusuri jalur…
        </p>
      </div>
    )
  }

  return (
    <div className="card stack">
      <div className="spread wrap">
        <h3>Jalur & anggaran redaman</h3>
        {paths && paths.length > 1 && (
          <SelectField
            aria-label="Pilih jalur"
            value={String(picked)}
            onChange={(e) => setPicked(Number(e.target.value))}
          >
            {paths.map((entry, index) => (
              <option key={entry.startLabel + index} value={index}>
                {entry.startLabel}
              </option>
            ))}
          </SelectField>
        )}
      </div>

      {!path && (
        <p className="muted">
          Belum ada titik yang bisa ditelusuri dari sini. Sambungkan dulu seratnya di meja kerja
          splicing di bawah — jalur dirangkai dari sambungan yang tercatat, bukan dari garis di peta.
        </p>
      )}

      {path && (
        <>
          <div className="row wrap">
            <Badge tone={END_TONE[path.end]}>{path.endLabel}</Badge>
            {path.end === 'SOURCE' && (
              <Badge tone={marginTone(path.marginDb)}>Sisa {db(path.marginDb)}</Badge>
            )}
            <span className="muted">
              {db(path.totalLossDb)} dari {db(path.budgetDb)} · {meters(path.fiberMeters)} serat ·{' '}
              {path.splitterCount} splitter · {path.spliceCount} sambungan
            </span>
          </div>

          {/* Batang anggaran: tiap blok selebar rugi yang ia sumbang, sisanya jatah
              yang belum terpakai. Judulnya dititipkan ke `title` supaya angkanya
              tetap terbaca tanpa memenuhi layar dengan label. */}
          <div className="budget-bar" role="img" aria-label={`Terpakai ${db(path.totalLossDb)} dari ${db(path.budgetDb)}`}>
            {blocks.map((block) => (
              <span
                key={block.key}
                className="budget-block"
                style={{ width: `${block.percent}%`, background: block.color }}
                title={block.title}
              />
            ))}
          </div>

          {path.warnings.map((warning) => (
            <p key={warning} className={warning.startsWith('Anggaran') ? 'error' : 'muted'}>
              {warning}
            </p>
          ))}

          {path.hops.length > 0 && (
            <ol className="hop-chain">
              {path.hops.map((hop, index) => (
                <li key={`${index}-${hop.label}`} className="hop">
                  <span className="hop-dot" style={{ background: HOP_COLOR[hop.kind] }} />
                  <div className="hop-body">
                    <div className="spread wrap">
                      <strong>{hop.label}</strong>
                      <span className="muted">
                        {hop.lossDb > 0 ? `+${db(hop.lossDb)}` : '—'}
                      </span>
                    </div>
                    <div className="spread wrap">
                      <span className="dim">
                        {hop.kindLabel} · {hop.detail}
                        {hop.kind === 'SPLICE' && !hop.measured && ' · belum diukur'}
                      </span>
                      <span className="dim">{db(hop.cumulativeLossDb)}</span>
                    </div>
                  </div>
                </li>
              ))}
            </ol>
          )}

          <p className="dim row">
            <Route size={14} aria-hidden />
            Dibaca searah cahaya: PON port di atas, kotak ini di bawah.
          </p>
        </>
      )}
    </div>
  )
}
