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
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
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
        <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Pekerjaan serat</Text>
        <Text as="span" className="muted" size={200}>
          {total} sambungan di {groups.length} kotak
        </Text>
      </div>

      {groups.map((group) => (
        <section key={`${group.closureKind}:${group.closureId}`} className="stack" style={{ gap: '0.35rem' }}>
          <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'baseline' }}>
            <Badge tone="accent">{group.closureCode}</Badge>
            <Text as="span" size={200}>{group.closureName}</Text>
            <Text as="span" className="muted" size={100}>{CLOSURE_LABEL[group.closureKind]}</Text>
          </div>
          <div className="table-wrap">
            <Table><TableHeader><TableRow ><TableHeaderCell >Sambungan</TableHeaderCell>
            <TableHeaderCell >Metode</TableHeaderCell>
            <TableHeaderCell >Rugi</TableHeaderCell>
            <TableHeaderCell >Oleh</TableHeaderCell></TableRow></TableHeader>
            <TableBody>{group.connections.map((row) => (
              <TableRow key={row.id}><TableCell ><span className="splice-end">
                {row.a.colorHex && <span className="splice-dot" style={{ background: row.a.colorHex }} />}
                {row.a.label}
                <span aria-hidden> ↔ </span>
                {row.b.colorHex && <span className="splice-dot" style={{ background: row.b.colorHex }} />}
                {row.b.label}
              </span>
              {row.note && <div className="muted">{row.note}</div>}</TableCell>
              <TableCell >{row.methodLabel}</TableCell>
              {/* Kosong berarti BELUM DIUKUR, bukan nol — jangan ditulis "0 dB". */}
              <TableCell className="tnum">{row.lossDb == null ? '—' : `${row.lossDb.toFixed(2)} dB`}</TableCell>
              <TableCell className="muted"><Text as="span" size={200}>{row.splicedByName ?? '—'}</Text></TableCell></TableRow>
            ))}</TableBody></Table>
          </div>
        </section>
      ))}
    </div>
  )
}
