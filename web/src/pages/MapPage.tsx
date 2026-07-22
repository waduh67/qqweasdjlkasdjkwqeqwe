import { useEffect, useRef, useState } from 'react'
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { api, ApiError, tokenStore } from '../api/client'
import type { CableType, CableView, ImpactCause, ImpactedOverlay, OdpInspection } from '../api/network'
import { useCan } from '../auth/useCan'
import { StatusBadge, useToast } from '../components/ui'
import { IconClose, IconRoute } from '../components/icons'
import { createCableTool, type CableTool, type ToolState } from '../map/cableTool'

/**
 * Peta jaringan berbasis vector tile.
 *
 * Tile dirender PostGIS (`ST_AsMVT`) dan diambil per ubin, sehingga jumlah aset
 * yang tergambar tidak membebani browser — inilah yang membuat peta tetap ringan
 * di puluhan ribu titik. Klik sebuah ODP untuk melihat siapa yang tersambung.
 */

const MAP_ATTRIBUTION = '&copy; Kontributor OpenStreetMap &copy; CARTO'

/** Pusat awal: Bekasi, sekadar titik berangkat sebelum data pertama masuk. */
const INITIAL_CENTER: [number, number] = [106.995, -6.243]

const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

/**
 * Warna kabel per tipe, dipakai bersama oleh lapisan glow & garis inti supaya
 * konsisten. Nada terang agar bercahaya di atas basemap gelap.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const CABLE_COLOR: any = [
  'match',
  ['get', 'cable_type'],
  'FEEDER',
  '#b47cff',
  'DISTRIBUTION',
  '#22d3ee',
  'DROP',
  '#34d399',
  '#7c8aa5',
]

/** Warna sorotan kabel terdampak menurut keparahan alarm hilirnya. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const SEVERITY_COLOR: any = ['match', ['get', 'severity'], 'CRITICAL', '#ff3b5c', 'WARNING', '#fbbf24', '#ff3b5c']

/** Warna ONU pelanggan menurut status hidup — dasar "perangkat modar → merah". */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const CUSTOMER_COLOR: any = [
  'match',
  ['get', 'onu_status'],
  'ONLINE',
  '#34d399',
  'OFFLINE',
  '#fbbf24',
  'LOS',
  '#ff5470',
  '#64748b',
]

/**
 * Urutan `line-dasharray` yang diputar untuk memberi kesan aliran (efek "semut
 * berjalan") — tiap langkah menggeser dash sedikit sehingga garis tampak
 * mengalir seperti data melintas. Diambil dari resep animasi baku MapLibre.
 */
const DASH_SEQUENCE: number[][] = [
  [0, 4, 3],
  [0.5, 4, 2.5],
  [1, 4, 2],
  [1.5, 4, 1.5],
  [2, 4, 1],
  [2.5, 4, 0.5],
  [3, 4, 0],
  [0, 0.5, 3, 3.5],
  [0, 1, 3, 3],
  [0, 1.5, 3, 2.5],
  [0, 2, 3, 2],
  [0, 2.5, 3, 1.5],
  [0, 3, 3, 1],
  [0, 3.5, 3, 0.5],
]

/** Lebar garis diinterpolasi menurut zoom agar ramping saat jauh, jelas saat dekat. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const zoomWidth = (near: number, far: number): any => [
  'interpolate',
  ['linear'],
  ['zoom'],
  11,
  far,
  16,
  near,
]

/** Lingkaran bercahaya: inti terang + halo blur di bawahnya. Dua lapisan per aset. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function glowCircle(id: string, sourceLayer: string, color: any, radius: number): any[] {
  return [
    {
      id: `${id}-glow`,
      type: 'circle',
      source: 'ftth',
      'source-layer': sourceLayer,
      paint: {
        'circle-radius': radius * 2.2,
        'circle-color': color,
        'circle-blur': 1,
        'circle-opacity': 0.45,
      },
    },
    {
      id,
      type: 'circle',
      source: 'ftth',
      'source-layer': sourceLayer,
      paint: {
        'circle-radius': radius,
        'circle-color': color,
        'circle-stroke-width': 1.5,
        'circle-stroke-color': 'rgba(255,255,255,0.85)',
      },
    },
  ]
}

/**
 * Gaya peta "NOC" gelap-futuristik. Kabel digambar tiga lapis: halo blur, garis
 * inti tipis, lalu dash beranimasi yang mengalir. Aset digambar sebagai lingkaran
 * bercahaya. Basemap gelap (Carto) membuat semuanya menyala.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const FUTURISTIC_STYLE: any = {
  version: 8,
  sources: {
    basemap: {
      type: 'raster',
      tiles: [
        'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
        'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
        'https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
      ],
      tileSize: 256,
      attribution: MAP_ATTRIBUTION,
    },
    ftth: {
      type: 'vector',
      tiles: [`${window.location.origin}/api/gis/tiles/{z}/{x}/{y}.mvt`],
      minzoom: 0,
      maxzoom: 22,
    },
  },
  layers: [
    { id: 'basemap', type: 'raster', source: 'basemap', paint: { 'raster-opacity': 0.85 } },
    // Kabel: halo → garis inti → dash mengalir
    {
      id: 'cable-glow',
      type: 'line',
      source: 'ftth',
      'source-layer': 'cable',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': CABLE_COLOR, 'line-width': zoomWidth(9, 4), 'line-blur': 4, 'line-opacity': 0.4 },
    },
    {
      id: 'cable',
      type: 'line',
      source: 'ftth',
      'source-layer': 'cable',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': CABLE_COLOR, 'line-width': zoomWidth(2, 0.8), 'line-opacity': 0.95 },
    },
    {
      id: 'cable-flow',
      type: 'line',
      source: 'ftth',
      'source-layer': 'cable',
      layout: { 'line-cap': 'round' },
      paint: {
        'line-color': '#eaffff',
        'line-width': zoomWidth(2, 0.8),
        'line-opacity': 0.9,
        'line-dasharray': [0, 4, 3],
      },
    },
    // Aset bercahaya
    ...glowCircle('customer', 'customer', CUSTOMER_COLOR, 4),
    ...glowCircle('odp', 'odp', '#fbbf24', 6),
    ...glowCircle('odc', 'odc', '#22d3ee', 8),
    ...glowCircle('site', 'site', '#b47cff', 10),
    {
      id: 'odp-label',
      type: 'symbol',
      source: 'ftth',
      'source-layer': 'odp',
      minzoom: 15,
      layout: { 'text-field': ['get', 'code'], 'text-size': 11, 'text-offset': [0, 1.5] },
      paint: { 'text-color': '#dbeafe', 'text-halo-color': '#0a0e14', 'text-halo-width': 1.5 },
    },
  ],
}

export function MapPage() {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const tool = useRef<CableTool | null>(null)
  const modeRef = useRef<'idle' | 'draw' | 'edit'>('idle')
  const animRef = useRef<number | null>(null)
  const impactedRef = useRef<number | null>(null)
  // Penyebab per kabel (id → alarm hidup di hilir), diisi tiap overlay disegarkan
  // dan dibaca saat kabel diklik untuk menjelaskan "kenapa merah".
  const impactedCauses = useRef<Map<string, ImpactCause[]>>(new Map())
  const [selected, setSelected] = useState<OdpInspection | null>(null)
  const [cable, setCable] = useState<CableView | null>(null)
  const [cableCauses, setCableCauses] = useState<ImpactCause[]>([])
  const [editing, setEditing] = useState<CableView | null>(null)
  const [toolState, setToolState] = useState<ToolState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { can } = useCan()
  const toast = useToast()

  /** Memaksa tile termuat ulang setelah kabel berubah (cache tile 60 detik). */
  const refreshTiles = () => {
    const src = map.current?.getSource('ftth') as { setTiles?: (t: string[]) => void } | undefined
    src?.setTiles?.([`${window.location.origin}/api/gis/tiles/{z}/{x}/{y}.mvt?v=${Date.now()}`])
  }

  /**
   * Memuat kabel terdampak (alarm hidup) ke sumber overlay. Opsional: bila gagal,
   * peta tetap tampil normal tanpa sorotan merah.
   */
  const refreshImpacted = async () => {
    try {
      const overlay = await api.get<ImpactedOverlay>('/api/gis/impacted')
      impactedCauses.current = new Map(overlay.cables.map((c) => [c.id, c.causes]))
      const src = map.current?.getSource('impacted') as GeoJSONSource | undefined
      src?.setData({
        type: 'FeatureCollection',
        features: overlay.cables.map((c) => ({
          type: 'Feature',
          properties: { severity: c.severity },
          geometry: { type: 'LineString', coordinates: c.points.map((p) => [p.longitude, p.latitude]) },
        })),
      })
    } catch {
      /* overlay opsional — abaikan galat */
    }
  }

  useEffect(() => {
    if (!container.current || map.current) return

    const instance = new maplibregl.Map({
      container: container.current,
      center: INITIAL_CENTER,
      zoom: 14,
      // Peta operasi selalu gelap (gaya NOC), lepas dari tema aplikasi — basemap
      // gelap membuat aset & kabel yang bercahaya menonjol. Carto dark cukup untuk
      // pengembangan; untuk produksi pakai penyedia berlangganan / tile sendiri.
      style: FUTURISTIC_STYLE,
      // Endpoint tile ikut dilindungi RBAC, jadi tokennya harus dibawa. MapLibre
      // mengambil tile sendiri sehingga klien HTTP biasa tidak terlibat.
      transformRequest: (url) => {
        if (!url.startsWith(`${window.location.origin}/api/`)) return { url }
        const token = tokenStore.getAccessToken()
        return { url, headers: token ? { Authorization: `Bearer ${token}` } : {} }
      },
    })

    instance.addControl(new maplibregl.NavigationControl(), 'top-right')
    instance.addControl(new maplibregl.ScaleControl(), 'bottom-left')

    instance.on('click', 'odp', (event) => {
      // Selagi menggambar/mengedit kabel, klik dikuasai alat kabel.
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      api
        .get<OdpInspection>(`/api/gis/odps/${id}`)
        .then((odp) => {
          setSelected(odp)
          setCable(null)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODP'))
    })

    // Klik kabel (mode idle) → tampilkan detail + aksi edit/hapus.
    instance.on('click', 'cable', (event) => {
      if (modeRef.current !== 'idle') return
      const id = event.features?.[0]?.properties?.id as string | undefined
      if (!id) return
      api
        .get<CableView>(`/api/cables/${id}`)
        .then((c) => {
          setCable(c)
          // Kalau kabel ini sedang merah, sertakan alarm penyebabnya.
          setCableCauses(impactedCauses.current.get(id) ?? [])
          setSelected(null)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail kabel'))
    })

    for (const layer of ['odp', 'cable']) {
      instance.on('mouseenter', layer, () => {
        if (modeRef.current === 'idle') instance.getCanvas().style.cursor = 'pointer'
      })
      instance.on('mouseleave', layer, () => {
        if (modeRef.current === 'idle') instance.getCanvas().style.cursor = ''
      })
    }

    // Alat kabel dibuat setelah gaya termuat agar sumber & lapisannya bisa dipasang.
    instance.on('load', () => {
      tool.current = createCableTool(instance, (state) => {
        modeRef.current = state.mode
        setToolState(state)
      })

      // Overlay kabel terdampak: sorotan merah berdenyut di atas kabel biasa,
      // di bawah marker agar aset tetap terlihat. Diisi dari alarm hidup.
      instance.addSource('impacted', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      instance.addLayer(
        {
          id: 'impacted-glow',
          type: 'line',
          source: 'impacted',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': SEVERITY_COLOR, 'line-width': zoomWidth(12, 5), 'line-blur': 5, 'line-opacity': 0.5 },
        },
        'customer-glow',
      )
      instance.addLayer(
        {
          id: 'impacted-core',
          type: 'line',
          source: 'impacted',
          layout: { 'line-cap': 'round' },
          paint: { 'line-color': SEVERITY_COLOR, 'line-width': zoomWidth(2.5, 1), 'line-opacity': 0.95 },
        },
        'customer-glow',
      )
      void refreshImpacted()
      impactedRef.current = window.setInterval(() => {
        if (!document.hidden) void refreshImpacted()
      }, 30_000)

      // Animasi: dash `cable-flow` mengalir + denyut opasitas sorotan merah.
      let step = 0
      animRef.current = window.setInterval(() => {
        // Berhenti saat tab tersembunyi — hemat CPU, tak ada yang menonton.
        if (document.hidden) return
        step = (step + 1) % DASH_SEQUENCE.length
        if (instance.getLayer('cable-flow')) {
          instance.setPaintProperty('cable-flow', 'line-dasharray', DASH_SEQUENCE[step])
        }
        if (instance.getLayer('impacted-glow')) {
          const pulse = 0.3 + 0.35 * (Math.sin(Date.now() / 450) + 1)
          instance.setPaintProperty('impacted-glow', 'line-opacity', Math.min(0.85, pulse))
        }
      }, 60)
    })

    map.current = instance
    return () => {
      if (animRef.current) window.clearInterval(animRef.current)
      if (impactedRef.current) window.clearInterval(impactedRef.current)
      tool.current?.destroy()
      tool.current = null
      instance.remove()
      map.current = null
    }
  }, [])

  const startDraw = () => {
    setSelected(null)
    setCable(null)
    setEditing(null)
    tool.current?.startDraw()
  }

  const cancelTool = () => {
    tool.current?.cancel()
    setEditing(null)
  }

  const saveNewCable = async (form: { code: string; name: string; coreCount: number }) => {
    const route = tool.current?.route() ?? []
    const state = toolState
    if (!state?.from || !state?.to || !state.cableType) return
    try {
      await api.post('/api/cables', {
        code: form.code,
        name: form.name,
        cableType: state.cableType,
        coreCount: form.coreCount,
        route: route.map(([longitude, latitude]) => ({ longitude, latitude })),
        fromKind: state.from.kind,
        fromId: state.from.id,
        toKind: state.to.kind,
        toId: state.to.id,
        status: 'ACTIVE',
      })
      toast.success(`Kabel ${form.code} tersimpan (${Math.round(state.lengthMeters)} m)`)
      cancelTool()
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan kabel')
    }
  }

  const startEdit = (c: CableView) => {
    setCable(null)
    setEditing(c)
    tool.current?.startEdit({
      id: c.id,
      code: c.code,
      route: c.route.points,
      fromKind: c.fromKind,
      fromId: c.fromId,
      toKind: c.toKind,
      toId: c.toId,
      cableType: c.cableType,
    })
  }

  const saveEdit = async () => {
    if (!editing) return
    const route = tool.current?.route() ?? []
    try {
      await api.put(`/api/cables/${editing.id}`, {
        code: editing.code,
        name: editing.name,
        cableType: editing.cableType,
        coreCount: editing.coreCount,
        route: route.map(([longitude, latitude]) => ({ longitude, latitude })),
        fromKind: editing.fromKind,
        fromId: editing.fromId,
        toKind: editing.toKind,
        toId: editing.toId,
        status: editing.status,
      })
      toast.success(`Jalur ${editing.code} diperbarui`)
      cancelTool()
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperbarui jalur')
    }
  }

  const deleteCable = async (c: CableView) => {
    try {
      await api.del(`/api/cables/${c.id}`)
      toast.success(`Kabel ${c.code} dihapus`)
      setCable(null)
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus kabel')
    }
  }

  if (!can('gis.map.view')) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">
          Kamu tidak punya izin <span className="badge">gis.map.view</span>.
        </p>
      </div>
    )
  }

  const drawing = toolState?.mode === 'draw'
  const editingMode = toolState?.mode === 'edit'

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1 className="page-title">Peta Jaringan</h1>
          <p className="page-sub">Aset, pelanggan, dan jalur kabel — dari POP sampai rumah.</p>
        </div>
        <Legend />
      </div>
      {error && <p className="error">{error}</p>}
      <div className="map-shell">
        <div ref={container} className="map-canvas" />

        {/* Toolbar kiri-atas: mulai tarik kabel. Tampil saat idle — termasuk state
            awal sebelum alat pernah dipakai (toolState masih null). */}
        {can('network.cable.create') && (!toolState || toolState.mode === 'idle') && (
          <div className="map-toolbar">
            <button className="primary" onClick={startDraw}>
              <IconRoute size={16} /> Tarik kabel
            </button>
          </div>
        )}

        {/* Bilah petunjuk saat menggambar */}
        {drawing && toolState && (
          <div className="map-hint">
            <IconRoute size={16} />
            <span>{drawHint(toolState)}</span>
            <span className="tnum" style={{ marginLeft: 'auto', fontWeight: 600 }}>
              {formatLength(toolState.lengthMeters)}
            </span>
            {toolState.bendCount > 0 && !toolState.complete && (
              <button className="ghost small" onClick={() => tool.current?.removeLastBend()}>
                Urungkan titik
              </button>
            )}
            <button className="ghost small" onClick={cancelTool}>
              Batal
            </button>
          </div>
        )}

        {/* Bilah petunjuk saat mengedit jalur */}
        {editingMode && (
          <div className="map-hint">
            <IconRoute size={16} />
            <span>Geser titik belok · klik garis untuk sisip · klik-ganda titik untuk hapus</span>
            <span className="tnum" style={{ marginLeft: 'auto', fontWeight: 600 }}>
              {formatLength(toolState?.lengthMeters ?? 0)}
            </span>
            <button className="primary small" onClick={() => void saveEdit()}>
              Simpan
            </button>
            <button className="ghost small" onClick={cancelTool}>
              Batal
            </button>
          </div>
        )}

        {/* Panel simpan kabel baru (kedua ujung sudah ditentukan) */}
        {toolState?.complete && toolState.from && toolState.to && toolState.cableType && (
          <SaveCablePanel
            from={toolState.from.code}
            to={toolState.to.code}
            cableType={toolState.cableType}
            lengthMeters={toolState.lengthMeters}
            onCancel={cancelTool}
            onSave={saveNewCable}
          />
        )}

        {selected && <OdpPanel inspection={selected} onClose={() => setSelected(null)} />}
        {cable && (
          <CablePanel
            cable={cable}
            causes={cableCauses}
            canEdit={can('network.cable.update')}
            canDelete={can('network.cable.delete')}
            onEdit={() => startEdit(cable)}
            onDelete={() => void deleteCable(cable)}
            onClose={() => setCable(null)}
          />
        )}
      </div>
    </div>
  )
}

const TYPE_LABEL: Record<CableType, string> = {
  FEEDER: 'Feeder',
  DISTRIBUTION: 'Distribusi',
  DROP: 'Drop',
}

const DEFAULT_CORES: Record<CableType, number> = { FEEDER: 24, DISTRIBUTION: 12, DROP: 1 }

function formatLength(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`
}

function drawHint(state: ToolState): string {
  if (!state.from) return 'Klik perangkat sumber (POP, ODC, atau ODP)'
  if (!state.to) return `Dari ${state.from.code} — klik titik belok, lalu klik perangkat tujuan`
  return 'Selesai — isi detail kabel'
}

/** Membersihkan kode agar cocok pola server (huruf besar, alfanumerik + . _ / -). */
function sanitizeCode(raw: string): string {
  return raw
    .toUpperCase()
    .replace(/[^A-Z0-9._/-]/g, '-')
    .replace(/-+/g, '-')
    .slice(0, 40)
}

function SaveCablePanel({
  from,
  to,
  cableType,
  lengthMeters,
  onCancel,
  onSave,
}: {
  from: string
  to: string
  cableType: CableType
  lengthMeters: number
  onCancel: () => void
  onSave: (form: { code: string; name: string; coreCount: number }) => void
}) {
  const [code, setCode] = useState(sanitizeCode(`CBL-${from}-${to}`))
  const [name, setName] = useState(`${TYPE_LABEL[cableType]} ${from} → ${to}`)
  const [coreCount, setCoreCount] = useState(DEFAULT_CORES[cableType])

  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>Kabel baru</h3>
        <button className="ghost icon-btn" onClick={onCancel} aria-label="Batal">
          <IconClose size={18} />
        </button>
      </div>
      <div className="row" style={{ gap: '0.5rem' }}>
        <StatusBadge status="ACTIVE" label={TYPE_LABEL[cableType]} />
        <span className="badge accent">
          {from} → {to}
        </span>
        <span className="badge tnum">{formatLength(lengthMeters)}</span>
      </div>
      <label>
        <span>Kode</span>
        <input value={code} onChange={(e) => setCode(e.target.value)} />
      </label>
      <label>
        <span>Nama</span>
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        <span>Jumlah core</span>
        <input type="number" min={1} max={288} value={coreCount} onChange={(e) => setCoreCount(Number(e.target.value))} />
      </label>
      <div className="row">
        <button
          className="primary"
          disabled={!code.trim() || !name.trim()}
          onClick={() => onSave({ code: sanitizeCode(code), name, coreCount })}
        >
          Simpan kabel
        </button>
        <button className="ghost" onClick={onCancel}>
          Batal
        </button>
      </div>
    </aside>
  )
}

function CablePanel({
  cable,
  causes,
  canEdit,
  canDelete,
  onEdit,
  onDelete,
  onClose,
}: {
  cable: CableView
  causes: ImpactCause[]
  canEdit: boolean
  canDelete: boolean
  onEdit: () => void
  onDelete: () => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{cable.code}</h3>
        <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {cable.name}
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <StatusBadge status="ACTIVE" label={TYPE_LABEL[cable.cableType]} />
        <span className="badge">{cable.coreCount} core</span>
        <span className="badge tnum">{formatLength(cable.lengthMeters)}</span>
        <StatusBadge status={cable.status} />
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        {cable.fromKind} → {cable.toKind} · {cable.route.points.length} titik jalur
      </p>
      {causes.length > 0 && <CableCauses causes={causes} />}
      {(canEdit || canDelete) && (
        <div className="row">
          {canEdit && (
            <button className="primary" onClick={onEdit}>
              <IconRoute size={15} /> Edit jalur
            </button>
          )}
          {canDelete && (
            <button className="ghost danger" onClick={onDelete}>
              Hapus
            </button>
          )}
        </div>
      )}
    </aside>
  )
}

const ALARM_LABEL: Record<string, string> = {
  ONU_LOS: 'Sinyal hilang (LOS)',
  ONU_OFFLINE: 'ONU offline',
  ONU_LOW_RX: 'Redaman lemah',
  OLT_UNREACHABLE: 'OLT tak terjangkau',
  ODC_UNREACHABLE: 'ODC tak terjangkau',
  COLLECTOR_SILENT: 'Collector membisu',
}

const CAUSE_DOT: Record<string, string> = { CRITICAL: '#ff3b5c', WARNING: '#fbbf24' }

/** Bagian "kenapa merah": alarm hidup di hilir yang menyorot kabel ini. */
function CableCauses({ causes }: { causes: ImpactCause[] }) {
  return (
    <div className="stack" style={{ gap: '0.4rem', borderTop: '1px solid var(--line, #2a3550)', paddingTop: '0.6rem' }}>
      <span style={{ fontSize: '0.8rem', fontWeight: 600, color: '#ff5470' }}>
        Kenapa merah — {causes.length} alarm hidup di hilir
      </span>
      {causes.map((c, i) => (
        <div key={`${c.kind}-${c.label}-${i}`} className="row" style={{ gap: '0.45rem', alignItems: 'center' }}>
          <span
            style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              flexShrink: 0,
              background: CAUSE_DOT[c.severity] ?? 'var(--muted)',
            }}
          />
          <span className="badge">{ALARM_LABEL[c.kind] ?? c.kind}</span>
          <span
            className="muted"
            style={{ fontSize: '0.82rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          >
            {c.label}
          </span>
        </div>
      ))}
    </div>
  )
}

function Legend() {
  const items: Array<[string, string]> = [
    ['#b47cff', 'Site/POP'],
    ['#22d3ee', 'ODC'],
    ['#fbbf24', 'ODP'],
    ['#34d399', 'Pelanggan online'],
    ['#ff5470', 'ONU mati'],
  ]
  return (
    <div className="row" style={{ flexWrap: 'wrap', gap: '0.75rem' }}>
      {items.map(([color, label]) => (
        <span key={label} className="row" style={{ gap: '0.35rem', alignItems: 'center' }}>
          <span style={{ width: 10, height: 10, borderRadius: '50%', background: color, display: 'inline-block' }} />
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            {label}
          </span>
        </span>
      ))}
    </div>
  )
}

/** Panel jawaban atas pertanyaan lapangan: "di ODP ini ada siapa saja, port mana yang kosong?" */
function OdpPanel({ inspection, onClose }: { inspection: OdpInspection; onClose: () => void }) {
  const { upstream } = inspection
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{inspection.code}</h3>
        <button onClick={onClose}>Tutup</button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {inspection.name}
      </p>

      <div>
        <div className="spread" style={{ marginBottom: '0.35rem' }}>
          <strong>
            {inspection.usedPorts}/{inspection.capacity} port terpakai
          </strong>
          <span className="badge">{inspection.utilizationPercent}%</span>
        </div>
        <div className="meter">
          <div
            className={`meter-fill ${
              inspection.utilizationPercent >= 90 ? 'crit' : inspection.utilizationPercent >= 70 ? 'warn' : ''
            }`}
            style={{ width: `${inspection.utilizationPercent}%` }}
          />
        </div>
        <p className="muted" style={{ margin: '0.4rem 0 0', fontSize: '0.85rem' }}>
          Port kosong: {inspection.availablePortNumbers.join(', ') || '—'}
        </p>
      </div>

      <div>
        <strong>Jalur hulu</strong>
        <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem', lineHeight: 1.6 }}>
          ODC {upstream.odcCode ?? '—'} → PON {upstream.ponPortLabel ?? '—'} → OLT {upstream.oltCode ?? '—'} → site{' '}
          {upstream.siteCode ?? '—'}
          <br />
          Rugi splitter {upstream.splitterLossDb.toFixed(1)} dB{' '}
          {!upstream.complete && <span className="badge">jalur belum lengkap</span>}
        </p>
      </div>

      <div>
        <strong>Pelanggan ({inspection.occupants.length})</strong>
        {inspection.occupants.length === 0 ? (
          <p className="muted" style={{ margin: '0.25rem 0 0' }}>
            Belum ada pelanggan tersambung.
          </p>
        ) : (
          <table style={{ marginTop: '0.5rem' }}>
            <thead>
              <tr>
                <th>Port</th>
                <th>Pelanggan</th>
                <th>ONU</th>
                <th>Optik</th>
              </tr>
            </thead>
            <tbody>
              {inspection.occupants.map((occupant) => (
                <tr key={occupant.portNumber}>
                  <td>{occupant.portNumber}</td>
                  <td>
                    {occupant.customerName}
                    <br />
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {occupant.phone ?? occupant.customerCode}
                    </span>
                  </td>
                  <td>
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {occupant.onuSerialNumber}
                    </span>
                    <br />
                    <StatusBadge status={occupant.onuStatus} />
                  </td>
                  <td>
                    <span style={{ color: HEALTH_COLOR[occupant.opticalHealth], fontWeight: 600 }}>
                      {occupant.installRxPowerDbm != null ? `${occupant.installRxPowerDbm} dBm` : occupant.opticalHealth}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </aside>
  )
}
