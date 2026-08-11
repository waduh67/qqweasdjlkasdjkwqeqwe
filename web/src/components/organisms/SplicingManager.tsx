import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { Zap } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type {
  ClosureKind,
  ConnectionPointRequest,
  SpliceMethod,
  SpliceWorkbenchView,
} from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextField } from '@/components/atoms'
import { useConfirm, useToast } from '@/system'

/**
 * Meja kerja splicing & patching — satu layar untuk SEMUA kotak: ODF, ODC, ODP,
 * joint box.
 *
 * Bentuknya meniru pekerjaan aslinya. Teknisi membuka satu kotak, menaruh dua
 * ujung di hadapannya, lalu menyambungkannya sehelai demi sehelai; jadi layar
 * ini juga dua panel bersisian dengan tombol sambung di antaranya, bukan sebuah
 * formulir "pilih A, pilih B" yang menyembunyikan apa saja yang tersedia.
 *
 * Tiga hal yang membedakannya dari daftar sambungan biasa:
 *
 * 1. **Kabel yang cuma LEWAT ikut tampil.** Kabel distribusi 8 core yang
 *    melewati delapan ODP dikupas di tiap kotak untuk mengambil satu core —
 *    kotaknya bukan ujung kabel, tapi core-nya tetap harus bisa disambung dari
 *    sini. Setiap kabel diberi keterangan "berujung di sini" atau jarak titik
 *    kupasnya dari pangkal, angka yang dipakai teknisi mencocokkan hasil OTDR.
 * 2. **Titik tujuan menyesuaikan jenis kotaknya**: di ODF muncul port (belakang
 *    & depan) dan PON port OLT, di ODC/ODP muncul kaki tiap modul splitter, di
 *    joint box tak ada — di sana serat memang cuma bertemu serat.
 * 3. **Sambung 1:1 otomatis.** Kabel 8 core masuk, 8 core keluar: memasangkan
 *    core 1↔1 … 8↔8 satu per satu adalah 8 klik yang hasilnya sudah bisa
 *    ditebak. Satu tombol mengerjakannya sekaligus, dan server menerapkannya
 *    sebagai satu transaksi — semua masuk, atau tak ada yang masuk.
 */

/** Satu titik yang bisa diklik di salah satu panel — core maupun port/kaki. */
interface Slot {
  key: string
  point: ConnectionPointRequest
  label: string
  title: string
  /** Warna selubung serat (TIA-598) untuk core; null untuk port/kaki. */
  colorHex: string | null
  /** Sambungan yang memakainya DI KOTAK INI — harus dilepas dulu sebelum dipakai lagi. */
  connectionId: string | null
  /** Core ini sudah tersambung di kotak LAIN; dari sini tak bisa diapa-apakan. */
  blocked: boolean
}

/** Sekelompok titik yang dipilih bersama lewat satu dropdown: satu kabel, satu modul, satu rak. */
interface Group {
  key: string
  option: string
  /** Keterangan di bawah dropdown; kosong bila nama kelompoknya sudah cukup. */
  hint: string
  isCable: boolean
  slots: Slot[]
}

const METHODS: SpliceMethod[] = ['FUSION', 'MECHANICAL', 'CONNECTOR']

const free = (slot: Slot) => slot.connectionId == null && !slot.blocked

/**
 * Hitam atau putih di atas warna serat, mengikuti luminansi yang dirasakan mata —
 * nomor core harus terbaca baik di atas "Kuning" maupun "Hitam". Kembaran dari
 * yang dipakai kisi core kabel; sengaja disalin daripada dijadikan util bersama
 * yang cuma dipakai dua tempat.
 */
function inkOn(hex: string): string {
  const v = hex.replace('#', '')
  const r = parseInt(v.slice(0, 2), 16)
  const g = parseInt(v.slice(2, 4), 16)
  const b = parseInt(v.slice(4, 6), 16)
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6 ? '#0f172a' : '#ffffff'
}

/** Susun kabel & titik simpul jadi kelompok yang bisa dipilih di dropdown panel. */
function toGroups(data: SpliceWorkbenchView): Group[] {
  const cables: Group[] = data.cables.map((cable) => {
    // Jarak tap adalah angka lapangan, bukan hiasan: itu yang dicocokkan dengan
    // hasil OTDR saat mencari letak sambungan di sepanjang rute.
    const where = cable.terminatesHere
      ? 'berujung di sini'
      : `lewat · m-${Math.round(cable.tapDistanceMeters)}`
    // Rute yang meleset jauh dari kotaknya bukan penghalang menyambung, tapi tanda
    // survei kasar: garis di peta tak lagi mewakili jalur yang sesungguhnya, dan
    // jarak tap di atas ikut meleset sebesar itu.
    const stray = cable.offsetMeters > 25 ? ` · rute meleset ${Math.round(cable.offsetMeters)} m` : ''
    return {
      key: `cable:${cable.cableId}`,
      option: `${cable.code} · ${cable.coreCount} core · ${where}`,
      hint: `${cable.name} · ${Math.round(cable.lengthMeters)} m${stray}`,
      isCable: true,
      slots: cable.cores.map((entry) => ({
        key: `core:${entry.core.id}`,
        point: { kind: 'CORE', coreId: entry.core.id },
        label: String(entry.core.coreNumber),
        title:
          `Core ${entry.core.coreNumber} · ${entry.core.color}` +
          (entry.connectionId
            ? ' · sudah tersambung di kotak ini'
            : entry.connectedElsewhere
              ? ' · dipakai sambungan di kotak lain'
              : ' · bebas') +
          (entry.core.note ? ` · ${entry.core.note}` : ''),
        colorHex: entry.core.colorHex,
        connectionId: entry.connectionId,
        blocked: entry.connectedElsewhere,
      })),
    }
  })

  const points: Group[] = []
  for (const point of data.points) {
    const key = `point:${point.group}`
    let group = points.find((g) => g.key === key)
    if (!group) {
      group = { key, option: point.group, hint: '', isCable: false, slots: [] }
      points.push(group)
    }
    group.slots.push({
      key: `point:${point.kind}:${point.nodeId}:${point.portNumber ?? ''}:${point.portSide ?? ''}`,
      point: {
        kind: point.kind,
        nodeId: point.nodeId,
        portNumber: point.portNumber,
        portSide: point.portSide,
      },
      label: point.label,
      title: `${point.label} · ${point.connectionId ? 'sudah tersambung' : 'bebas'}`,
      colorHex: null,
      connectionId: point.connectionId,
      blocked: false,
    })
  }

  return [...cables, ...points]
}

/** Satu panel: dropdown pemilih kelompok + kisi titiknya. */
function SidePanel({
  title,
  hint,
  groups,
  groupKey,
  onGroup,
  picked,
  onPick,
  disabled,
}: {
  title: string
  hint: string
  groups: Group[]
  groupKey: string
  onGroup: (key: string) => void
  picked: string | null
  onPick: (slot: Slot) => void
  disabled: boolean
}) {
  const group = groups.find((g) => g.key === groupKey)
  const available = group ? group.slots.filter(free).length : 0

  return (
    <div className="splice-side stack" style={{ gap: '0.45rem' }}>
      <div className="spread" style={{ alignItems: 'baseline', gap: '0.4rem' }}>
        <strong style={{ fontSize: '0.82rem' }}>{title}</strong>
        {group && (
          <span className="muted tnum" style={{ fontSize: '0.72rem' }}>
            {available}/{group.slots.length} bebas
          </span>
        )}
      </div>

      <SelectField
        aria-label={title}
        hint={group?.hint || undefined}
        value={groupKey}
        onChange={(_, d) => onGroup(d.value)}
      >
        <option value="">— pilih {hint} —</option>
        {groups.map((g) => (
          <option key={g.key} value={g.key}>
            {g.option}
          </option>
        ))}
      </SelectField>

      {group ? (
        <div className={group.isCable ? 'core-grid' : 'splice-slots'}>
          {group.slots.map((slot) => {
            const taken = !free(slot)
            const on = picked === slot.key
            const style = slot.colorHex
              ? { background: slot.colorHex, color: inkOn(slot.colorHex) }
              : undefined
            return (
              <button
                key={slot.key}
                type="button"
                className={
                  (group.isCable ? 'core-chip' : 'splice-slot') +
                  (on ? ' is-selected' : '') +
                  (taken ? ' is-used' : '')
                }
                style={style}
                title={slot.title}
                disabled={disabled || taken}
                onClick={() => onPick(slot)}
              >
                {slot.label}
              </button>
            )
          })}
        </div>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.76rem' }}>
          Belum ada yang dipilih.
        </p>
      )}
    </div>
  )
}

export function SplicingManager({
  closureKind,
  closureId,
  onChanged,
}: {
  closureKind: ClosureKind
  closureId: string
  /** Dipanggil seusai sambungan berubah — pemanggil menyegarkan ringkasan detailnya. */
  onChanged?: () => void
}) {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const canView = can('network.splice.view')
  const canManage = can('network.splice.manage')

  const [data, setData] = useState<SpliceWorkbenchView | null>(null)
  const [loading, setLoading] = useState(true)
  const [leftKey, setLeftKey] = useState('')
  const [rightKey, setRightKey] = useState('')
  const [leftPick, setLeftPick] = useState<Slot | null>(null)
  const [rightPick, setRightPick] = useState<Slot | null>(null)
  const [method, setMethod] = useState<SpliceMethod>('FUSION')
  const [lossDb, setLossDb] = useState('')
  const [busy, setBusy] = useState(false)
  // Baris sambungan yang sedang disunting keterangannya (hasil ukur menyusul besoknya).
  const [editing, setEditing] = useState<string | null>(null)
  const [editMethod, setEditMethod] = useState<SpliceMethod>('FUSION')
  const [editLoss, setEditLoss] = useState('')
  const [editNote, setEditNote] = useState('')

  const load = useCallback(async () => {
    try {
      setData(
        await api.get<SpliceWorkbenchView>(
          `/api/fiber-connections/workbench?closureKind=${closureKind}&closureId=${closureId}`,
        ),
      )
    } catch {
      // Panel pelengkap: kegagalannya tak boleh menutup detail kotak yang sudah tampil.
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [closureKind, closureId])

  useEffect(() => {
    if (!canView) {
      setLoading(false)
      return
    }
    void load()
  }, [canView, load])

  const groups = useMemo(() => (data ? toGroups(data) : []), [data])

  // Tebakan awal yang benar untuk kasus paling umum: kabel pertama (yang sudah
  // diurut server — yang berujung di sini duluan) lawan tujuan alaminya, yaitu
  // kaki splitter / port ODF bila ada, atau kabel berikutnya di joint box.
  useEffect(() => {
    if (groups.length === 0) return
    setLeftKey((prev) => (groups.some((g) => g.key === prev) ? prev : (groups[0]?.key ?? '')))
    setRightKey((prev) => {
      if (groups.some((g) => g.key === prev)) return prev
      const target = groups.find((g) => !g.isCable) ?? groups[1]
      return target?.key ?? ''
    })
  }, [groups])

  // Pilihan yang sudah tak ada lagi (habis disambung/dilepas) tak boleh menggantung.
  useEffect(() => {
    const alive = (slot: Slot | null) =>
      slot != null && groups.some((g) => g.slots.some((s) => s.key === slot.key && free(s)))
    setLeftPick((prev) => (alive(prev) ? prev : null))
    setRightPick((prev) => (alive(prev) ? prev : null))
  }, [groups])

  /** Pasangan yang akan dibuat "sambung 1:1": urutan bebas lawan urutan bebas. */
  const autoPairs = useMemo(() => {
    if (leftKey === '' || leftKey === rightKey) return []
    const a = groups.find((g) => g.key === leftKey)?.slots.filter(free) ?? []
    const b = groups.find((g) => g.key === rightKey)?.slots.filter(free) ?? []
    const n = Math.min(a.length, b.length)
    return Array.from({ length: n }, (_, i) => ({ a: a[i], b: b[i] }))
  }, [groups, leftKey, rightKey])

  const afterChange = async () => {
    setLeftPick(null)
    setRightPick(null)
    setLossDb('')
    await load()
    onChanged?.()
  }

  const connect = async () => {
    if (!leftPick || !rightPick || busy) return
    setBusy(true)
    try {
      await api.post('/api/fiber-connections', {
        closureKind,
        closureId,
        a: leftPick.point,
        b: rightPick.point,
        method,
        lossDb: lossDb.trim() === '' ? null : Number(lossDb),
      })
      toast.success(`${leftPick.label} ↔ ${rightPick.label} tersambung`)
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyambung')
    } finally {
      setBusy(false)
    }
  }

  const connectAll = async () => {
    if (autoPairs.length === 0 || busy) return
    const left = groups.find((g) => g.key === leftKey)
    const right = groups.find((g) => g.key === rightKey)
    if (
      !(await confirm({
        title: 'Sambung 1:1 otomatis',
        message:
          `Buat ${autoPairs.length} sambungan sekaligus antara ${left?.option} dan ${right?.option}, ` +
          'dipasangkan berurutan dari yang paling kecil. Semua masuk atau tak ada yang masuk.',
        confirmLabel: `Sambung ${autoPairs.length} pasang`,
      }))
    )
      return
    setBusy(true)
    try {
      await api.post('/api/fiber-connections/bulk', {
        closureKind,
        closureId,
        pairs: autoPairs.map((pair) => ({ a: pair.a.point, b: pair.b.point, method })),
      })
      toast.success(`${autoPairs.length} sambungan dibuat`)
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyambung sekaligus')
    } finally {
      setBusy(false)
    }
  }

  const disconnect = async (id: string, label: string) => {
    if (
      !(await confirm({
        title: 'Lepas sambungan',
        message: `Lepas ${label}? Kedua ujungnya kembali bebas dan layanan yang lewat situ akan mati.`,
        confirmLabel: 'Lepas',
        danger: true,
      }))
    )
      return
    try {
      await api.del(`/api/fiber-connections/${id}`)
      toast.success('Sambungan dilepas')
      await afterChange()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal melepas sambungan')
    }
  }

  const saveDetail = async (id: string) => {
    setBusy(true)
    try {
      await api.put(`/api/fiber-connections/${id}`, {
        method: editMethod,
        lossDb: editLoss.trim() === '' ? null : Number(editLoss),
        note: editNote.trim() === '' ? null : editNote.trim(),
      })
      setEditing(null)
      toast.success('Keterangan sambungan disimpan')
      await load()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan keterangan')
    } finally {
      setBusy(false)
    }
  }

  if (!canView || loading || !data) return null

  const capacityFull = data.spliceCapacity != null && data.spliceCount >= data.spliceCapacity
  const summary =
    data.spliceCapacity != null
      ? `${data.spliceCount}/${data.spliceCapacity} sambungan · ${data.cables.length} kabel terjangkau`
      : `${data.spliceCount} sambungan · ${data.cables.length} kabel terjangkau`

  return (
    <div className="card stack">
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <h3 style={{ margin: 0 }}>Sambungan serat</h3>
        {capacityFull && <Badge tone="warning">tray penuh</Badge>}
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>{summary}</p>

      {data.cables.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Tak ada kabel yang berujung atau lewat di kotak ini. Tarik kabelnya dulu di peta —
          bila menurutmu sudah ada, periksa rutenya: kotak ini terlalu jauh dari garisnya.
        </p>
      ) : (
        <>
          <div className="splice-bench">
            <SidePanel
              title="Titik masuk"
              hint="kabel"
              groups={groups}
              groupKey={leftKey}
              onGroup={(key) => {
                setLeftKey(key)
                setLeftPick(null)
              }}
              picked={leftPick?.key ?? null}
              onPick={setLeftPick}
              disabled={!canManage || busy}
            />
            <SidePanel
              title="Titik tujuan"
              hint="tujuan"
              groups={groups}
              groupKey={rightKey}
              onGroup={(key) => {
                setRightKey(key)
                setRightPick(null)
              }}
              picked={rightPick?.key ?? null}
              onPick={setRightPick}
              disabled={!canManage || busy}
            />
          </div>

          {canManage && (
            <div className="splice-actions">
              <SelectField
                label="Metode"
                value={method}
                onChange={(_, d) => setMethod(d.value as SpliceMethod)}
              >
                {METHODS.map((m) => (
                  <option key={m} value={m}>
                    {SPLICE_METHOD_LABEL[m]}
                  </option>
                ))}
              </SelectField>
              <TextField
                label="Rugi (dB)"
                value={lossDb}
                onChange={(_, d) => setLossDb(d.value)}
                placeholder="kosong = belum diukur"
                inputMode="decimal"
              />
              <Button
                variant="primary"
                disabled={!leftPick || !rightPick || busy}
                onClick={() => void connect()}
              >
                {leftPick && rightPick ? `Sambung ${leftPick.label} ↔ ${rightPick.label}` : 'Sambung'}
              </Button>
              <Button
                icon={<Zap size={16} />}
                disabled={autoPairs.length === 0 || busy}
                onClick={() => void connectAll()}
                title={
                  autoPairs.length === 0
                    ? 'Pilih dua kelompok berbeda yang sama-sama masih punya titik bebas'
                    : undefined
                }
              >
                Sambung 1:1 otomatis{autoPairs.length > 0 ? ` (${autoPairs.length} pasang)` : ''}
              </Button>
            </div>
          )}
        </>
      )}

      {data.connections.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Titik masuk</th>
                <th>Titik tujuan</th>
                <th>Metode</th>
                <th>Rugi</th>
                {canManage && <th />}
              </tr>
            </thead>
            <tbody>
              {data.connections.map((row) => {
                const label = `${row.a.label} ↔ ${row.b.label}`
                return (
                  <Fragment key={row.id}>
                    <tr>
                      <td>
                        <span className="splice-end">
                          {row.a.colorHex && (
                            <span className="splice-dot" style={{ background: row.a.colorHex }} />
                          )}
                          {row.a.label}
                        </span>
                      </td>
                      <td>
                        <span className="splice-end">
                          {row.b.colorHex && (
                            <span className="splice-dot" style={{ background: row.b.colorHex }} />
                          )}
                          {row.b.label}
                        </span>
                      </td>
                      <td>{row.methodLabel}</td>
                      {/* Kosong berarti BELUM DIUKUR, bukan nol — jangan ditulis "0 dB". */}
                      <td className="tnum">{row.lossDb == null ? '—' : `${row.lossDb.toFixed(2)} dB`}</td>
                      {canManage && (
                        <td>
                          <div className="row" style={{ gap: '0.3rem', justifyContent: 'flex-end' }}>
                            <Button
                              variant="subtle"
                              onClick={() => {
                                setEditing(row.id)
                                setEditMethod(row.method)
                                setEditLoss(row.lossDb == null ? '' : String(row.lossDb))
                                setEditNote(row.note ?? '')
                              }}
                              disabled={editing === row.id}
                            >
                              Ubah
                            </Button>
                            <Button variant="danger" onClick={() => void disconnect(row.id, label)}>
                              Lepas
                            </Button>
                          </div>
                        </td>
                      )}
                    </tr>
                    {editing === row.id && (
                      <tr>
                        <td colSpan={canManage ? 5 : 4}>
                          <div className="splice-actions">
                            <SelectField
                              label="Metode"
                              value={editMethod}
                              onChange={(_, d) => setEditMethod(d.value as SpliceMethod)}
                            >
                              {METHODS.map((m) => (
                                <option key={m} value={m}>
                                  {SPLICE_METHOD_LABEL[m]}
                                </option>
                              ))}
                            </SelectField>
                            <TextField
                              label="Rugi (dB)"
                              value={editLoss}
                              onChange={(_, d) => setEditLoss(d.value)}
                              placeholder="hasil ukur OTDR/splicer"
                              inputMode="decimal"
                            />
                            <TextField
                              label="Catatan"
                              value={editNote}
                              onChange={(_, d) => setEditNote(d.value)}
                              maxLength={200}
                            />
                            <Button
                              variant="primary"
                              disabled={busy}
                              onClick={() => void saveDetail(row.id)}
                            >
                              Simpan
                            </Button>
                            <Button variant="subtle" onClick={() => setEditing(null)}>
                              Batal
                            </Button>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
