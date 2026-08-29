import { useCallback, useEffect, useState } from 'react'
import { Plus } from 'lucide-react'
import { typographyStyles } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { ClosureKind, ClosureSplitterView, SplitterView } from '@/api/network'
import { SPLITTER_RATIOS } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextField } from '@/components/atoms'
import { useConfirm, useToast } from '@/system'

/**
 * Isi sebuah kabinet: modul-modul splitter di dalamnya.
 *
 * Aplikasi sejenis menaruh splitter sebagai SATU isian "rasio" di form ODC —
 * yang menyiratkan satu kabinet = satu splitter selamanya. Kabinet lapangan tak
 * begitu: ia bisa berisi dua modul dengan rasio berbeda, bisa bertingkat (kaki
 * modul pertama menyuapi modul kedua), dan bisa pula kosong sama sekali (ODC
 * cross-connect yang cuma menyambung feeder ke distribusi).
 *
 * Yang digambar di sini karena itu bukan angka rasio, melainkan KAKI-nya satu
 * per satu. "1:8" tak menjawab pertanyaan yang dibawa orang ke layar ini —
 * "masih ada slot buat pelanggan baru?" — sedangkan delapan kotak dengan tiga
 * di antaranya padam menjawabnya tanpa dibaca.
 */

/** Ringkasan sebaris di kepala panel: kapasitas cabang kabinet dalam satu kalimat. */
function summarize(splitters: SplitterView[]): string {
  const legs = splitters.reduce((n, s) => n + s.legCount, 0)
  const used = splitters.reduce((n, s) => n + s.usedLegs.length, 0)
  return `${splitters.length} modul · ${legs} kaki · ${used} terpakai · ${legs - used} bebas`
}

/** Kaki splitter sebagai kotak — padam berarti sudah ada serat menempel di situ. */
function LegGrid({ splitter }: { splitter: SplitterView }) {
  const used = new Set(splitter.usedLegs)
  return (
    <div className="splitter-legs">
      {Array.from({ length: splitter.legCount }, (_, i) => i + 1).map((leg) => (
        <span
          key={leg}
          className={`splitter-leg${used.has(leg) ? ' is-used' : ''}`}
          title={`Kaki ${leg} · ${used.has(leg) ? 'terpakai' : 'bebas'}`}
        >
          {leg}
        </span>
      ))}
    </div>
  )
}

export function SplitterPanel({
  ownerKind,
  ownerId,
  onChanged,
}: {
  ownerKind: Extract<ClosureKind, 'ODC' | 'ODP'>
  ownerId: string
  /** Dipanggil seusai isi kabinet berubah — pemanggil menyegarkan ringkasan di detailnya. */
  onChanged?: () => void
}) {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const canView = can('network.splitter.view')
  const canCreate = can('network.splitter.create')
  const canUpdate = can('network.splitter.update')
  const canDelete = can('network.splitter.delete')

  const [data, setData] = useState<ClosureSplitterView | null>(null)
  const [loading, setLoading] = useState(true)
  // Modul yang sedang disunting; 'new' berarti form tambah modul.
  const [editing, setEditing] = useState<string | null>(null)
  const [ratio, setRatio] = useState('1:8')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      setData(await api.get<ClosureSplitterView>(`/api/splitters?ownerKind=${ownerKind}&ownerId=${ownerId}`))
    } catch {
      // Panel pelengkap: kegagalannya tak boleh menutup detail kabinet yang sudah tampil.
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [ownerKind, ownerId])

  useEffect(() => {
    // Kabinet lain, isi lain: dikosongkan dulu supaya modul kabinet sebelumnya tak
    // sempat terbaca sebagai isi kabinet yang baru dibuka.
    setData(null)
    setEditing(null)
    setLoading(true)
    if (!canView) {
      setLoading(false)
      return
    }
    void load()
  }, [canView, load])

  const openAdd = () => {
    setEditing('new')
    setRatio('1:8')
    setNote('')
  }

  const openEdit = (splitter: SplitterView) => {
    setEditing(splitter.id)
    setRatio(splitter.ratio)
    setNote(splitter.note ?? '')
  }

  const save = async () => {
    if (!editing || saving) return
    setSaving(true)
    try {
      const body = { ratio, note: note.trim() || null }
      if (editing === 'new') await api.post('/api/splitters', { ownerKind, ownerId, ...body })
      else await api.put(`/api/splitters/${editing}`, body)
      setEditing(null)
      await load()
      onChanged?.()
      toast.success(editing === 'new' ? 'Modul splitter ditambahkan' : 'Modul splitter diperbarui')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan modul splitter')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (splitter: SplitterView) => {
    if (
      !(await confirm({
        title: 'Hapus modul splitter',
        message: `Hapus ${splitter.code} (${splitter.ratio}) dari kabinet ini?`,
        confirmLabel: 'Hapus',
        danger: true,
      }))
    )
      return
    try {
      await api.del(`/api/splitters/${splitter.id}`)
      await load()
      onChanged?.()
      toast.success(`${splitter.code} dihapus`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus modul splitter')
    }
  }

  if (!canView || loading || !data) return null

  const splitters = data.splitters

  return (
    <div className="card stack">
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <h3 style={{ margin: 0 }}>Isi kabinet</h3>
        {canCreate && (
          <Button icon={<Plus size={16} />} onClick={openAdd} disabled={editing === 'new'}>
            Tambah modul
          </Button>
        )}
      </div>

      {splitters.length > 0 ? (
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>{summarize(splitters)}</p>
      ) : (
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>
          Belum ada modul splitter di sini. Kabinet tanpa splitter itu sah — isinya cuma
          sambungan lewat (cross-connect); tambahkan modul bila kabinet ini memang memecah sinyal.
        </p>
      )}

      {splitters.map((s) => (
        <div key={s.id} className="splitter-module stack" style={{ gap: '0.45rem' }}>
          <div className="spread wrap" style={{ alignItems: 'center', gap: '0.4rem' }}>
            <div className="row wrap" style={{ alignItems: 'center', gap: '0.4rem' }}>
              <strong style={{ ...typographyStyles.body1 }}>{s.code}</strong>
              <Badge>{s.ratio}</Badge>
              <span className="muted tnum" style={{ ...typographyStyles.caption2 }}>
                {s.usedLegs.length}/{s.legCount} kaki · −{s.insertionLossDb.toFixed(1)} dB
              </span>
              {/* Modul tanpa masukan tak menyalurkan apa-apa — pelanggan yang dicolok
                  di kakinya tetap gelap, dan itu tak terlihat dari kaki mana pun. */}
              {!s.inputConnected && (
                <span className="splitter-warn" title="Sisi masukan modul ini belum dapat serat dari hulu">
                  input belum tersambung
                </span>
              )}
            </div>
            <div className="row" style={{ gap: '0.3rem' }}>
              {canUpdate && (
                <Button variant="subtle" onClick={() => openEdit(s)} disabled={editing === s.id}>
                  Ubah
                </Button>
              )}
              {canDelete && (
                <Button variant="danger" onClick={() => void remove(s)}>
                  Hapus
                </Button>
              )}
            </div>
          </div>

          <LegGrid splitter={s} />
          {s.note && <p className="muted" style={{ margin: 0, ...typographyStyles.caption2 }}>{s.note}</p>}

          {editing === s.id && (
            <div className="core-editor stack" style={{ gap: '0.5rem' }}>
              <SelectField label="Rasio" value={ratio} onChange={(_, d) => setRatio(d.value)}>
                {SPLITTER_RATIOS.map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </SelectField>
              <TextField
                label={<>Catatan <span className="muted">(opsional)</span></>}
                value={note}
                onChange={(_, d) => setNote(d.value)}
                placeholder="mis. cabang perumahan blok C"
                maxLength={200}
              />
              {/* Menurunkan rasio ditolak server bila ada kaki terpakai di luar rasio
                  baru — disebutkan di sini supaya penolakannya tak terasa sewenang-wenang. */}
              <p className="muted" style={{ margin: 0, ...typographyStyles.caption2 }}>
                Rasio hanya bisa diperkecil selama kaki yang terpakai masih muat di dalamnya.
              </p>
              <div className="row" style={{ gap: '0.4rem' }}>
                <Button variant="primary" onClick={() => void save()} disabled={saving}>
                  {saving ? 'Menyimpan…' : 'Simpan'}
                </Button>
                <Button variant="subtle" onClick={() => setEditing(null)} disabled={saving}>
                  Batal
                </Button>
              </div>
            </div>
          )}
        </div>
      ))}

      {editing === 'new' && (
        <div className="core-editor stack" style={{ gap: '0.5rem' }}>
          <strong style={{ ...typographyStyles.caption1 }}>Modul baru</strong>
          <SelectField label="Rasio" value={ratio} onChange={(_, d) => setRatio(d.value)}>
            {SPLITTER_RATIOS.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </SelectField>
          <TextField
            label={<>Catatan <span className="muted">(opsional)</span></>}
            value={note}
            onChange={(_, d) => setNote(d.value)}
            placeholder="mis. cabang perumahan blok C"
            maxLength={200}
          />
          <div className="row" style={{ gap: '0.4rem' }}>
            <Button variant="primary" onClick={() => void save()} disabled={saving}>
              {saving ? 'Menyimpan…' : 'Tambah'}
            </Button>
            <Button variant="subtle" onClick={() => setEditing(null)} disabled={saving}>
              Batal
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
