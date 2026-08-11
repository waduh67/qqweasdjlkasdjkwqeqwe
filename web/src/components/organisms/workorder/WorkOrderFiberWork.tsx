/**
 * Pekerjaan serat yang dibukukan ke sebuah work order — kotak apa saja yang dibuka
 * teknisi karena tiket ini, dan sambungan apa yang lahir di dalamnya.
 *
 * Sebelum ini, kerja splicing cuma bisa dilihat dari sisi kotaknya (buka peta, cari
 * ODP-nya, buka meja splicing). Penyelia yang mengurasi hasil kerja justru bertanya dari
 * arah sebaliknya: "tiket ini menghasilkan apa?" — itulah yang dijawab panel ini, satu
 * layar bersama bukti foto dan redaman optik.
 *
 * Dikelompokkan per kotak, sebagaimana teknisi menceritakannya ("di JB-01 saya sambung
 * tiga, di ODP-07 satu"). Panel disembunyikan bila tiketnya memang tak menyentuh serat —
 * sebagian besar WO memang begitu, dan kartu kosong di tiap tiket cuma jadi bising.
 */
import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { ClosureKind, ClosureSpliceView } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge } from '@/components/atoms'

const CLOSURE_LABEL: Record<ClosureKind, string> = {
  ODC: 'ODC',
  ODP: 'ODP',
  JOINT_BOX: 'Joint box',
  ODF: 'ODF',
}

export function WorkOrderFiberWork({ workOrderId }: { workOrderId: string }) {
  const { can } = useCan()
  const canView = can('network.splice.view')

  const [groups, setGroups] = useState<ClosureSpliceView[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!canView) {
      setLoading(false)
      return
    }
    let alive = true
    api
      .get<ClosureSpliceView[]>(`/api/fiber-connections/by-work-order?workOrderId=${workOrderId}`)
      // Panel pelengkap: kegagalannya tak boleh menutup detail work order yang sudah tampil.
      .catch(() => [] as ClosureSpliceView[])
      .then((rows) => {
        if (!alive) return
        setGroups(rows)
        setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [canView, workOrderId])

  // Sunyi selama memuat: kartunya baru muncul kalau memang ada isinya, jadi tak ada
  // kerangka yang berkedip lalu lenyap di mayoritas tiket yang tak menyentuh serat.
  if (!canView || loading || groups.length === 0) return null

  const total = groups.reduce((sum, g) => sum + g.connections.length, 0)

  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'baseline', gap: '0.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Pekerjaan serat</h3>
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          {total} sambungan di {groups.length} kotak
        </span>
      </div>

      {groups.map((group) => (
        <section key={`${group.closureKind}:${group.closureId}`} className="stack" style={{ gap: '0.35rem' }}>
          <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'baseline' }}>
            <Badge tone="accent">{group.closureCode}</Badge>
            <span style={{ fontSize: '0.85rem' }}>{group.closureName}</span>
            <span className="muted" style={{ fontSize: '0.75rem' }}>{CLOSURE_LABEL[group.closureKind]}</span>
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Sambungan</th>
                  <th>Metode</th>
                  <th>Rugi</th>
                  <th>Oleh</th>
                </tr>
              </thead>
              <tbody>
                {group.connections.map((row) => (
                  <tr key={row.id}>
                    <td>
                      <span className="splice-end">
                        {row.a.colorHex && <span className="splice-dot" style={{ background: row.a.colorHex }} />}
                        {row.a.label}
                        <span aria-hidden> ↔ </span>
                        {row.b.colorHex && <span className="splice-dot" style={{ background: row.b.colorHex }} />}
                        {row.b.label}
                      </span>
                      {row.note && <div className="muted" style={{ fontSize: '0.75rem' }}>{row.note}</div>}
                    </td>
                    <td>{row.methodLabel}</td>
                    {/* Kosong berarti BELUM DIUKUR, bukan nol — jangan ditulis "0 dB". */}
                    <td className="tnum">{row.lossDb == null ? '—' : `${row.lossDb.toFixed(2)} dB`}</td>
                    <td className="muted" style={{ fontSize: '0.8rem' }}>{row.splicedByName ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </div>
  )
}
