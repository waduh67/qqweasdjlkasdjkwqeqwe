import { Text } from '@fluentui/react-components'
import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '@/api/client'
import type { AssetStatus, OdfView, SiteView } from '@/api/network'
import type { PageResponse } from '@/api/types'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Spinner, StatusBadge, TextField } from '@/components/atoms'
import { IconInventory } from '@/components/atoms/icons'
import { useConfirm, useToast } from '@/system'
import { mapFocusState, type MapFocusState } from '@/map/mapFocus'
import { uplinkLabel } from '@/utils/odfUplinks'
import { AssetDetailPanel } from './AccessNodeDetail'
import { Blade } from './Blade'
import { LocationPicker } from './LocationPicker'
import { FiberTracePanel } from './FiberTracePanel'
import { SplicingManager } from './SplicingManager'

const STATUS_OPTIONS: { value: AssetStatus; label: string }[] = [
  { value: 'PLANNED', label: 'Rencana' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'MAINTENANCE', label: 'Perawatan' },
  { value: 'INACTIVE', label: 'Nonaktif' },
  // Rak yang masih terpasang tapi sudah tak dilayani lagi — dibedakan dari yang
  // sengaja dimatikan sementara.
  { value: 'ABANDONED', label: 'Ditinggal' },
]

/** Bentuk formulir sunting — semua string karena datang dari input. */
interface OdfDraft {
  code: string
  name: string
  siteId: string
  longitude: string
  latitude: string
  portCount: string
  status: AssetStatus
}

function toDraft(odf: OdfView): OdfDraft {
  return {
    code: odf.code,
    name: odf.name,
    siteId: odf.siteId,
    longitude: String(odf.location.longitude),
    latitude: String(odf.location.latitude),
    portCount: String(odf.portCount),
    status: odf.status,
  }
}

/**
 * Detail satu ODF — SATU implementasi yang dipakai di mana pun rak itu dibuka
 * (daftar Inventory maupun panel peta), kembar peran dengan [AccessNodeDetail].
 *
 * Sengaja BUKAN kind keempat di komponen itu: bentuk datanya memang beda, bukan cuma
 * kata-katanya. Rak tak punya alamat sendiri (alamatnya alamat POP-nya), wajib punya
 * site induk, dan ukurannya port dua sisi — bukan kapasitas + rasio splitter. Yang
 * memang sama, kerangka bacanya, tetap dipakai bersama lewat [AssetDetailPanel].
 */
export function OdfDetail({
  odfId,
  onChanged,
  onDeleted,
  onShowOnMap,
}: {
  odfId: string
  /** Dipanggil seusai sunting tersimpan — pemanggil menyegarkan daftar/tile-nya. */
  onChanged?: () => void
  /** Dipanggil seusai rak dihapus — pemanggil menutup panelnya. */
  onDeleted?: () => void
  /**
   * Perilaku aksi "Lihat di peta"; pesan sorotnya sudah disiapkan. Kosong = aksi
   * disembunyikan (operator tak berizin membuka peta).
   */
  onShowOnMap?: (focus: MapFocusState) => void
}) {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const canUpdate = can('network.odf.update')
  const canDelete = can('network.odf.delete')

  const [odf, setOdf] = useState<OdfView | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [draft, setDraft] = useState<OdfDraft | null>(null)
  const [initialDraft, setInitialDraft] = useState<OdfDraft | null>(null)
  const [sites, setSites] = useState<SiteView[]>([])
  const [saving, setSaving] = useState(false)
  /** Naik tiap kali sambungan berubah — jalurnya ikut dirangkai ulang. */
  const [spliceVersion, setSpliceVersion] = useState(0)

  const load = useCallback(async () => {
    try {
      setOdf(await api.get<OdfView>(`/api/odfs/${odfId}`))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setNotFound(true)
      else toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail ODF')
    } finally {
      setLoading(false)
    }
  }, [odfId, toast])

  useEffect(() => {
    void load()
  }, [load])

  // Daftar POP ditarik hanya saat drawer sunting dibuka: membacanya tak butuh
  // pemilih, dan sebagian besar kunjungan ke panel ini memang cuma membaca.
  useEffect(() => {
    if (!draft) return
    let alive = true
    api
      .get<PageResponse<SiteView>>('/api/sites?size=100')
      .then((page) => {
        if (alive) setSites(page.content)
      })
      .catch(() => {
        /* pemilih site opsional saat memuat — pilihan lama tetap terkirim */
      })
    return () => {
      alive = false
    }
  }, [draft])

  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }

  const save = async () => {
    if (!draft) return
    setSaving(true)
    try {
      await api.put(`/api/odfs/${odfId}`, {
        code: draft.code,
        name: draft.name,
        siteId: draft.siteId,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
        portCount: Number(draft.portCount),
        status: draft.status,
      })
      closeDraft()
      await load()
      onChanged?.()
      toast.success('ODF diperbarui')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan ODF')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!odf) return
    if (
      !(await confirm({
        title: 'Hapus ODF',
        message: `Hapus ODF ${odf.code}?`,
        confirmLabel: 'Hapus',
        danger: true,
      }))
    )
      return
    try {
      await api.del(`/api/odfs/${odfId}`)
      toast.success(`ODF ${odf.code} dihapus`)
      onDeleted?.()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus ODF')
    }
  }

  if (loading) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
        <Spinner />
      </div>
    )
  }

  if (notFound || !odf) {
    return (
      <div className="card">
        <EmptyState
          title="ODF tidak ditemukan"
          hint="Mungkin sudah dihapus atau kamu tak berizin melihatnya."
          icon={<IconInventory size={32} />}
        />
      </div>
    )
  }

  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)
  const free = Math.max(0, odf.portCount - odf.usedPortCount)

  return (
    <>
      <AssetDetailPanel
        badges={
          <>
            <StatusBadge status={odf.status} />
            <Badge>{odf.portCount} port</Badge>
          </>
        }
        subtitle={
          free === 0
            ? `Rak penuh — ${odf.portCount} port terpakai semua`
            : `${odf.usedPortCount}/${odf.portCount} port terpakai · sisa ${free}`
        }
        fields={[
          { label: 'Nama', value: odf.name },
          { label: 'POP', value: odf.siteName ?? '—' },
          // Bukan isian: dibaca dari patchcord yang benar-benar tercolok, jadi
          // boleh lebih dari satu OLT dan boleh belum ada sama sekali.
          {
            label: 'OLT terkait',
            value: odf.olts.length === 0 ? 'belum ada patchcord tercatat' : odf.olts.map(uplinkLabel).join(', '),
          },
          { label: 'Jumlah port', value: String(odf.portCount) },
          { label: 'Port terpakai', value: `${odf.usedPortCount}` },
          // Dua angka, bukan satu yang dibagi dua: satu port dipakai DUA sambungan
          // (belakang ke core kabel luar, depan ke patchcord OLT), jadi jumlah
          // sambungan memang bisa melebihi jumlah port terpakai.
          { label: 'Sambungan di dalamnya', value: String(odf.spliceCount) },
        ]}
        location={odf.location}
        canUpdate={canUpdate}
        canDelete={canDelete}
        onEdit={() => {
          const d = toDraft(odf)
          setDraft(d)
          setInitialDraft(d)
        }}
        onDelete={() => void remove()}
        onShowOnMap={onShowOnMap ? () => onShowOnMap(mapFocusState('odf', odfId, odf.location)) : undefined}
      >
        {/* Jalur di ATAS meja kerja: yang dicari orang saat membuka rak adalah
            "port ini bermuara ke mana", bukan formulir menyambung. */}
        <FiberTracePanel closureKind="ODF" closureId={odfId} reloadKey={spliceVersion} />

        {/* Rak ODF adalah satu-satunya kotak yang sisi tujuannya bukan serat: di
            sini core kabel luar bertemu port (sisi belakang), lalu patchcord dari
            sisi depan lanjut ke PON port OLT. */}
        <SplicingManager
          closureKind="ODF"
          closureId={odfId}
          onChanged={() => {
            void load()
            setSpliceVersion((v) => v + 1)
          }}
        />
      </AssetDetailPanel>

      <Blade
        open={draft != null}
        title={`Edit ${odf.code}`}
        subtitle="Ubah identitas, POP induk & jumlah port. Kabel yang berhenti di rak ini diatur di peta."
        size="full"
        className="blade-edit"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <Button variant="primary" disabled={saving} onClick={() => void save()}>
              Simpan
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <div style={{ flex: 1 }}>
                <TextField
                  label="Kode"
                  value={draft.code}
                  onChange={(_, data) => setDraft({ ...draft, code: data.value })}
                  disabled
                />
              </div>
              <div style={{ flex: 2 }}>
                <TextField
                  label="Nama"
                  value={draft.name}
                  onChange={(_, data) => setDraft({ ...draft, name: data.value })}
                />
              </div>
            </div>
            <SelectField
              label="POP induk"
              value={draft.siteId}
              onChange={(_, data) => setDraft({ ...draft, siteId: data.value })}
            >
              {/* Site yang sedang terpasang tetap jadi opsi walau daftar belum mendarat,
                  supaya menyimpan tanpa menunggu tarikan tak diam-diam memindah rak. */}
              {sites.length === 0 && <option value={draft.siteId}>{odf.siteName ?? '— POP saat ini —'}</option>}
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} — {s.name}
                </option>
              ))}
            </SelectField>
            <div className="row">
              <div style={{ flex: 1 }}>
                <TextField
                  label="Jumlah port"
                  type="number"
                  min={1}
                  max={1152}
                  value={draft.portCount}
                  onChange={(_, data) => setDraft({ ...draft, portCount: data.value })}
                />
              </div>
              <div style={{ flex: 1 }}>
                <SelectField
                  label="Status"
                  value={draft.status}
                  onChange={(_, data) => setDraft({ ...draft, status: data.value as AssetStatus })}
                >
                  {STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </SelectField>
              </div>
            </div>
            <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
              Jumlah port tak bisa dikurangi di bawah port tertinggi yang sudah tersambung —
              server menolaknya agar sambungan yang ada tak jadi yatim.
            </Text>
            <label>
              <span>Lokasi</span>
              <LocationPicker
                longitude={draft.longitude}
                latitude={draft.latitude}
                onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
                height={240}
              />
            </label>
          </div>
        )}
      </Blade>
    </>
  )
}
