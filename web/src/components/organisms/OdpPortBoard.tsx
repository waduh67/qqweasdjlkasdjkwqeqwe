import { useCallback, useEffect, useMemo, useState } from 'react'
import { MessageBar, MessageBarBody, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { api } from '@/api/client'
import type { OdpPortBoardView, OdpPortIssue, OdpPortRowView } from '@/api/network'
import { onuStatusLabel } from '@/api/network'
import { Badge, Spinner, StatusBadge } from '@/components/atoms'
import type { Tone } from '@/components/atoms'
import { HEALTH_COLOR } from '@/map/mapStyle'

/**
 * Papan port sebuah ODP: siapa di lubang mana, dan di mana catatan berselisih
 * dengan serat.
 *
 * Selama ini isi ODP dicatat di dua tempat oleh dua orang pada dua waktu —
 * pemasangan ONU oleh petugas layanan, sambungan serat oleh teknisi yang membuka
 * kotaknya — dan tak ada satu layar pun yang mempertemukannya. Selisihnya baru
 * ketahuan di lapangan: port yang katanya kosong ternyata terisi, pelanggan yang
 * ikut mati saat tetangganya diperbaiki, kaki yang dicabut satu per satu untuk
 * mencari milik siapa. Panel ini menaruh keduanya berdampingan supaya selisihnya
 * kelihatan dari kantor, sebelum ada yang berangkat.
 *
 * Dua hal yang membuatnya berbeda dari daftar pelanggan biasa:
 *
 * 1. **Lubang kosong ikut tampil.** Yang dibawa orang ke layar ini kerap justru
 *    "port mana yang masih bisa dipakai", dan daftar penghuni tak menjawabnya.
 * 2. **"Bebas" dihitung dari KEDUA sisi.** Port tanpa ONU yang kakinya sudah
 *    dilas sampai ke rumah orang BUKAN port bebas — menawarkannya ke pemasangan
 *    berikutnya berarti mengirim teknisi ke lubang yang sudah terisi.
 */

/**
 * Nada tiap bentuk selisih. Dua yang merah adalah yang bisa MEMATIKAN orang:
 * catatan yang menunjuk pelanggan lain membuat pemutusan salah sasaran, dan kaki
 * yang berbalik ke penyuapnya tak melayani siapa pun sementara portnya terhitung
 * terpakai. Sisanya pekerjaan yang belum tuntas — perlu dibereskan, tapi tak ada
 * yang sedang mati karenanya.
 */
const ISSUE_TONE: Record<OdpPortIssue, Tone> = {
  PORT_MISMATCH: 'critical',
  LEG_BACKWARD: 'critical',
  FIBER_WITHOUT_PORT: 'serious',
  PORT_WITHOUT_FIBER: 'warning',
  PORT_UNRECORDED: 'warning',
}

/**
 * Lubang yang benar-benar bisa dipakai besok: tak ada ONU tercatat DAN tak ada
 * kaki yang sudah dilas ke sana. Cukup salah satunya terisi untuk membuatnya tak
 * lagi bebas — dan justru pasangan yang timpang itulah yang selama ini
 * ditawarkan sebagai kosong.
 */
const isFree = (row: OdpPortRowView) =>
  row.portNumber != null && row.customerId == null && !row.legConnected

export function OdpPortBoard({ odpId, reloadKey }: { odpId: string; reloadKey?: number }) {
  const [board, setBoard] = useState<OdpPortBoardView | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    try {
      setBoard(await api.get<OdpPortBoardView>(`/api/odps/${odpId}/ports`))
    } catch {
      // Panel pelengkap: kegagalannya tak boleh menutup detail kotak yang sudah tampil.
      setBoard(null)
    } finally {
      setLoading(false)
    }
  }, [odpId])

  useEffect(() => {
    void load()
  }, [load, reloadKey])

  const free = useMemo(() => (board?.ports ?? []).filter(isFree).map((row) => row.portNumber), [board])

  if (loading) {
    return (
      <div className="card stack">
        <h3 style={{ margin: 0 }}>Penghuni port</h3>
        <p className="muted row">
          <Spinner /> Menyandingkan catatan dengan serat…
        </p>
      </div>
    )
  }

  if (!board) return null

  return (
    <div className="card stack">
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <h3 style={{ margin: 0 }}>Penghuni port</h3>
        {board.issueCount > 0 && <Badge tone="warning">{board.issueCount} selisih</Badge>}
      </div>
      <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
        {board.occupiedCount}/{board.capacity} port terisi ·{' '}
        {board.splitterCodes.length > 0
          ? `kaki ${board.splitterCodes.join(', ')} dipetakan berurutan ke lubangnya`
          : 'kotak tanpa splitter — kaki tak dipetakan ke lubang'}
      </Text>

      {/* Port bebas disebut satu per satu, bukan cuma dihitung: yang membuka panel
          ini sering sedang menjawab "besok pasang di lubang mana". */}
      <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
        Port bebas:{' '}
        {free.length > 0 ? (
          <span className="tnum">{free.join(', ')}</span>
        ) : (
          'penuh — tak ada lubang yang catatan & seratnya sama-sama kosong'
        )}
      </Text>

      {board.issueCount > 0 && (
        <MessageBar intent="warning">
          <MessageBarBody>{board.issueCount} port catatannya berselisih dengan seratnya.</MessageBarBody>
        </MessageBar>
      )}

      <div className="table-wrap">
        <Table><TableHeader><TableRow ><TableHeaderCell >Port</TableHeaderCell>
        <TableHeaderCell >Kaki &amp; serat</TableHeaderCell>
        <TableHeaderCell >Pelanggan</TableHeaderCell>
        <TableHeaderCell >ONU</TableHeaderCell>
        <TableHeaderCell >Optik</TableHeaderCell>
        <TableHeaderCell >Selisih</TableHeaderCell></TableRow></TableHeader>
        <TableBody>{board.ports.map((row) => (
          <TableRow key={row.portNumber ?? `stray:${row.customerId}`}>{/* ONU tanpa nomor port tak dikarang letaknya: ia muncul apa adanya
              di bawah, dengan tanda tanya sebagai nomornya. */}
          <TableCell className="tnum">{row.portNumber ?? '?'}</TableCell>
          <TableCell >{row.legLabel ? (
            <>
              {row.legLabel}
              <br />
              <Text as="span" size={100} className="muted">
                {row.servedBy ?? 'belum dilas'}
              </Text>
            </>
          ) : (
            <span className="muted">—</span>
          )}</TableCell>
          <TableCell >{row.customerName ?? <span className="muted">kosong</span>}</TableCell>
          <TableCell >{row.onuSerialNumber ? (
            <>
              <Text as="span" size={100} className="muted tnum">
                {row.onuSerialNumber}
              </Text>
              <br />
              {row.onuStatus && (
                <StatusBadge status={row.onuStatus} label={onuStatusLabel(row.onuStatus)} />
              )}
            </>
          ) : (
            <span className="muted">—</span>
          )}</TableCell>
          <TableCell >{row.opticalHealth ? (
            <Text
              as="span"
              size={200}
              weight="semibold"
              className="tnum"
              style={{ color: HEALTH_COLOR[row.opticalHealth] }}
            >
              {/* Kosong berarti BELUM DIUKUR saat pasang, bukan 0 dBm. */}
              {row.rxPowerDbm != null ? `${row.rxPowerDbm} dBm` : row.opticalHealth}
            </Text>
          ) : (
            <span className="muted">—</span>
          )}</TableCell>
          <TableCell >{row.issue ? (
            <span title={row.issueDetail ?? undefined}>
              <Badge tone={ISSUE_TONE[row.issue]}>{row.issueLabel ?? row.issue}</Badge>
            </span>
          ) : (
            <span className="muted">—</span>
          )}</TableCell></TableRow>
        ))}</TableBody></Table>
      </div>
    </div>
  )
}
