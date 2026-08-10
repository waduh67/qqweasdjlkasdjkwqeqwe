import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { CableCoreList, CableCoreView, CoreStatus } from '@/api/network'
import { CORE_STATUS_LABEL } from '@/api/network'
import { Button, SelectField, TextField } from '@/components/atoms'

/**
 * Kelola core sebuah kabel — pengganti tabel "CORE # | WARNA | STATUS | CATATAN"
 * yang lazim dipakai aplikasi sejenis.
 *
 * Alih-alih baris tabel, core digambar sebagai KISI CHIP berwarna asli seratnya
 * (TIA-598) dan dipecah per tube — persis susunan yang dilihat teknisi saat tube
 * dibuka. Kabel 24 core muat sekali pandang tanpa menggulung, dan "masih ada
 * berapa yang bebas" terjawab tanpa membaca satu baris pun.
 *
 * Perbedaan kedua: penyuntingan BOROngan. Menandai delapan core sekaligus adalah
 * kejadian normal (satu ODP = satu core, satu kabel melayani sederet ODP), jadi
 * pilih beberapa chip — atau klik angka ringkasan untuk memilih semua core
 * berstatus itu — lalu setel sekali jalan. Bidang yang dikosongkan tidak diubah,
 * sehingga catatan lapangan tiap core selamat saat statusnya disetel massal.
 */

const STATUS_ORDER: CoreStatus[] = ['FREE', 'USED', 'RESERVED', 'DAMAGED']

/** Warna cincin status di sekeliling chip; warna isi chip milik serat, bukan status. */
const STATUS_RING: Record<CoreStatus, string> = {
  FREE: 'var(--good)',
  USED: 'var(--muted)',
  RESERVED: 'var(--warning)',
  DAMAGED: 'var(--critical)',
}

/**
 * Hitam atau putih di atas warna serat, mengikuti luminansi yang dirasakan mata.
 * Nomor core harus terbaca baik di atas "Kuning" maupun "Hitam".
 */
function inkOn(hex: string): string {
  const v = hex.replace('#', '')
  const r = parseInt(v.slice(0, 2), 16)
  const g = parseInt(v.slice(2, 4), 16)
  const b = parseInt(v.slice(4, 6), 16)
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6 ? '#0f172a' : '#ffffff'
}

export function CableCoreManager({ cableId, canEdit }: { cableId: string; canEdit: boolean }) {
  const [data, setData] = useState<CableCoreList | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<number[]>([])
  const [anchor, setAnchor] = useState<number | null>(null)
  const [status, setStatus] = useState<CoreStatus | ''>('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setData(await api.get<CableCoreList>(`/api/cables/${cableId}/cores`))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Gagal memuat core')
    }
  }, [cableId])

  useEffect(() => {
    setSelected([])
    setAnchor(null)
    void load()
  }, [load])

  /** Core dikelompokkan per tube — satu baris kisi = satu tube, seperti aslinya. */
  const tubes = useMemo(() => {
    const map = new Map<number, CableCoreView[]>()
    for (const core of data?.cores ?? []) {
      const list = map.get(core.tubeNumber)
      if (list) list.push(core)
      else map.set(core.tubeNumber, [core])
    }
    return [...map.entries()].sort((a, b) => a[0] - b[0])
  }, [data])

  // Chip terpilih menyetir form: satu core memuat nilainya sendiri supaya
  // menyunting terasa seperti mengklik baris, bukan mengisi formulir kosong.
  const applySelection = (numbers: number[]) => {
    setSelected(numbers)
    const only = numbers.length === 1 ? data?.cores.find((c) => c.coreNumber === numbers[0]) : undefined
    setStatus(only ? only.status : '')
    setNote(only?.note ?? '')
  }

  const toggle = (core: CableCoreView, range: boolean) => {
    if (!canEdit) return
    if (range && anchor != null) {
      const [lo, hi] = anchor < core.coreNumber ? [anchor, core.coreNumber] : [core.coreNumber, anchor]
      const span = (data?.cores ?? []).filter((c) => c.coreNumber >= lo && c.coreNumber <= hi).map((c) => c.coreNumber)
      applySelection([...new Set([...selected, ...span])].sort((a, b) => a - b))
      return
    }
    setAnchor(core.coreNumber)
    applySelection(
      selected.includes(core.coreNumber)
        ? selected.filter((n) => n !== core.coreNumber)
        : [...selected, core.coreNumber].sort((a, b) => a - b),
    )
  }

  /** Klik angka ringkasan = pilih semua core berstatus itu (mis. semua yang rusak). */
  const selectByStatus = (s: CoreStatus) => {
    if (!canEdit) return
    const numbers = (data?.cores ?? []).filter((c) => c.status === s).map((c) => c.coreNumber)
    if (numbers.length === 0) return
    setAnchor(null)
    applySelection(numbers)
  }

  const save = async () => {
    if (selected.length === 0 || busy) return
    const trimmed = note.trim()
    const single = selected.length === 1 ? data?.cores.find((c) => c.coreNumber === selected[0]) : undefined
    // Catatan kosong pada satu core yang tadinya bercatatan = perintah menghapus;
    // pada seleksi borongan, kosong berarti "jangan sentuh catatan masing-masing".
    const clearNote = trimmed === '' && !!single?.note
    setBusy(true)
    try {
      const next = await api.put<CableCoreList>(`/api/cables/${cableId}/cores`, {
        coreNumbers: selected,
        status: status === '' ? null : status,
        note: trimmed === '' ? null : trimmed,
        clearNote,
      })
      setData(next)
      setSelected([])
      setAnchor(null)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Gagal menyimpan core')
    } finally {
      setBusy(false)
    }
  }

  if (error && !data) return <p className="muted" style={{ fontSize: '0.8rem' }}>{error}</p>
  if (!data) return <p className="muted" style={{ fontSize: '0.8rem' }}>Memuat core…</p>

  const counts: Record<CoreStatus, number> = {
    FREE: data.free,
    USED: data.used,
    RESERVED: data.reserved,
    DAMAGED: data.damaged,
  }

  return (
    <div className="stack" style={{ gap: '0.6rem' }}>
      {/* Ringkasan sekaligus alat pilih-cepat: angkanya bisa diklik. */}
      <div className="core-summary">
        {STATUS_ORDER.map((s) => (
          <button
            key={s}
            type="button"
            className="core-count"
            onClick={() => selectByStatus(s)}
            disabled={!canEdit || counts[s] === 0}
            title={canEdit && counts[s] > 0 ? `Pilih semua core ${CORE_STATUS_LABEL[s].toLowerCase()}` : undefined}
          >
            <span className="core-count-dot" style={{ background: STATUS_RING[s] }} />
            <strong className="tnum">{counts[s]}</strong>
            <span>{CORE_STATUS_LABEL[s]}</span>
          </button>
        ))}
      </div>

      {tubes.map(([tubeNumber, cores]) => (
        <div key={tubeNumber} className="stack" style={{ gap: '0.3rem' }}>
          {tubes.length > 1 && (
            <span className="core-tube-label">
              <span className="core-tube-dot" style={{ background: cores[0].tubeColorHex }} />
              Tube {tubeNumber} · {cores[0].tubeColor}
            </span>
          )}
          <div className="core-grid">
            {cores.map((core) => {
              const on = selected.includes(core.coreNumber)
              return (
                <button
                  key={core.id}
                  type="button"
                  className={`core-chip${on ? ' is-selected' : ''}${core.status === 'USED' ? ' is-used' : ''}`}
                  style={{
                    background: core.colorHex,
                    color: inkOn(core.colorHex),
                    boxShadow: `inset 0 0 0 2.5px ${STATUS_RING[core.status]}`,
                  }}
                  onClick={(e) => toggle(core, e.shiftKey)}
                  disabled={!canEdit}
                  title={`Core ${core.coreNumber} · ${core.color} · ${CORE_STATUS_LABEL[core.status]}${
                    core.note ? ` · ${core.note}` : ''
                  }`}
                >
                  {core.coreNumber}
                  {core.status === 'DAMAGED' && <span className="core-slash" />}
                  {core.note && <span className="core-note-dot" />}
                </button>
              )
            })}
          </div>
        </div>
      ))}

      {canEdit && selected.length > 0 && (
        <div className="core-editor stack" style={{ gap: '0.5rem' }}>
          <div className="spread" style={{ alignItems: 'center' }}>
            <strong style={{ fontSize: '0.82rem' }}>
              {selected.length === 1 ? `Core ${selected[0]}` : `${selected.length} core terpilih`}
            </strong>
            <span className="muted" style={{ fontSize: '0.72rem' }}>Shift+klik untuk rentang</span>
          </div>
          <SelectField
            label="Status"
            value={status}
            onChange={(_, d) => setStatus(d.value as CoreStatus | '')}
          >
            {selected.length > 1 && <option value="">— jangan ubah —</option>}
            {STATUS_ORDER.map((s) => (
              <option key={s} value={s}>{CORE_STATUS_LABEL[s]}</option>
            ))}
          </SelectField>
          <TextField
            label="Catatan"
            value={note}
            onChange={(_, d) => setNote(d.value)}
            placeholder={selected.length > 1 ? 'Kosong = catatan tiap core dibiarkan' : 'mis. ke ODP-3 Jl. Melati'}
            maxLength={200}
          />
          <div className="row" style={{ gap: '0.4rem' }}>
            <Button variant="primary" onClick={() => void save()} disabled={busy}>
              {busy ? 'Menyimpan…' : 'Simpan'}
            </Button>
            <Button variant="subtle" onClick={() => applySelection([])} disabled={busy}>
              Batal
            </Button>
          </div>
        </div>
      )}

      {error && <p style={{ fontSize: '0.78rem', color: 'var(--critical-ink)', margin: 0 }}>{error}</p>}
    </div>
  )
}
