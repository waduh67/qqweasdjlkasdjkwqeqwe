import { useEffect, useMemo, useRef, useState } from 'react'
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { api, ApiError, tokenStore } from '../api/client'
import type {
  AffectedCustomer,
  BlastRadiusView,
  CableCutView,
  CableType,
  CableView,
  CustomerTrace,
  ImpactCause,
  ImpactedOverlay,
  OdcView,
  OdpInspection,
  SiteInspection,
  SiteOlt,
  TraceHop,
} from '../api/network'
import type { PageResponse } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { StatusBadge, useToast } from '../components/ui'
import { IconClose, IconPlus, IconRoute } from '../components/icons'
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

/**
 * Warna sorotan simulasi "kalau putus" — amber, sengaja beda dari merah alarm
 * hidup: yang ini hipotetis (belum terjadi), bukan gangguan nyata yang berjalan.
 */
const WHATIF_COLOR = '#f59e0b'

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
      paint: { 'line-color': CABLE_COLOR, 'line-width': zoomWidth(5, 2.2), 'line-blur': 4, 'line-opacity': 0.35 },
    },
    {
      id: 'cable',
      type: 'line',
      source: 'ftth',
      'source-layer': 'cable',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': CABLE_COLOR, 'line-width': zoomWidth(1.2, 0.5), 'line-opacity': 0.95 },
    },
    {
      id: 'cable-flow',
      type: 'line',
      source: 'ftth',
      'source-layer': 'cable',
      layout: { 'line-cap': 'round' },
      paint: {
        'line-color': '#eaffff',
        'line-width': zoomWidth(1.2, 0.5),
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

/** Meng-escape teks agar aman disisipkan ke markup SVG watermark. */
function escapeXml(raw: string): string {
  return raw.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}

/**
 * Membuat ubin SVG berisi label pengguna yang dimiringkan, untuk dijadikan
 * `background` berulang di atas kanvas. Tujuannya jejak akuntabilitas: bila peta
 * di-screenshot, nama & email peng-capture ikut terekam. Sengaja sangat samar
 * (opasitas rendah) agar tidak mengganggu pembacaan peta.
 */
function watermarkTile(label: string): string {
  const text = escapeXml(label)
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="340" height="180">` +
    `<text x="20" y="150" transform="rotate(-28 20 150)" ` +
    `font-family="system-ui,-apple-system,sans-serif" font-size="13" font-weight="600" ` +
    `fill="rgba(255,255,255,0.07)" letter-spacing="0.5">${text}</text></svg>`
  return `url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}")`
}

/** Perangkat titik yang bisa ditaruh langsung di peta (punya koordinat sendiri). */
type AssetKind = 'SITE' | 'ODC' | 'ODP'

const ASSET_META: Record<AssetKind, { label: string; createPerm: string; deletePerm: string; endpoint: string }> = {
  SITE: { label: 'Site/POP', createPerm: 'network.site.create', deletePerm: 'network.site.delete', endpoint: '/api/sites' },
  ODC: { label: 'ODC', createPerm: 'network.odc.create', deletePerm: 'network.odc.delete', endpoint: '/api/odcs' },
  ODP: { label: 'ODP', createPerm: 'network.odp.create', deletePerm: 'network.odp.delete', endpoint: '/api/odps' },
}

export function MapPage() {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const tool = useRef<CableTool | null>(null)
  const modeRef = useRef<'idle' | 'draw' | 'edit' | 'place'>('idle')
  // Jenis perangkat yang sedang ditaruh (mode 'place'), dibaca handler klik peta.
  const placeKindRef = useRef<AssetKind | null>(null)
  const animRef = useRef<number | null>(null)
  const impactedRef = useRef<number | null>(null)
  // Penyebab per kabel (id → alarm hidup di hilir), diisi tiap overlay disegarkan
  // dan dibaca saat kabel diklik untuk menjelaskan "kenapa merah".
  const impactedCauses = useRef<Map<string, ImpactCause[]>>(new Map())
  const [selected, setSelected] = useState<OdpInspection | null>(null)
  const [cable, setCable] = useState<CableView | null>(null)
  const [cableCauses, setCableCauses] = useState<ImpactCause[]>([])
  const [blast, setBlast] = useState<BlastRadiusView | null>(null)
  // Simulasi "kalau kabel ini putus" — panel + sorotan subpohon terputus.
  const [whatIf, setWhatIf] = useState<CableCutView | null>(null)
  const [trace, setTrace] = useState<CustomerTrace | null>(null)
  const [siteInsp, setSiteInsp] = useState<SiteInspection | null>(null)
  const [editing, setEditing] = useState<CableView | null>(null)
  const [toolState, setToolState] = useState<ToolState | null>(null)
  // Mode taruh perangkat baru: jenis yang dipilih, dan lokasi klik yang menunggu form.
  const [placing, setPlacing] = useState<AssetKind | null>(null)
  const [placeAt, setPlaceAt] = useState<{ kind: AssetKind; lng: number; lat: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { can } = useCan()
  const { user } = useAuth()
  const toast = useToast()

  // Label watermark: siapa yang sedang melihat peta ini. Dihitung sekali per user.
  const watermark = useMemo(() => {
    const label = [user?.name, user?.email].filter(Boolean).join(' · ') || 'FTTH OSS'
    return watermarkTile(label)
  }, [user?.name, user?.email])

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

    // Kontrol zoom di kanan-bawah agar tidak tertimpa panel info yang mengambang
    // di pojok kanan-atas peta.
    instance.addControl(new maplibregl.NavigationControl(), 'bottom-right')
    instance.addControl(new maplibregl.ScaleControl(), 'bottom-left')

    // Menutup semua panel info sebelum membuka yang baru — hanya satu tampil.
    const clearPanels = () => {
      setSelected(null)
      setCable(null)
      setBlast(null)
      setWhatIf(null)
      setTrace(null)
      setSiteInsp(null)
    }

    // Menaruh perangkat baru: klik peta mana pun jadi lokasinya, lalu form muncul.
    instance.on('click', (event) => {
      if (modeRef.current !== 'place') return
      const kind = placeKindRef.current
      if (!kind) return
      modeRef.current = 'idle'
      placeKindRef.current = null
      setPlacing(null)
      instance.getCanvas().style.cursor = ''
      setPlaceAt({ kind, lng: event.lngLat.lng, lat: event.lngLat.lat })
    })

    instance.on('click', 'odp', (event) => {
      // Selagi menggambar/mengedit kabel atau menaruh perangkat, klik dikuasai alat itu.
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      api
        .get<OdpInspection>(`/api/gis/odps/${id}`)
        .then((odp) => {
          clearPanels()
          setSelected(odp)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODP'))
    })

    // Klik pelanggan (mode idle) → telusur jalur ONU → ODP → ODC → OLT.
    instance.on('click', 'customer', (event) => {
      if (modeRef.current !== 'idle') return
      const id = event.features?.[0]?.properties?.id as string | undefined
      if (!id) return
      api
        .get<CustomerTrace>(`/api/gis/trace/customers/${id}`)
        .then((t) => {
          clearPanels()
          setTrace(t)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat telusur pelanggan'))
    })

    // Klik site/POP (mode idle) → isi site: OLT + rekap perangkat & pelanggan hilir.
    instance.on('click', 'site', (event) => {
      if (modeRef.current !== 'idle') return
      const id = event.features?.[0]?.properties?.id as string | undefined
      if (!id) return
      api
        .get<SiteInspection>(`/api/gis/sites/${id}`)
        .then((s) => {
          clearPanels()
          setSiteInsp(s)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail site'))
    })

    // Klik ODC (mode idle) → blast radius: siapa saja di hilirnya.
    instance.on('click', 'odc', (event) => {
      if (modeRef.current !== 'idle') return
      const id = event.features?.[0]?.properties?.id as string | undefined
      if (!id) return
      api
        .get<BlastRadiusView>(`/api/gis/odcs/${id}/blast-radius`)
        .then((b) => {
          clearPanels()
          setBlast(b)
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat blast radius ODC'))
    })

    // Klik kabel (mode idle) → tampilkan detail + aksi edit/hapus.
    instance.on('click', 'cable', (event) => {
      if (modeRef.current !== 'idle') return
      const id = event.features?.[0]?.properties?.id as string | undefined
      if (!id) return
      api
        .get<CableView>(`/api/cables/${id}`)
        .then((c) => {
          clearPanels()
          setCable(c)
          // Kalau kabel ini sedang merah, sertakan alarm penyebabnya.
          setCableCauses(impactedCauses.current.get(id) ?? [])
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail kabel'))
    })

    for (const layer of ['odp', 'odc', 'cable', 'customer', 'site']) {
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
          paint: { 'line-color': SEVERITY_COLOR, 'line-width': zoomWidth(7, 3), 'line-blur': 5, 'line-opacity': 0.5 },
        },
        'customer-glow',
      )
      instance.addLayer(
        {
          id: 'impacted-core',
          type: 'line',
          source: 'impacted',
          layout: { 'line-cap': 'round' },
          paint: { 'line-color': SEVERITY_COLOR, 'line-width': zoomWidth(1.6, 0.7), 'line-opacity': 0.95 },
        },
        'customer-glow',
      )
      void refreshImpacted()
      impactedRef.current = window.setInterval(() => {
        if (!document.hidden) void refreshImpacted()
      }, 30_000)

      // Overlay simulasi "kalau putus": subpohon kabel yang lenyap disorot amber
      // dan putus-putus — hipotetis, dibedakan dari merah alarm hidup. Diisi
      // imperatif dari state `whatIf` (lihat efek sinkron di bawah).
      instance.addSource('whatif', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      instance.addLayer(
        {
          id: 'whatif-glow',
          type: 'line',
          source: 'whatif',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': WHATIF_COLOR, 'line-width': zoomWidth(8, 3.5), 'line-blur': 6, 'line-opacity': 0.45 },
        },
        'customer-glow',
      )
      instance.addLayer(
        {
          id: 'whatif-core',
          type: 'line',
          source: 'whatif',
          layout: { 'line-cap': 'round' },
          paint: {
            'line-color': WHATIF_COLOR,
            'line-width': zoomWidth(2, 0.9),
            'line-opacity': 0.95,
            'line-dasharray': [2, 2],
          },
        },
        'customer-glow',
      )

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

  // Menyorot subpohon terputus saat panel simulasi terbuka; kosongkan saat tutup.
  useEffect(() => {
    const src = map.current?.getSource('whatif') as GeoJSONSource | undefined
    if (!src) return
    src.setData({
      type: 'FeatureCollection',
      features: (whatIf?.severedCables ?? []).map((c) => ({
        type: 'Feature',
        properties: {},
        geometry: { type: 'LineString', coordinates: c.points.map((p) => [p.longitude, p.latitude]) },
      })),
    })
  }, [whatIf])

  const startDraw = () => {
    // Kalau sedang menaruh perangkat, batalkan dulu — satu alat aktif pada satu waktu.
    if (placing) cancelPlace()
    setSelected(null)
    setCable(null)
    setEditing(null)
    tool.current?.startDraw()
  }

  const cancelTool = () => {
    tool.current?.cancel()
    setEditing(null)
  }

  /** Masuk mode taruh: klik peta berikutnya menentukan lokasi perangkat baru. */
  const startPlace = (kind: AssetKind) => {
    tool.current?.cancel()
    setSelected(null)
    setCable(null)
    setBlast(null)
    setTrace(null)
    setSiteInsp(null)
    setEditing(null)
    setPlaceAt(null)
    placeKindRef.current = kind
    modeRef.current = 'place'
    setPlacing(kind)
    if (map.current) map.current.getCanvas().style.cursor = 'crosshair'
  }

  const cancelPlace = () => {
    placeKindRef.current = null
    modeRef.current = 'idle'
    setPlacing(null)
    setPlaceAt(null)
    if (map.current) map.current.getCanvas().style.cursor = ''
  }

  /** Menyimpan perangkat titik baru di lokasi yang diklik, lalu menyegarkan tile. */
  const savePlacedAsset = async (payload: Record<string, unknown>) => {
    if (!placeAt) return
    const meta = ASSET_META[placeAt.kind]
    try {
      await api.post(meta.endpoint, {
        ...payload,
        location: { longitude: placeAt.lng, latitude: placeAt.lat },
      })
      toast.success(`${meta.label} ${String(payload.code ?? '')} tersimpan`)
      setPlaceAt(null)
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal menyimpan ${meta.label}`)
    }
  }

  /** Menghapus perangkat titik dari panelnya; server menolak bila masih dipakai hilir. */
  const deleteAsset = async (kind: AssetKind, id: string, code: string, onDone: () => void) => {
    const meta = ASSET_META[kind]
    try {
      await api.del(`${meta.endpoint}/${id}`)
      toast.success(`${meta.label} ${code} dihapus`)
      onDone()
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal menghapus ${meta.label}`)
    }
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

  /** Simulasi "kalau kabel ini putus": tukar panel kabel ke panel dampak + sorotan. */
  const simulateCut = async (c: CableView) => {
    try {
      const view = await api.get<CableCutView>(`/api/gis/cables/${c.id}/blast-radius`)
      setCable(null)
      setWhatIf(view)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal memuat simulasi putus kabel')
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
        {/* Kanvas dibungkus agar watermark akuntabilitas hanya menutup peta,
            bukan panel di sampingnya. */}
        <div className="map-canvas-wrap">
          <div ref={container} className="map-canvas" />
          <div className="map-watermark" aria-hidden="true" style={{ backgroundImage: watermark }} />
        </div>

        {/* Toolbar kiri-atas: tarik kabel + taruh perangkat. Tampil saat idle —
            termasuk state awal sebelum alat pernah dipakai (toolState masih null). */}
        {(!toolState || toolState.mode === 'idle') && !placing && !placeAt && (
          <MapToolbar can={can} onDraw={startDraw} onPlace={startPlace} />
        )}

        {/* Bilah petunjuk saat menaruh perangkat baru */}
        {placing && (
          <div className="map-hint">
            <IconPlus size={16} />
            <span>Klik lokasi di peta untuk menaruh {ASSET_META[placing].label} baru</span>
            <button className="ghost small" style={{ marginLeft: 'auto' }} onClick={cancelPlace}>
              Batal
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

        {blast && (
          <BlastRadiusPanel
            blast={blast}
            canDelete={can('network.odc.delete')}
            onDelete={() => void deleteAsset('ODC', blast.odcId, blast.code, () => setBlast(null))}
            onClose={() => setBlast(null)}
          />
        )}
        {whatIf && <CableCutPanel cut={whatIf} onClose={() => setWhatIf(null)} />}
        {trace && <CustomerTracePanel trace={trace} onClose={() => setTrace(null)} />}
        {siteInsp && (
          <SitePanel
            site={siteInsp}
            canDelete={can('network.site.delete')}
            onDelete={() => void deleteAsset('SITE', siteInsp.siteId, siteInsp.code, () => setSiteInsp(null))}
            onClose={() => setSiteInsp(null)}
          />
        )}
        {selected && (
          <OdpPanel
            inspection={selected}
            canDelete={can('network.odp.delete')}
            onDelete={() => void deleteAsset('ODP', selected.odpId, selected.code, () => setSelected(null))}
            onClose={() => setSelected(null)}
          />
        )}
        {placeAt && (
          <PlaceAssetForm
            kind={placeAt.kind}
            lng={placeAt.lng}
            lat={placeAt.lat}
            onCancel={() => setPlaceAt(null)}
            onSave={savePlacedAsset}
          />
        )}
        {cable && (
          <CablePanel
            cable={cable}
            causes={cableCauses}
            canEdit={can('network.cable.update')}
            canDelete={can('network.cable.delete')}
            canSimulate={can('customer.customer.view')}
            onEdit={() => startEdit(cable)}
            onDelete={() => void deleteCable(cable)}
            onSimulate={() => void simulateCut(cable)}
            onClose={() => setCable(null)}
          />
        )}
      </div>
    </div>
  )
}

/**
 * Toolbar kiri-atas peta: tarik kabel + tombol taruh perangkat. Tiap tombol
 * hanya muncul bila pengguna punya izin membuat aset terkait, sehingga toolbar
 * menyesuaikan diri dengan peran — teknisi read-only tidak melihat apa pun.
 */
function MapToolbar({
  can,
  onDraw,
  onPlace,
}: {
  can: (perm: string) => boolean
  onDraw: () => void
  onPlace: (kind: AssetKind) => void
}) {
  const placeable = (Object.keys(ASSET_META) as AssetKind[]).filter((k) => can(ASSET_META[k].createPerm))
  if (!can('network.cable.create') && placeable.length === 0) return null
  return (
    <div className="map-toolbar">
      {can('network.cable.create') && (
        <button className="primary" onClick={onDraw}>
          <IconRoute size={16} /> Tarik kabel
        </button>
      )}
      {placeable.map((k) => (
        <button key={k} className="ghost" onClick={() => onPlace(k)}>
          <IconPlus size={15} /> {ASSET_META[k].label}
        </button>
      ))}
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
  canSimulate,
  onEdit,
  onDelete,
  onSimulate,
  onClose,
}: {
  cable: CableView
  causes: ImpactCause[]
  canEdit: boolean
  canDelete: boolean
  canSimulate: boolean
  onEdit: () => void
  onDelete: () => void
  onSimulate: () => void
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
      {canSimulate && (
        <button className="ghost" style={{ justifyContent: 'flex-start', color: WHATIF_COLOR }} onClick={onSimulate}>
          Simulasi putus — siapa yang kena?
        </button>
      )}
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

const CUT_ROOT_LABEL: Record<string, string> = {
  ODC: 'ODC + seluruh hilirnya',
  ODP: 'ODP sasaran',
  CUSTOMER: 'satu pelanggan',
}

/**
 * Panel simulasi "kalau kabel ini putus, siapa yang kena". Kabel drop menjatuhkan
 * satu pelanggan, distribusi satu ODP, feeder satu ODC beserta segenap subpohonnya
 * — dampaknya ditentukan simpul di ujung hilir kabel, yang ditandai di sini.
 */
function CableCutPanel({ cut, onClose }: { cut: CableCutView; onClose: () => void }) {
  const withPhone = cut.customers.filter((c) => c.phone).length
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{cut.cableCode}</h3>
        <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        Simulasi putus · {CUT_ROOT_LABEL[cut.severedRootKind] ?? cut.severedRootKind}
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        {cut.odcCount > 0 && <span className="badge">{cut.odcCount} ODC</span>}
        {cut.odpCount > 0 && <span className="badge">{cut.odpCount} ODP</span>}
        <span className="badge">{cut.customerCount} pelanggan</span>
        {cut.downCount > 0 && (
          <span className="badge" style={{ color: '#ff5470', borderColor: '#ff5470' }}>
            {cut.downCount} sudah mati
          </span>
        )}
      </div>
      <p style={{ margin: 0, fontSize: '0.82rem', color: WHATIF_COLOR }}>
        Kalau ruas ini putus, {cut.customerCount} pelanggan kehilangan layanan.
      </p>
      {cut.customers.length > 0 && (
        <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
          {cut.customers.map((c) => (
            <AffectedRow key={c.customerId} c={c} />
          ))}
        </div>
      )}
      {withPhone > 0 && (
        <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
          {withPhone} nomor siap untuk broadcast pemberitahuan.
        </p>
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

const ONU_DOT: Record<string, string> = {
  ONLINE: '#34d399',
  LOS: '#ff3b5c',
  OFFLINE: '#fbbf24',
  PENDING: '#8b95a7',
  DISMANTLED: '#8b95a7',
}

/** Panel "kalau ODC ini putus, siapa yang kena" — daftar pelanggan hilir + kesiapan broadcast. */
function BlastRadiusPanel({
  blast,
  canDelete,
  onDelete,
  onClose,
}: {
  blast: BlastRadiusView
  canDelete: boolean
  onDelete: () => void
  onClose: () => void
}) {
  const withPhone = blast.customers.filter((c) => c.phone).length
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{blast.code}</h3>
        <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {blast.name} · blast radius
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <StatusBadge
          status={blast.energized ? 'ACTIVE' : 'INACTIVE'}
          label={blast.energized ? 'Berenergi' : 'Tanpa uplink'}
        />
        <span className="badge">{blast.odpCount} ODP</span>
        <span className="badge">{blast.customerCount} pelanggan</span>
        {blast.downCount > 0 && (
          <span className="badge" style={{ color: '#ff5470', borderColor: '#ff5470' }}>
            {blast.downCount} mati
          </span>
        )}
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Kalau ODC ini putus, {blast.customerCount} pelanggan kehilangan layanan.
      </p>
      {blast.customers.length > 0 && (
        <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
          {blast.customers.map((c) => (
            <AffectedRow key={c.customerId} c={c} />
          ))}
        </div>
      )}
      {withPhone > 0 && (
        <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
          {withPhone} nomor siap untuk broadcast pemberitahuan.
        </p>
      )}
      {canDelete && (
        <div className="row">
          <button className="ghost danger" onClick={onDelete}>
            Hapus ODC
          </button>
        </div>
      )}
    </aside>
  )
}

function AffectedRow({ c }: { c: AffectedCustomer }) {
  return (
    <div className="spread" style={{ gap: '0.45rem', alignItems: 'center' }}>
      <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flexShrink: 0,
            background: ONU_DOT[c.onuStatus] ?? 'var(--muted)',
          }}
        />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name}</span>
      </span>
      <span className="muted tnum" style={{ fontSize: '0.78rem', flexShrink: 0 }}>
        {c.odpCode}
      </span>
    </div>
  )
}

const HOP_LABEL: Record<string, string> = {
  CUSTOMER: 'Pelanggan',
  ONU: 'ONU',
  ODP: 'ODP',
  ODC: 'ODC',
  OLT: 'OLT',
  PON: 'PON',
  SITE: 'Site/POP',
}

/**
 * Panel telusur pelanggan: jalur fisik dari rumah pelanggan menaiki topologi
 * sampai OLT, dengan status optik dan perkiraan anggaran redaman — menjawab
 * "kenapa pelanggan ini bermasalah dan lewat mana kabelnya".
 */
function CustomerTracePanel({ trace, onClose }: { trace: CustomerTrace; onClose: () => void }) {
  const up = trace.upstream
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{trace.customerName}</h3>
        <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {trace.customerCode}
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        {trace.onuStatus && <StatusBadge status={trace.onuStatus} />}
        {trace.onuSerialNumber && <span className="badge">{trace.onuSerialNumber}</span>}
        {trace.odpPortNumber != null && <span className="badge">port {trace.odpPortNumber}</span>}
      </div>

      {(trace.installRxPowerDbm != null || trace.opticalHealth || trace.estimatedLossDb != null) && (
        <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'center' }}>
          {trace.installRxPowerDbm != null && (
            <span style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'], fontWeight: 600 }}>
              {trace.installRxPowerDbm} dBm
            </span>
          )}
          {trace.opticalHealth && trace.installRxPowerDbm == null && (
            <span style={{ color: HEALTH_COLOR[trace.opticalHealth], fontWeight: 600 }}>{trace.opticalHealth}</span>
          )}
          {trace.estimatedLossDb != null && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              perkiraan rugi total {trace.estimatedLossDb.toFixed(1)} dB
            </span>
          )}
        </div>
      )}

      {up && (
        <div>
          <strong>Jalur hulu</strong>
          <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem', lineHeight: 1.6 }}>
            ODC {up.odcCode ?? '—'} → PON {up.ponPortLabel ?? '—'} → OLT {up.oltCode ?? '—'} → site{' '}
            {up.siteCode ?? '—'} {!up.complete && <span className="badge">jalur belum lengkap</span>}
          </p>
        </div>
      )}

      {trace.hops.length > 0 && (
        <div>
          <strong>Telusur jalur ({trace.hops.length})</strong>
          <ol className="timeline" style={{ marginTop: '0.5rem' }}>
            {trace.hops.map((hop: TraceHop, i: number) => (
              <li key={`${hop.kind}-${hop.code}-${i}`}>
                <span className="tl-dot" aria-hidden="true" />
                <div className="stack" style={{ gap: '0.1rem' }}>
                  <strong style={{ fontSize: '0.85rem' }}>
                    {HOP_LABEL[hop.kind] ?? hop.kind} {hop.code}
                  </strong>
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {hop.name}
                  </span>
                </div>
              </li>
            ))}
          </ol>
        </div>
      )}
    </aside>
  )
}

/**
 * Panel isi sebuah site/POP: OLT yang berdiri di sini plus rekap seluruh
 * perangkat & pelanggan di hilirnya — "seberapa besar site ini". Menghapus site
 * ditolak server selama masih ada OLT terpasang, jadi tombolnya dikunci lebih dulu.
 */
function SitePanel({
  site,
  canDelete,
  onDelete,
  onClose,
}: {
  site: SiteInspection
  canDelete: boolean
  onDelete: () => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{site.code}</h3>
        <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {site.name}
      </p>
      {site.address && (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          {site.address}
        </p>
      )}
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <span className="badge">{site.oltCount} OLT</span>
        <span className="badge">{site.odcCount} ODC</span>
        <span className="badge">{site.odpCount} ODP</span>
        <span className="badge accent">{site.customerCount} pelanggan</span>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Seluruh perangkat & pelanggan yang bergantung pada site ini.
      </p>
      {site.olts.length > 0 && (
        <div className="stack" style={{ gap: '0.3rem' }}>
          <strong style={{ fontSize: '0.85rem' }}>OLT di site ini</strong>
          {site.olts.map((olt) => (
            <SiteOltRow key={olt.id} olt={olt} />
          ))}
        </div>
      )}
      {canDelete && (
        <div className="row">
          <button className="ghost danger" onClick={onDelete} disabled={site.oltCount > 0}>
            Hapus site
          </button>
          {site.oltCount > 0 && (
            <span className="muted" style={{ fontSize: '0.78rem', alignSelf: 'center' }}>
              Masih ada OLT terpasang.
            </span>
          )}
        </div>
      )}
    </aside>
  )
}

function SiteOltRow({ olt }: { olt: SiteOlt }) {
  return (
    <div className="spread" style={{ gap: '0.45rem', alignItems: 'center' }}>
      <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flexShrink: 0,
            background: olt.active ? '#34d399' : 'var(--muted)',
          }}
        />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{olt.name}</span>
      </span>
      <span className="muted tnum" style={{ fontSize: '0.78rem', flexShrink: 0 }}>
        {olt.code} · {olt.vendor}
      </span>
    </div>
  )
}

/** Rasio splitter yang lazim dipakai — cukup untuk sebagian besar pemasangan. */
const SPLITTER_RATIOS = ['1:2', '1:4', '1:8', '1:16', '1:32', '1:64']

/**
 * Form isian perangkat titik baru, muncul setelah lokasi diklik di peta. Field
 * menyesuaikan jenis: Site cukup alamat, ODC/ODP butuh rasio splitter & kapasitas,
 * dan ODP boleh langsung ditautkan ke ODC induknya. Koordinat diambil dari titik
 * klik (ditampilkan, tak bisa diubah manual di sini).
 */
function PlaceAssetForm({
  kind,
  lng,
  lat,
  onCancel,
  onSave,
}: {
  kind: AssetKind
  lng: number
  lat: number
  onCancel: () => void
  onSave: (payload: Record<string, unknown>) => void
}) {
  const meta = ASSET_META[kind]
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [splitterRatio, setSplitterRatio] = useState('1:8')
  const [capacity, setCapacity] = useState(kind === 'ODP' ? 8 : 64)
  const [odcId, setOdcId] = useState('')
  const [odcs, setOdcs] = useState<OdcView[]>([])

  // Daftar ODC untuk memilih induk sebuah ODP. Hanya relevan saat menaruh ODP.
  useEffect(() => {
    if (kind !== 'ODP') return
    let alive = true
    api
      .get<PageResponse<OdcView>>('/api/odcs?size=100')
      .then((page) => {
        if (alive) setOdcs(page.content)
      })
      .catch(() => {
        /* pemilih induk opsional — biarkan kosong bila gagal */
      })
    return () => {
      alive = false
    }
  }, [kind])

  const submit = () => {
    const base: Record<string, unknown> = { code: sanitizeCode(code), name: name.trim() }
    if (address.trim()) base.address = address.trim()
    if (kind === 'SITE') {
      onSave(base)
      return
    }
    base.splitterRatio = splitterRatio
    base.capacity = capacity
    if (kind === 'ODP' && odcId) base.odcId = odcId
    onSave(base)
  }

  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{meta.label} baru</h3>
        <button className="ghost icon-btn" onClick={onCancel} aria-label="Batal">
          <IconClose size={18} />
        </button>
      </div>
      <p className="muted tnum" style={{ margin: 0, fontSize: '0.82rem' }}>
        {lat.toFixed(6)}, {lng.toFixed(6)}
      </p>
      <label>
        <span>Kode</span>
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder={`${kind}-001`} />
      </label>
      <label>
        <span>Nama</span>
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        <span>Alamat {kind !== 'SITE' && <span className="muted">(opsional)</span>}</span>
        <input value={address} onChange={(e) => setAddress(e.target.value)} />
      </label>
      {kind === 'ODP' && (
        <label>
          <span>ODC induk</span>
          <select value={odcId} onChange={(e) => setOdcId(e.target.value)}>
            <option value="">— belum ditautkan —</option>
            {odcs.map((odc) => (
              <option key={odc.id} value={odc.id}>
                {odc.code} — {odc.name}
              </option>
            ))}
          </select>
        </label>
      )}
      {kind !== 'SITE' && (
        <div className="row" style={{ gap: '0.5rem' }}>
          <label style={{ flex: 1 }}>
            <span>Rasio splitter</span>
            <select value={splitterRatio} onChange={(e) => setSplitterRatio(e.target.value)}>
              {SPLITTER_RATIOS.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>
          <label style={{ flex: 1 }}>
            <span>Kapasitas</span>
            <input
              type="number"
              min={1}
              max={kind === 'ODP' ? 256 : 1024}
              value={capacity}
              onChange={(e) => setCapacity(Number(e.target.value))}
            />
          </label>
        </div>
      )}
      <div className="row">
        <button className="primary" disabled={!code.trim() || !name.trim()} onClick={submit}>
          Simpan {meta.label}
        </button>
        <button className="ghost" onClick={onCancel}>
          Batal
        </button>
      </div>
    </aside>
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
function OdpPanel({
  inspection,
  canDelete,
  onDelete,
  onClose,
}: {
  inspection: OdpInspection
  canDelete: boolean
  onDelete: () => void
  onClose: () => void
}) {
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

      {canDelete && (
        <div className="row">
          <button className="ghost danger" onClick={onDelete} disabled={inspection.occupants.length > 0}>
            Hapus ODP
          </button>
          {inspection.occupants.length > 0 && (
            <span className="muted" style={{ fontSize: '0.78rem', alignSelf: 'center' }}>
              Masih ada pelanggan tersambung.
            </span>
          )}
        </div>
      )}
    </aside>
  )
}
