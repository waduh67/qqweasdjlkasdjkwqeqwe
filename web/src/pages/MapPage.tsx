import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { api, ApiError, tokenStore } from '../api/client'
import type {
  AffectedCustomer,
  BlastRadiusView,
  CableCutView,
  CableEnd,
  CablePortOption,
  CableType,
  CableView,
  CustomerTrace,
  ImpactCause,
  ImpactedOverlay,
  NodeKind,
  OdpInspection,
  OltView,
  OnuView,
  OtdrEventType,
  OtdrTest,
  RecordOtdrTest,
  SiteInspection,
  SiteOlt,
  SiteView,
  TraceHop,
  UtilizationHeatmap,
} from '../api/network'
import type { PageResponse } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Button, Segmented, SelectField, StatusBadge, TextField } from '@/components/atoms'
import { useToast } from '@/system'
import { IconClose, IconCrosshair, IconPlus, IconRoute } from '@/components/atoms/icons'
import { createCableTool, type CableTool, type ToolState } from '../map/cableTool'

/**
 * Peta jaringan berbasis vector tile.
 *
 * Tile dirender PostGIS (`ST_AsMVT`) dan diambil per ubin, sehingga jumlah aset
 * yang tergambar tidak membebani browser — inilah yang membuat peta tetap ringan
 * di puluhan ribu titik. Klik sebuah ODP untuk melihat siapa yang tersambung.
 */

// Atribusi gabungan semua penyedia basemap (Carto & Esri) karena pengguna bisa
// berpindah mode; tetap ditampilkan apa pun mode yang aktif.
const MAP_ATTRIBUTION =
  '&copy; Kontributor OpenStreetMap &copy; CARTO &middot; Citra satelit &copy; Esri'

/** Pusat awal: Bekasi, sekadar titik berangkat sebelum data pertama masuk. */
const INITIAL_CENTER: [number, number] = [106.995, -6.243]

/**
 * Mode basemap yang bisa dipilih pengguna. Semua tile raster KEYLESS — memadai untuk
 * pengembangan; untuk PRODUKSI pindah ke penyedia berlangganan / tile sendiri (Carto
 * & Esri membatasi pemakaian komersial). Ganti mode cukup menukar tile & opacity
 * sumber raster `basemap` via `setTiles`, jadi tak menyentuh layer overlay vektor.
 * Catatan skema ubin: Carto memakai {z}/{x}/{y} (XYZ standar), Esri {z}/{y}/{x}.
 */
type BasemapMode = 'streets' | 'satellite' | 'dark'

const BASEMAPS: Record<BasemapMode, { label: string; tiles: string[]; opacity: number }> = {
  // Jalan/standar ala Google Maps (Carto Voyager) — enak untuk survei alamat.
  streets: {
    label: 'Peta',
    tiles: [
      'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
      'https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
      'https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
      'https://d.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
    ],
    opacity: 1,
  },
  // Satelit (Esri World Imagery) — verifikasi tiang/rumah dari citra udara.
  satellite: {
    label: 'Satelit',
    tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
    opacity: 1,
  },
  // NOC gelap (bawaan) — aset & kabel bercahaya paling menonjol di sini.
  dark: {
    label: 'Gelap',
    tiles: [
      'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
      'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
      'https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
    ],
    opacity: 0.85,
  },
}

/** Urutan tampil di pemilih: Peta → Satelit → Gelap. */
const BASEMAP_ORDER: BasemapMode[] = ['streets', 'satellite', 'dark']

/** Mode awal: tetap gelap (gaya NOC) agar aset & kabel bercahaya paling menonjol. */
const DEFAULT_BASEMAP: BasemapMode = 'dark'

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

/** Warna marker OLT — biru, sengaja lepas dari cyan ODC & ungu site agar perangkat aktif menonjol. */
const OLT_COLOR = '#4f9dff'

/** Warna simpul terdampak (OLT/ODC/ODP/pelanggan) saat alarm hidup — merah/amber, sama palet kabel. */
const NODE_CRITICAL_COLOR = '#ff3b5c'
const NODE_WARNING_COLOR = '#fbbf24'

/**
 * Warna sorotan simulasi "kalau putus" — amber, sengaja beda dari merah alarm
 * hidup: yang ini hipotetis (belum terjadi), bukan gangguan nyata yang berjalan.
 */
const WHATIF_COLOR = '#f59e0b'

/**
 * Warna titik perkiraan uji OTDR — magenta, sengaja lepas dari palet lain (merah
 * alarm, amber simulasi, gradasi heatmap, warna aset): penanda diagnostik yang
 * jelas "hasil ukur", bukan gangguan hidup maupun hipotesis.
 */
const OTDR_COLOR = '#f472b6'

/** Label peristiwa OTDR dalam bahasa Indonesia — dipakai daftar & dropdown form. */
const OTDR_EVENT_LABEL: Record<OtdrEventType, string> = {
  BREAK: 'Putus',
  HIGH_LOSS: 'Redaman tinggi',
  REFLECTION: 'Pantulan',
  SPLICE: 'Sambungan',
  END: 'Ujung serat',
}

const OTDR_EVENT_OPTIONS = Object.entries(OTDR_EVENT_LABEL) as [OtdrEventType, string][]

/**
 * Gradasi warna heatmap utilisasi port: hijau (lengang) → kuning → jingga →
 * merah (penuh). Diinterpolasi dari properti `util` (0–100) tiap titik ODP,
 * sehingga sekali pandang terlihat ODP mana yang butuh perluasan kapasitas.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const HEATMAP_COLOR: any = [
  'interpolate',
  ['linear'],
  ['get', 'util'],
  0,
  '#22c55e',
  50,
  '#eab308',
  75,
  '#f97316',
  100,
  '#ef4444',
]

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
      tiles: BASEMAPS[DEFAULT_BASEMAP].tiles,
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
    {
      id: 'basemap',
      type: 'raster',
      source: 'basemap',
      paint: { 'raster-opacity': BASEMAPS[DEFAULT_BASEMAP].opacity },
    },
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
    ...glowCircle('olt', 'olt', OLT_COLOR, 9),
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
    {
      id: 'olt-label',
      type: 'symbol',
      source: 'ftth',
      'source-layer': 'olt',
      minzoom: 13,
      layout: { 'text-field': ['get', 'code'], 'text-size': 12, 'text-offset': [0, 1.6] },
      paint: { 'text-color': '#dbeafe', 'text-halo-color': '#0a0e14', 'text-halo-width': 1.5 },
    },
  ],
}

/**
 * Layer titik yang markernya diwarnai ulang saat perangkat/pelanggannya terdampak
 * alarm hidup ("perangkat modar → merah"). Cukup mencocokkan id fitur dengan daftar
 * simpul terdampak — id UUID unik global, jadi satu ekspresi berlaku lintas layer.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const NODE_LAYERS: Array<{ id: string; base: any }> = [
  { id: 'customer', base: CUSTOMER_COLOR },
  { id: 'odp', base: '#fbbf24' },
  { id: 'odc', base: '#22d3ee' },
  { id: 'olt', base: OLT_COLOR },
  { id: 'site', base: '#b47cff' },
]

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
type AssetKind = 'SITE' | 'OLT' | 'ODC' | 'ODP'

const ASSET_META: Record<AssetKind, { label: string; createPerm: string; deletePerm: string; endpoint: string }> = {
  SITE: { label: 'Site/POP', createPerm: 'network.site.create', deletePerm: 'network.site.delete', endpoint: '/api/sites' },
  OLT: { label: 'OLT', createPerm: 'network.olt.create', deletePerm: 'network.olt.delete', endpoint: '/api/olts' },
  ODC: { label: 'ODC', createPerm: 'network.odc.create', deletePerm: 'network.odc.delete', endpoint: '/api/odcs' },
  ODP: { label: 'ODP', createPerm: 'network.odp.create', deletePerm: 'network.odp.delete', endpoint: '/api/odps' },
}

/**
 * Layer titik yang koordinatnya bisa DIGESER langsung di peta. Kunci = id layer
 * lingkaran (sekaligus source-layer MVT), nilai = endpoint pindah-lokasi + izinnya
 * + warna pin sementara (senada warna markernya di peta). Semua endpoint menerima
 * body `{ longitude, latitude }` (`PUT /api/{plural}/{id}/location`), termasuk
 * pelanggan yang kabel drop-nya ikut menempel ulang di sisi server.
 */
const MOVABLE_NODES: Record<string, { plural: string; perm: string; label: string; color: string }> = {
  customer: { plural: 'customers', perm: 'customer.customer.update', label: 'Pelanggan', color: '#34d399' },
  odp: { plural: 'odps', perm: 'network.odp.update', label: 'ODP', color: '#fbbf24' },
  odc: { plural: 'odcs', perm: 'network.odc.update', label: 'ODC', color: '#22d3ee' },
  olt: { plural: 'olts', perm: 'network.olt.update', label: 'OLT', color: OLT_COLOR },
  site: { plural: 'sites', perm: 'network.site.update', label: 'Site', color: '#b47cff' },
}

export function MapPage() {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const tool = useRef<CableTool | null>(null)
  const modeRef = useRef<'idle' | 'draw' | 'edit' | 'place' | 'drag'>('idle')
  // Jenis perangkat yang sedang ditaruh (mode 'place'), dibaca handler klik peta.
  const placeKindRef = useRef<AssetKind | null>(null)
  const animRef = useRef<number | null>(null)
  const impactedRef = useRef<number | null>(null)
  // Pin yang bisa diseret untuk menyetel lokasi perangkat baru sebelum disimpan.
  const placeMarker = useRef<maplibregl.Marker | null>(null)
  // Penanda draggable untuk simpul yang SEDANG dipindah lokasinya (mode relokasi);
  // dibuat/dibuang oleh efek ber-dep `relocating`.
  const relocateMarker = useRef<maplibregl.Marker | null>(null)
  // Popup koordinat: klik lahan kosong → lat/long titik itu yang bisa disalin.
  const coordPopup = useRef<maplibregl.Popup | null>(null)
  // Penyebab per kabel (id → alarm hidup di hilir), diisi tiap overlay disegarkan
  // dan dibaca saat kabel diklik untuk menjelaskan "kenapa merah".
  const impactedCauses = useRef<Map<string, ImpactCause[]>>(new Map())
  const [selected, setSelected] = useState<OdpInspection | null>(null)
  const [cable, setCable] = useState<CableView | null>(null)
  const [cableCauses, setCableCauses] = useState<ImpactCause[]>([])
  // Hasil uji OTDR kabel yang panelnya terbuka; titik perkiraannya diplot di peta.
  // `null` = panel kabel tertutup / belum dimuat; `[]` = sudah dimuat, kosong.
  const [otdrTests, setOtdrTests] = useState<OtdrTest[] | null>(null)
  const [blast, setBlast] = useState<BlastRadiusView | null>(null)
  // Simulasi "kalau kabel ini putus" — panel + sorotan subpohon terputus.
  const [whatIf, setWhatIf] = useState<CableCutView | null>(null)
  const [trace, setTrace] = useState<CustomerTrace | null>(null)
  const [siteInsp, setSiteInsp] = useState<SiteInspection | null>(null)
  const [oltInsp, setOltInsp] = useState<OltView | null>(null)
  // Heatmap utilisasi port: menyala/mati lewat toggle, mewarnai ODP menurut pemakaian.
  const [heatmap, setHeatmap] = useState(false)
  const [editing, setEditing] = useState<CableView | null>(null)
  const [toolState, setToolState] = useState<ToolState | null>(null)
  // Mode taruh perangkat baru: jenis yang dipilih, dan lokasi klik yang menunggu form.
  const [placing, setPlacing] = useState<AssetKind | null>(null)
  const [placeAt, setPlaceAt] = useState<{ kind: AssetKind; lng: number; lat: number } | null>(null)
  // Simpul (perangkat/pelanggan) yang panel infonya sedang terbuka & bisa dipindah:
  // dari sini tombol "Pindahkan lokasi" tahu jenis, id, dan titik awalnya.
  const [movable, setMovable] = useState<{ layer: string; id: string; lng: number; lat: number } | null>(null)
  // Simpul yang SEDANG dalam mode relokasi (penanda draggable aktif). `null` = tak ada.
  const [relocating, setRelocating] = useState<
    { layer: string; id: string; label: string; color: string; lng: number; lat: number } | null
  >(null)
  const [error, setError] = useState<string | null>(null)
  const [basemap, setBasemap] = useState<BasemapMode>(DEFAULT_BASEMAP)
  const { can } = useCan()
  const { user } = useAuth()
  const toast = useToast()
  // Dipakai tombol "Buka detail" di panel OLT untuk pindah ke halaman lengkapnya.
  const navigate = useNavigate()

  // Label watermark: siapa yang sedang melihat peta ini. Dihitung sekali per user.
  const watermark = useMemo(() => {
    const label = [user?.name, user?.email].filter(Boolean).join(' · ') || 'NetOps Console'
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
      recolorImpactedNodes(overlay.nodes)
    } catch {
      /* overlay opsional — abaikan galat */
    }
  }

  /**
   * Mewarnai ulang marker perangkat yang terdampak alarm hidup: id UUID unik global,
   * jadi satu ekspresi `case` yang mencocokkan id fitur berlaku untuk tiap layer
   * titik (inti + halo). Simpul tak-terdampak jatuh ke warna dasar layernya — saat
   * alarm menutup, `crit`/`warn` kosong sehingga semua kembali normal.
   */
  const recolorImpactedNodes = (nodes: ImpactedOverlay['nodes']) => {
    const instance = map.current
    if (!instance) return
    const crit = nodes.filter((n) => n.severity === 'CRITICAL').map((n) => n.id)
    const warn = nodes.filter((n) => n.severity !== 'CRITICAL').map((n) => n.id)
    for (const { id, base } of NODE_LAYERS) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const expr: any = [
        'case',
        ['in', ['get', 'id'], ['literal', crit]],
        NODE_CRITICAL_COLOR,
        ['in', ['get', 'id'], ['literal', warn]],
        NODE_WARNING_COLOR,
        base,
      ]
      if (instance.getLayer(id)) instance.setPaintProperty(id, 'circle-color', expr)
      if (instance.getLayer(`${id}-glow`)) instance.setPaintProperty(`${id}-glow`, 'circle-color', expr)
    }
  }

  /**
   * Pusatkan peta ke lokasi pengguna lewat Geolocation API. Dipanggil sekali otomatis
   * saat peta siap (`announce=false`, diam bila ditolak → peta tetap di default Bekasi)
   * dan lewat tombol "Lokasi saya" (`announce=true`, toast sukses/gagal). Geolocation
   * memberi lat/lng; MapLibre memakai [lng, lat] sehingga urutannya dibalik.
   */
  const locateMe = useCallback(
    (announce: boolean) => {
      if (!navigator.geolocation) {
        if (announce) toast.error('Peramban tidak mendukung geolokasi')
        return
      }
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const { longitude, latitude } = pos.coords
          map.current?.flyTo({ center: [longitude, latitude], zoom: Math.max(map.current.getZoom(), 15) })
          if (announce) toast.success('Peta dipusatkan ke lokasi Anda')
        },
        () => {
          if (announce) toast.error('Tidak bisa mengakses lokasi — periksa izin peramban')
        },
        { enableHighAccuracy: true, timeout: 8000 },
      )
    },
    [toast],
  )

  // Pindah basemap saat pengguna memilih mode lain: cukup tukar tile & opacity sumber
  // raster 'basemap'; layer overlay (aset & kabel) tetap. Mode awal (gelap) sudah jadi
  // default pada gaya, jadi saat mount efek ini no-op (map belum ada → early return).
  // Gerbang pakai keberadaan sumber 'basemap' — BUKAN isStyleLoaded() — karena tepat
  // setelah setTiles gaya sempat "belum termuat" sementara ubin menyusul; memakai
  // once('load') pada kondisi itu akan menggantung selamanya (event 'load' hanya sekali).
  useEffect(() => {
    const m = map.current
    if (!m) return
    const preset = BASEMAPS[basemap]
    const apply = () => {
      const src = m.getSource('basemap') as maplibregl.RasterTileSource | undefined
      if (!src) return
      src.setTiles(preset.tiles)
      m.setPaintProperty('basemap', 'raster-opacity', preset.opacity)
    }
    if (m.getSource('basemap')) apply()
    else m.once('load', apply)
  }, [basemap])

  useEffect(() => {
    if (!container.current || map.current) return

    const instance = new maplibregl.Map({
      container: container.current,
      center: INITIAL_CENTER,
      zoom: 14,
      // Klik-ganda dipakai untuk menampilkan koordinat titik (lihat handler 'dblclick'),
      // jadi zoom klik-ganda dimatikan; zoom tetap lewat scroll/pinch & kontrol +/−.
      doubleClickZoom: false,
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
    // Skala ikut di kanan-bawah; pojok kiri-bawah kini ditempati kartu info peta.
    instance.addControl(new maplibregl.ScaleControl(), 'bottom-right')

    // Menutup semua panel info sebelum membuka yang baru — hanya satu tampil.
    const clearPanels = () => {
      setSelected(null)
      setCable(null)
      setOtdrTests(null)
      setBlast(null)
      setWhatIf(null)
      setTrace(null)
      setSiteInsp(null)
      setOltInsp(null)
      setMovable(null)
    }

    /**
     * Koordinat titik yang diklik, diambil dari geometri fiturnya (bukan titik
     * kursor) supaya presisi. Dipakai menandai simpul mana yang panel infonya
     * terbuka sehingga tombol "Pindahkan lokasi" tahu titik awalnya.
     */
    const pointAt = (feature: maplibregl.MapGeoJSONFeature | undefined): { lng: number; lat: number } | null => {
      const g = feature?.geometry
      return g?.type === 'Point' ? { lng: g.coordinates[0], lat: g.coordinates[1] } : null
    }

    // Layer aset/kabel yang bisa diklik — dipakai untuk membedakan "klik lahan kosong"
    // dari "klik perangkat" pada handler klik umum di bawah.
    const INTERACTIVE_LAYERS = ['customer', 'odp', 'odc', 'olt', 'site', 'cable']

    // Popup koordinat di sebuah titik: teks "lat, lng" yang, saat diklik, menyalin ke
    // papan klip (helper lat/long yang sering dibutuhkan). Auto-anchor mengikuti
    // geser/zoom peta ala Google Maps; tombol X menutupnya. Pondasi untuk fitur
    // berbasis titik lain (mis. taruh perangkat / ukur jarak dari sini).
    const showCoordPopup = (lngLat: maplibregl.LngLat) => {
      const text = `${lngLat.lat.toFixed(6)}, ${lngLat.lng.toFixed(6)}`
      const val = document.createElement('button')
      val.type = 'button'
      val.textContent = text
      val.title = 'Klik untuk menyalin'
      val.style.cssText =
        'font:inherit;font-weight:600;font-variant-numeric:tabular-nums;color:var(--accent);background:none;border:0;padding:0;cursor:pointer'
      val.onclick = () => void navigator.clipboard?.writeText(text).then(() => toast.success('Koordinat disalin'))
      const hint = document.createElement('div')
      hint.textContent = 'klik angka untuk salin'
      hint.style.cssText = 'margin-top:2px;font-size:0.72rem;color:#605e5c'
      const node = document.createElement('div')
      node.append(val, hint)
      if (!coordPopup.current) {
        coordPopup.current = new maplibregl.Popup({ closeButton: true, closeOnClick: false, className: 'coord-popup' })
      }
      coordPopup.current.setLngLat(lngLat).setDOMContent(node).addTo(instance)
    }

    // Klik TUNGGAL: (1) saat menaruh perangkat, titik itu jadi lokasinya lalu form
    // muncul; (2) saat idle, cukup menutup popup koordinat bila terbuka — tak ada aksi
    // lain di lahan kosong (klik aset/kabel tetap dilayani handler layer di bawah).
    instance.on('click', (event) => {
      if (modeRef.current === 'place') {
        const kind = placeKindRef.current
        if (!kind) return
        modeRef.current = 'idle'
        placeKindRef.current = null
        setPlacing(null)
        instance.getCanvas().style.cursor = ''
        setPlaceAt({ kind, lng: event.lngLat.lng, lat: event.lngLat.lat })
        return
      }
      if (modeRef.current !== 'idle') return
      coordPopup.current?.remove()
    })

    // Klik GANDA di lahan kosong (bukan aset/kabel) → tampilkan koordinat titik itu.
    instance.on('dblclick', (event) => {
      if (modeRef.current !== 'idle') return
      const layers = INTERACTIVE_LAYERS.filter((l) => instance.getLayer(l))
      if (layers.length > 0 && instance.queryRenderedFeatures(event.point, { layers }).length > 0) return
      showCoordPopup(event.lngLat)
    })

    instance.on('click', 'odp', (event) => {
      // Selagi menggambar/mengedit kabel atau menaruh perangkat, klik dikuasai alat itu.
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<OdpInspection>(`/api/gis/odps/${id}`)
        .then((odp) => {
          clearPanels()
          setSelected(odp)
          if (at) setMovable({ layer: 'odp', id, ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODP'))
    })

    // Klik pelanggan (mode idle) → telusur jalur ONU → ODP → ODC → OLT.
    instance.on('click', 'customer', (event) => {
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<CustomerTrace>(`/api/gis/trace/customers/${id}`)
        .then((t) => {
          clearPanels()
          setTrace(t)
          if (at) setMovable({ layer: 'customer', id, ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat telusur pelanggan'))
    })

    // Klik site/POP (mode idle) → isi site: OLT + rekap perangkat & pelanggan hilir.
    instance.on('click', 'site', (event) => {
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<SiteInspection>(`/api/gis/sites/${id}`)
        .then((s) => {
          clearPanels()
          setSiteInsp(s)
          if (at) setMovable({ layer: 'site', id, ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail site'))
    })

    // Klik OLT (mode idle) → panel ringkas perangkat (vendor/model/IP + kesiapan
    // SNMP), seragam dengan ODC/ODP/site. Panelnya menyediakan tombol "Buka detail"
    // untuk masuk ke halaman lengkap (edit lokasi/identitas/SNMP & PON port).
    instance.on('click', 'olt', (event) => {
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<OltView>(`/api/olts/${id}`)
        .then((o) => {
          clearPanels()
          setOltInsp(o)
          if (at) setMovable({ layer: 'olt', id, ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail OLT'))
    })

    // Klik ODC (mode idle) → blast radius: siapa saja di hilirnya.
    instance.on('click', 'odc', (event) => {
      if (modeRef.current !== 'idle') return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<BlastRadiusView>(`/api/gis/odcs/${id}/blast-radius`)
        .then((b) => {
          clearPanels()
          setBlast(b)
          if (at) setMovable({ layer: 'odc', id, ...at })
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

    for (const layer of ['odp', 'odc', 'olt', 'cable', 'customer', 'site']) {
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
        // Alat kabel aktif → tutup popup koordinat agar tak menutupi gambar kabel.
        if (state.mode !== 'idle') coordPopup.current?.remove()
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

      // Overlay heatmap utilisasi: halo lingkaran besar berwarna gradasi di bawah
      // marker aset, jadi titik ODP tetap terlihat di atasnya. Diisi imperatif dari
      // state `heatmap` (lihat efek sinkron di bawah) — kosong selama toggle mati.
      instance.addSource('heat', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      instance.addLayer(
        {
          id: 'heat-glow',
          type: 'circle',
          source: 'heat',
          paint: {
            'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 8, 16, 24],
            'circle-color': HEATMAP_COLOR,
            'circle-blur': 1,
            'circle-opacity': 0.55,
          },
        },
        'customer-glow',
      )

      // Titik perkiraan uji OTDR: halo magenta + inti bercincin putih, sengaja di
      // ATAS marker aset (tanpa beforeId) agar penanda diagnostik tak tertutup.
      // Diisi imperatif dari state `otdrTests` (lihat efek sinkron di bawah).
      instance.addSource('otdr', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      instance.addLayer({
        id: 'otdr-glow',
        type: 'circle',
        source: 'otdr',
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 10, 17, 26],
          'circle-color': OTDR_COLOR,
          'circle-blur': 1,
          'circle-opacity': 0.4,
        },
      })
      instance.addLayer({
        id: 'otdr-dot',
        type: 'circle',
        source: 'otdr',
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 5, 17, 9],
          'circle-color': OTDR_COLOR,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 2,
          'circle-opacity': 0.95,
        },
      })

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

    // Saat peta pertama dibuka, coba pusatkan ke lokasi pengguna (diam bila ditolak —
    // peta tetap di default). Operator bisa memusatkan ulang lewat tombol "Lokasi saya".
    locateMe(false)

    // Sidebar bisa diciutkan/dilebarkan, jadi lebar kanvas berubah tanpa resize
    // jendela — MapLibre perlu diberi tahu agar peta mengisi ulang penuh.
    const ro = new ResizeObserver(() => instance.resize())
    if (container.current) ro.observe(container.current)

    return () => {
      if (animRef.current) window.clearInterval(animRef.current)
      if (impactedRef.current) window.clearInterval(impactedRef.current)
      ro.disconnect()
      tool.current?.destroy()
      tool.current = null
      coordPopup.current?.remove()
      coordPopup.current = null
      instance.remove()
      map.current = null
    }
    // Init peta sekali saat mount; `locateMe` stabil (useCallback) & sengaja tak jadi dep
    // agar peta tak dibangun ulang.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // Pin lokasi perangkat baru: muncul begitu titik dipilih (klik peta), bisa
  // diseret untuk menyetel posisi, dan koordinatnya balik ke `placeAt` sehingga
  // form ikut memperbarui. Dibuang saat mode taruh selesai/batal.
  useEffect(() => {
    const instance = map.current
    if (!instance) return
    if (!placeAt) {
      placeMarker.current?.remove()
      placeMarker.current = null
      return
    }
    if (!placeMarker.current) {
      const marker = new maplibregl.Marker({ draggable: true, color: '#5b8cff' })
        .setLngLat([placeAt.lng, placeAt.lat])
        .addTo(instance)
      marker.on('dragend', () => {
        const p = marker.getLngLat()
        setPlaceAt((cur) => (cur ? { ...cur, lng: p.lng, lat: p.lat } : cur))
      })
      placeMarker.current = marker
    } else {
      placeMarker.current.setLngLat([placeAt.lng, placeAt.lat])
    }
  }, [placeAt])

  // Penanda relokasi: pin draggable KHAS (cincin berdenyut, warna sesuai jenis
  // simpul) yang menandai titik yang sedang dipindah — sengaja beda dari titik
  // biasa agar jelas "yang ini yang lagi diedit". Posisi kerjanya hidup di penanda
  // itu sendiri (dibaca saat "Simpan"), jadi menyeretnya tak memicu render ulang.
  useEffect(() => {
    const instance = map.current
    if (!instance || !relocating) return
    const el = document.createElement('div')
    el.className = 'relocate-pin'
    el.style.setProperty('--pin-color', relocating.color)
    const marker = new maplibregl.Marker({ element: el, draggable: true, anchor: 'center' })
      .setLngLat([relocating.lng, relocating.lat])
      .addTo(instance)
    relocateMarker.current = marker
    return () => {
      marker.remove()
      relocateMarker.current = null
    }
    // Hanya id yang menentukan ulang-pasang; lng/lat awal & warna tetap selama sesi relokasi.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [relocating?.id])

  // Heatmap utilisasi: saat toggle menyala, ambil pemakaian port tiap ODP dan
  // warnai titiknya; saat mati, kosongkan sumbernya. Fetch dibatalkan bila toggle
  // berubah sebelum respons tiba agar tidak menimpa data dengan hasil basi.
  useEffect(() => {
    const src = map.current?.getSource('heat') as GeoJSONSource | undefined
    if (!src) return
    if (!heatmap) {
      src.setData({ type: 'FeatureCollection', features: [] })
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const view = await api.get<UtilizationHeatmap>('/api/gis/odp-utilization')
        if (cancelled) return
        const live = map.current?.getSource('heat') as GeoJSONSource | undefined
        live?.setData({
          type: 'FeatureCollection',
          features: view.odps.map((o) => ({
            type: 'Feature',
            properties: { util: o.utilizationPercent },
            geometry: { type: 'Point', coordinates: [o.location.longitude, o.location.latitude] },
          })),
        })
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Gagal memuat heatmap utilisasi')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [heatmap])

  // Saat panel kabel terbuka, muat riwayat uji OTDR-nya (bila punya izin lihat).
  // Fetch dibatalkan bila kabel berganti sebelum respons tiba agar tak basi.
  useEffect(() => {
    if (!cable || !can('network.otdr.view')) {
      setOtdrTests(null)
      return
    }
    let cancelled = false
    const cableId = cable.id
    void (async () => {
      try {
        const list = await api.get<OtdrTest[]>(`/api/cables/${cableId}/otdr`)
        if (!cancelled) setOtdrTests(list)
      } catch {
        // Riwayat OTDR opsional — panel kabel tetap tampil tanpa daftar uji.
        if (!cancelled) setOtdrTests([])
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cable?.id])

  // Plot titik perkiraan tiap uji OTDR ke peta; kosongkan saat panel kabel tutup.
  useEffect(() => {
    const src = map.current?.getSource('otdr') as GeoJSONSource | undefined
    if (!src) return
    src.setData({
      type: 'FeatureCollection',
      features: (otdrTests ?? [])
        .filter((t) => t.estimatedPoint)
        .map((t) => ({
          type: 'Feature',
          properties: { id: t.id, eventType: t.eventType },
          geometry: {
            type: 'Point',
            coordinates: [t.estimatedPoint!.longitude, t.estimatedPoint!.latitude],
          },
        })),
    })
  }, [otdrTests])

  /** Mencatat satu uji OTDR, menyisipkannya ke daftar (terbaru dulu), lalu terbang ke titiknya. */
  const recordOtdr = async (cableId: string, form: RecordOtdrTest) => {
    try {
      const test = await api.post<OtdrTest>(`/api/cables/${cableId}/otdr`, form)
      setOtdrTests((prev) => [test, ...(prev ?? [])])
      if (test.estimatedPoint) {
        map.current?.flyTo({
          center: [test.estimatedPoint.longitude, test.estimatedPoint.latitude],
          zoom: Math.max(map.current.getZoom(), 16),
        })
      }
      toast.success(
        test.beyondCable
          ? 'Uji OTDR dicatat — jarak melampaui panjang kabel, titik dijepit ke ujung'
          : 'Uji OTDR dicatat',
      )
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencatat uji OTDR')
    }
  }

  const deleteOtdr = async (cableId: string, testId: string) => {
    try {
      await api.del(`/api/cables/${cableId}/otdr/${testId}`)
      setOtdrTests((prev) => (prev ?? []).filter((t) => t.id !== testId))
      toast.success('Uji OTDR dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus uji OTDR')
    }
  }

  /** Terbang ke titik perkiraan sebuah uji (dipakai saat menekan barisnya di panel). */
  const focusOtdr = (test: OtdrTest) => {
    if (!test.estimatedPoint) return
    map.current?.flyTo({
      center: [test.estimatedPoint.longitude, test.estimatedPoint.latitude],
      zoom: Math.max(map.current.getZoom(), 16),
    })
  }

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
    coordPopup.current?.remove()
    setSelected(null)
    setCable(null)
    setBlast(null)
    setTrace(null)
    setSiteInsp(null)
    setOltInsp(null)
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

  /**
   * Menyimpan lokasi baru simpul yang baru saja diseret. Server yang menempelkan
   * ulang ujung kabel terkait ke titik ini (ujung nempel, tikungan tetap), lalu tile
   * & overlay disegarkan agar simpul + kabelnya tergambar di posisi baru. Gagal →
   * toast + tetap segarkan tile supaya titik kembali ke posisi lama (bukan ilusi
   * tersimpan).
   */
  const relocateNode = async (layer: string, id: string, lng: number, lat: number) => {
    const cfg = MOVABLE_NODES[layer]
    if (!cfg) return
    try {
      await api.put(`/api/${cfg.plural}/${id}/location`, { longitude: lng, latitude: lat })
      toast.success(`Lokasi ${cfg.label} dipindahkan`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : `Gagal memindahkan ${cfg.label}`)
    } finally {
      refreshTiles()
      void refreshImpacted()
    }
  }

  /**
   * Menyembunyikan titik MVT sebuah simpul (agar tak dobel dengan penanda draggable
   * saat direlokasi), atau memulihkannya. `null` = tampilkan semua lagi.
   */
  const hideNodeDot = (layer: string, id: string | null) => {
    const instance = map.current
    if (!instance) return
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const filter: any = id ? ['!=', ['get', 'id'], id] : null
    for (const l of [layer, `${layer}-glow`]) {
      if (instance.getLayer(l)) instance.setFilter(l, filter)
    }
  }

  /**
   * Masuk mode relokasi untuk simpul yang panelnya terbuka: tutup panel, kunci mode
   * (klik peta lain & alat lain digerbang), sembunyikan titik aslinya, lalu munculkan
   * penanda draggable khas di titiknya (lihat efek `relocating`). Belum menyimpan apa
   * pun — operator menyeret penanda lalu menekan "Simpan".
   */
  const startRelocate = () => {
    const target = movable
    if (!target) return
    const cfg = MOVABLE_NODES[target.layer]
    if (!cfg) return
    coordPopup.current?.remove()
    setSelected(null)
    setBlast(null)
    setTrace(null)
    setSiteInsp(null)
    setOltInsp(null)
    setMovable(null)
    modeRef.current = 'drag'
    hideNodeDot(target.layer, target.id)
    setRelocating({ layer: target.layer, id: target.id, label: cfg.label, color: cfg.color, lng: target.lng, lat: target.lat })
  }

  /** Membereskan mode relokasi: pulihkan titik asli, buka gerbang mode, buang penanda. */
  const finishRelocate = () => {
    if (relocating) hideNodeDot(relocating.layer, null)
    modeRef.current = 'idle'
    setRelocating(null)
  }

  /** Simpan posisi penanda saat ini sebagai lokasi baru simpul, lalu keluar mode. */
  const saveRelocate = async () => {
    const r = relocating
    const marker = relocateMarker.current
    if (!r || !marker) return
    const { lng, lat } = marker.getLngLat()
    await relocateNode(r.layer, r.id, lng, lat)
    finishRelocate()
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

  const saveNewCable = async (form: {
    name: string
    coreCount: number
    // Feeder: PON port OLT sumber. Distribusi/drop: kaki/slot sumber.
    fromPonPortId?: string
    fromPortNumber?: number
    // Drop → pelanggan: ONU yang ditautkan ke slot ODP sumber (form.fromPortNumber).
    onuId?: string
  }) => {
    const route = tool.current?.route() ?? []
    const state = toolState
    if (!state?.from || !state?.to || !state.cableType) return
    const odpId = state.from.id
    try {
      await api.post('/api/cables', {
        name: form.name,
        cableType: state.cableType,
        coreCount: form.coreCount,
        route: route.map(([longitude, latitude]) => ({ longitude, latitude })),
        fromKind: state.from.kind,
        fromId: state.from.id,
        toKind: state.to.kind,
        toId: state.to.id,
        fromPonPortId: form.fromPonPortId,
        fromPortNumber: form.fromPortNumber,
        status: 'ACTIVE',
      })
      // Drop ke pelanggan: tautkan ONU-nya ke slot ODP yang sama, sehingga
      // "port mana" tercatat di penempatan ONU — sumber kebenaran port ODP.
      let portNote = ''
      if (form.onuId && form.fromPortNumber != null) {
        try {
          await api.post(`/api/customers/onus/${form.onuId}/attach`, {
            odpId,
            portNumber: form.fromPortNumber,
          })
          portNote = ` · ONU di port ${form.fromPortNumber}`
        } catch (attachErr) {
          toast.error(
            attachErr instanceof ApiError ? attachErr.message : 'Kabel tersimpan, tapi gagal menautkan ONU ke port',
          )
        }
      }
      toast.success(`Kabel tersimpan (${Math.round(state.lengthMeters)} m)${portNote}`)
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
        // Edit hanya mengubah geometri jalur — pertahankan port terekam agar tak
        // ter-null-kan (yang berarti melepas uplink) hanya karena rute digeser.
        fromPonPortId: editing.fromPonPortId ?? undefined,
        fromPortNumber: editing.fromPortNumber ?? undefined,
        toPortNumber: editing.toPortNumber ?? undefined,
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
    <div className="map-page">
      {error && <p className="error map-error">{error}</p>}
      <div className="map-shell">
        {/* Kanvas dibungkus agar watermark akuntabilitas hanya menutup peta,
            bukan panel di sampingnya. */}
        <div className="map-canvas-wrap">
          <div ref={container} className="map-canvas" />
          <div className="map-watermark" aria-hidden="true" style={{ backgroundImage: watermark }} />
        </div>

        {/* Judul + toggle heatmap + legenda dikumpulkan di kartu mengambang pojok
            kiri-bawah, agar peta bisa mengisi penuh tanpa blok info mencuri tinggi. */}
        <div className="map-info">
          <div className="map-info-head">
            <h2>Peta Jaringan</h2>
            {can('network.odp.view') && (
              <Button
                size="small"
                variant={heatmap ? 'primary' : 'subtle'}
                style={{ marginLeft: 'auto' }}
                onClick={() => setHeatmap((v) => !v)}
                title="Warnai ODP menurut pemakaian port untuk perencanaan kapasitas"
              >
                Heatmap utilisasi
              </Button>
            )}
          </div>
          <BasemapSwitcher value={basemap} onChange={setBasemap} />
          {heatmap ? <HeatmapLegend /> : <Legend />}
        </div>

        {/* Toolbar kiri-atas: tarik kabel + taruh perangkat. Tampil saat idle —
            termasuk state awal sebelum alat pernah dipakai (toolState masih null). */}
        {(!toolState || toolState.mode === 'idle') && !placing && !placeAt && !relocating && (
          <MapToolbar can={can} onDraw={startDraw} onPlace={startPlace} onLocate={() => locateMe(true)} />
        )}

        {/* Bilah petunjuk saat menaruh perangkat baru */}
        {placing && (
          <div className="map-hint">
            <IconPlus size={16} />
            <span>Klik lokasi di peta untuk menaruh {ASSET_META[placing].label} baru</span>
            <Button variant="subtle" size="small" style={{ marginLeft: 'auto' }} onClick={cancelPlace}>
              Batal
            </Button>
          </div>
        )}

        {/* Bilah aksi saat memindah lokasi simpul (mode relokasi) */}
        {relocating && (
          <div className="map-hint">
            <IconCrosshair size={16} />
            <span>Seret penanda {relocating.label} ke lokasi baru · kabel yang menempel ikut menyesuaikan</span>
            <Button variant="primary" size="small" style={{ marginLeft: 'auto' }} onClick={() => void saveRelocate()}>
              Simpan
            </Button>
            <Button variant="subtle" size="small" onClick={finishRelocate}>
              Batal
            </Button>
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
              <Button variant="subtle" size="small" onClick={() => tool.current?.removeLastBend()}>
                Urungkan titik
              </Button>
            )}
            <Button variant="subtle" size="small" onClick={cancelTool}>
              Batal
            </Button>
          </div>
        )}

        {/* Bilah petunjuk saat mengedit jalur */}
        {editingMode && (
          <div className="map-hint">
            <IconRoute size={16} />
            <span>Seret titik tengah (yang samar) untuk membelokkan · seret titik untuk menggeser · klik-ganda untuk hapus</span>
            <span className="tnum" style={{ marginLeft: 'auto', fontWeight: 600 }}>
              {formatLength(toolState?.lengthMeters ?? 0)}
            </span>
            <Button variant="primary" size="small" onClick={() => void saveEdit()}>
              Simpan
            </Button>
            <Button variant="subtle" size="small" onClick={cancelTool}>
              Batal
            </Button>
          </div>
        )}

        {/* Panel simpan kabel baru (kedua ujung sudah ditentukan) */}
        {toolState?.complete && toolState.from && toolState.to && toolState.cableType && (
          <SaveCablePanel
            from={toolState.from.code}
            to={toolState.to.code}
            fromKind={toolState.from.kind}
            fromId={toolState.from.id}
            toId={toolState.to.id}
            cableType={toolState.cableType}
            lengthMeters={toolState.lengthMeters}
            canAssignPort={can('customer.onu.assign')}
            onCancel={cancelTool}
            onSave={saveNewCable}
          />
        )}

        {blast && (
          <BlastRadiusPanel
            blast={blast}
            canDelete={can('network.odc.delete')}
            canRelocate={can('network.odc.update')}
            onRelocate={startRelocate}
            onDelete={() => void deleteAsset('ODC', blast.odcId, blast.code, () => setBlast(null))}
            onClose={() => setBlast(null)}
          />
        )}
        {whatIf && <CableCutPanel cut={whatIf} onClose={() => setWhatIf(null)} />}
        {trace && (
          <CustomerTracePanel
            trace={trace}
            canRelocate={can('customer.customer.update')}
            onRelocate={startRelocate}
            onClose={() => setTrace(null)}
          />
        )}
        {siteInsp && (
          <SitePanel
            site={siteInsp}
            canDelete={can('network.site.delete')}
            canRelocate={can('network.site.update')}
            onRelocate={startRelocate}
            onDelete={() => void deleteAsset('SITE', siteInsp.siteId, siteInsp.code, () => setSiteInsp(null))}
            onClose={() => setSiteInsp(null)}
          />
        )}
        {oltInsp && (
          <OltPanel
            olt={oltInsp}
            canView={can('network.olt.view')}
            canRelocate={can('network.olt.update')}
            onRelocate={startRelocate}
            onOpenDetail={() => navigate(`/olts/${oltInsp.id}`, { state: { backTo: '/map', backLabel: 'Peta' } })}
            onClose={() => setOltInsp(null)}
          />
        )}
        {selected && (
          <OdpPanel
            inspection={selected}
            canDelete={can('network.odp.delete')}
            canRelocate={can('network.odp.update')}
            onRelocate={startRelocate}
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
            canViewOtdr={can('network.otdr.view')}
            canRecordOtdr={can('network.otdr.record')}
            otdrTests={otdrTests}
            onRecordOtdr={(form) => void recordOtdr(cable.id, form)}
            onDeleteOtdr={(testId) => void deleteOtdr(cable.id, testId)}
            onFocusOtdr={focusOtdr}
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
 * Toolbar kiri-atas peta: lokasi saya + tarik kabel + tombol taruh perangkat. Tombol
 * tulis (tarik kabel/taruh aset) hanya muncul bila pengguna punya izin terkait; tombol
 * "Lokasi saya" selalu tampil karena geolokasi bukan aksi tulis (semua peran boleh).
 */
/**
 * Pemilih basemap: segmen kecil di dalam kartu info (kiri-bawah), dikumpulkan bersama
 * toggle heatmap & legenda karena sama-sama mengatur "apa yang ditampilkan peta".
 * Sengaja jauh dari alat-edit (kiri-atas) & panel detail (kanan-atas) agar tak
 * bertabrakan. Pakai atom `Segmented` (Fluent) yang legibel di atas kartu kaca bertema.
 */
function BasemapSwitcher({ value, onChange }: { value: BasemapMode; onChange: (mode: BasemapMode) => void }) {
  return (
    <Segmented
      className="map-basemap"
      ariaLabel="Mode peta"
      value={value}
      onChange={onChange}
      options={BASEMAP_ORDER.map((mode) => ({ value: mode, label: BASEMAPS[mode].label }))}
    />
  )
}

function MapToolbar({
  can,
  onDraw,
  onPlace,
  onLocate,
}: {
  can: (perm: string) => boolean
  onDraw: () => void
  onPlace: (kind: AssetKind) => void
  onLocate: () => void
}) {
  const placeable = (Object.keys(ASSET_META) as AssetKind[]).filter((k) => can(ASSET_META[k].createPerm))
  return (
    <div className="map-toolbar">
      <Button variant="subtle" onClick={onLocate}>
        <IconCrosshair size={15} /> Lokasi saya
      </Button>
      {can('network.cable.create') && (
        <Button variant="primary" onClick={onDraw}>
          <IconRoute size={16} /> Tarik kabel
        </Button>
      )}
      {placeable.map((k) => (
        <Button key={k} variant="subtle" onClick={() => onPlace(k)}>
          <IconPlus size={15} /> {ASSET_META[k].label}
        </Button>
      ))}
    </div>
  )
}

/**
 * Tombol "Pindahkan lokasi" seragam di setiap panel info simpul. Menekannya masuk
 * mode relokasi: penanda draggable khas muncul di titik itu (lihat startRelocate).
 * Tersembunyi bila pengguna tak punya izin ubah jenis simpul terkait.
 */
function RelocateButton({ show, onClick }: { show: boolean; onClick: () => void }) {
  if (!show) return null
  return (
    <Button variant="subtle" onClick={onClick}>
      <IconCrosshair size={15} /> Pindahkan lokasi
    </Button>
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

/** Port keluaran sumber yang dipilih: PON port OLT (ponPortId) atau kaki/slot (portNumber). */
type SourcePort = { ponPortId: string | null; portNumber: number | null }

function SaveCablePanel({
  from,
  to,
  fromKind,
  fromId,
  toId,
  cableType,
  lengthMeters,
  canAssignPort,
  onCancel,
  onSave,
}: {
  from: string
  to: string
  /** Jenis perangkat ujung awal — menentukan bentuk port sumber (PON/kaki/slot). */
  fromKind: NodeKind
  /** Id perangkat ujung awal (untuk drop = ODP tempat port dipilih). */
  fromId: string
  /** Id perangkat ujung akhir (untuk drop = pelanggan yang ONU-nya ditautkan). */
  toId: string
  cableType: CableType
  lengthMeters: number
  canAssignPort: boolean
  onCancel: () => void
  onSave: (form: {
    name: string
    coreCount: number
    fromPonPortId?: string
    fromPortNumber?: number
    onuId?: string
  }) => void
}) {
  const [name, setName] = useState(`${TYPE_LABEL[cableType]} ${from} → ${to}`)
  const [coreCount, setCoreCount] = useState(DEFAULT_CORES[cableType])

  const isDrop = cableType === 'DROP'

  // Feeder/distribusi: pilih port KELUARAN sumber (PON port OLT / kaki ODC / slot
  // ODP). Feeder dari SITE tak punya PON port → daftar kosong, port tak diperlukan.
  const [srcOptions, setSrcOptions] = useState<CablePortOption[] | null>(isDrop ? [] : null)
  const [srcPort, setSrcPort] = useState<SourcePort | null>(null)

  useEffect(() => {
    if (isDrop) return
    let alive = true
    setSrcOptions(null)
    void api
      .get<CablePortOption[]>(`/api/cables/source-ports?kind=${fromKind}&id=${fromId}`)
      .then((opts) => {
        if (alive) setSrcOptions(opts)
      })
      .catch(() => {
        if (alive) setSrcOptions([])
      })
    return () => {
      alive = false
    }
  }, [isDrop, fromKind, fromId])

  // Untuk kabel drop, tampilkan peta port ODP tujuan supaya port tidak ditebak.
  const [odp, setOdp] = useState<OdpInspection | null>(null)
  const [onu, setOnu] = useState<OnuView | null>(null)
  const [loadingPorts, setLoadingPorts] = useState(isDrop)
  const [selectedPort, setSelectedPort] = useState<number | null>(null)

  useEffect(() => {
    if (!isDrop) return
    let alive = true
    setLoadingPorts(true)
    void (async () => {
      try {
        const [odpInsp, onus] = await Promise.all([
          api.get<OdpInspection>(`/api/gis/odps/${fromId}`),
          api
            .get<OnuView[]>(`/api/customers/${toId}/onus`)
            .catch(() => [] as OnuView[]),
        ])
        if (!alive) return
        setOdp(odpInsp)
        // ONU aktif pelanggan (yang belum dibongkar) — sasaran penautan port.
        const active = onus.find((o) => o.status !== 'DISMANTLED') ?? onus[0] ?? null
        setOnu(active)
        // Prasetel ke port ONU saat ini bila memang sudah di ODP ini.
        if (active?.odpId === odpInsp.odpId && active.odpPortNumber != null) {
          setSelectedPort(active.odpPortNumber)
        }
      } finally {
        if (alive) setLoadingPorts(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [isDrop, fromId, toId])

  // Kesiapan simpan: feeder/distribusi WAJIB port sumber terpilih. Satu-satunya
  // pengecualian "daftar kosong boleh" adalah feeder dari SITE (POP tak lewat PON
  // port). OLT tanpa PON port juga berdaftar kosong, TAPI di situ port tetap wajib —
  // menyimpan tanpa port berarti uplink diam-diam tak ter-set (feeder "yatim").
  const siteFeeder = fromKind === 'SITE'
  const sourceReady = isDrop
    ? true
    : srcOptions != null && (srcPort != null || (siteFeeder && srcOptions.length === 0))
  const dropReady = !isDrop || onu != null
  const canSave = name.trim() !== '' && sourceReady && dropReady

  const submit = () =>
    onSave({
      name,
      coreCount,
      fromPonPortId: srcPort?.ponPortId ?? undefined,
      fromPortNumber: isDrop ? selectedPort ?? undefined : srcPort?.portNumber ?? undefined,
      onuId: isDrop && onu && selectedPort != null ? onu.id : undefined,
    })

  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>Kabel baru</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onCancel} aria-label="Batal" />
      </div>
      <div className="row" style={{ gap: '0.5rem' }}>
        <StatusBadge status="ACTIVE" label={TYPE_LABEL[cableType]} />
        <span className="badge accent">
          {from} → {to}
        </span>
        <span className="badge tnum">{formatLength(lengthMeters)}</span>
      </div>
      <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} />
      <TextField
        label="Jumlah core"
        type="number"
        min={1}
        max={288}
        value={String(coreCount)}
        onChange={(_, data) => setCoreCount(Number(data.value))}
      />

      {!isDrop && (
        <div className="stack" style={{ gap: '0.4rem' }}>
          <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Port sumber {from}</span>
          {srcOptions == null ? (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Memuat port…
            </span>
          ) : srcOptions.length === 0 ? (
            <span className="muted" style={{ fontSize: '0.78rem' }}>
              {fromKind === 'SITE'
                ? 'Feeder dari POP tak melalui PON port — langsung tersambung.'
                : fromKind === 'OLT'
                  ? 'OLT ini belum punya PON port. Tambahkan dulu di detail OLT (tab PON Port) sebelum menarik feeder.'
                  : 'Tak ada port keluaran di simpul ini — tak bisa menarik kabel dari sini.'}
            </span>
          ) : (
            <>
              <SourcePortGrid options={srcOptions} selected={srcPort} onPick={setSrcPort} />
              <span className="muted" style={{ fontSize: '0.78rem' }}>
                {srcPort == null
                  ? 'Pilih port keluaran dulu — kabel tak bisa ditarik tanpa port.'
                  : `Menarik kabel ini otomatis menyetel uplink ${to}.`}
              </span>
            </>
          )}
        </div>
      )}

      {isDrop && (
        <div className="stack" style={{ gap: '0.4rem' }}>
          <div className="spread">
            <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Port ODP {odp?.code ?? from}</span>
            {odp && (
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                {odp.usedPorts}/{odp.capacity} terpakai
              </span>
            )}
          </div>
          {loadingPorts ? (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Memuat port…
            </span>
          ) : odp ? (
            <>
              <PortGrid
                inspection={odp}
                selected={selectedPort}
                ownPort={onu?.odpId === odp.odpId ? onu?.odpPortNumber ?? null : null}
                onPick={canAssignPort && onu ? setSelectedPort : undefined}
              />
              {!onu ? (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Pelanggan belum punya ONU terpasang — kabel drop tak bisa ditarik ke sini.
                </span>
              ) : !canAssignPort ? (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Butuh izin <span className="tnum">customer.onu.assign</span> untuk menautkan port.
                </span>
              ) : selectedPort == null ? (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Pilih slot kosong untuk menautkan ONU pelanggan.
                </span>
              ) : (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  ONU {onu.serialNumber} → slot {selectedPort}
                </span>
              )}
            </>
          ) : (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Gagal memuat port ODP.
            </span>
          )}
        </div>
      )}

      <div className="row">
        <Button variant="primary" disabled={!canSave} onClick={submit}>
          Simpan kabel
        </Button>
        <Button variant="subtle" onClick={onCancel}>
          Batal
        </Button>
      </div>
    </aside>
  )
}

/**
 * Peta port KELUARAN sumber (feeder/distribusi): PON port OLT, kaki splitter ODC,
 * atau slot ODP. Port yang sudah dipakai kabel lain tampil nonaktif dengan kode
 * kabel penghuninya — menjawab "colok dari port mana" tanpa menabrak yang terisi.
 */
function SourcePortGrid({
  options,
  selected,
  onPick,
}: {
  options: CablePortOption[]
  selected: SourcePort | null
  onPick: (port: SourcePort) => void
}) {
  const isSame = (o: CablePortOption) =>
    selected != null &&
    (o.ponPortId != null ? selected.ponPortId === o.ponPortId : selected.portNumber === o.portNumber)
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(56px, 1fr))', gap: '0.3rem' }}>
      {options.map((o) => {
        const key = o.ponPortId ?? `p${o.portNumber}`
        const isSelected = isSame(o)
        const selectable = !o.occupied
        const bg = isSelected ? 'var(--accent-soft)' : o.occupied ? 'var(--surface-2, rgba(148,163,184,0.15))' : 'transparent'
        const border = isSelected ? 'var(--accent)' : o.occupied ? 'var(--border)' : 'var(--good-ink)'
        return (
          // Tombol native (bukan Fluent Button) supaya inline-style dihormati apa adanya
          // dan tak terkena min-width 96px Fluent yang membuat sel grid meluber & tumpang tindih.
          <button
            key={key}
            type="button"
            disabled={!selectable}
            onClick={selectable ? () => onPick({ ponPortId: o.ponPortId, portNumber: o.portNumber }) : undefined}
            title={o.occupied ? `${o.label} · dipakai kabel ${o.occupiedByCable}` : `${o.label} · kosong`}
            style={{
              minWidth: 0,
              width: '100%',
              boxSizing: 'border-box',
              padding: '0.3rem 0.2rem',
              borderRadius: 6,
              border: `1px solid ${border}`,
              background: bg,
              color: o.occupied ? 'var(--muted)' : 'var(--text)',
              cursor: selectable ? 'pointer' : 'default',
              fontFamily: 'inherit',
              fontSize: '0.7rem',
              lineHeight: 1.2,
              textAlign: 'center',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

/**
 * Peta port sebuah ODP: satu kotak per port, hijau untuk kosong dan abu untuk
 * terpakai (dengan kode pelanggan penghuninya). Menjawab "port mana yang kosong"
 * secara visual, tanpa menebak. Kotak yang bisa dipilih menyala saat ditunjuk.
 */
function PortGrid({
  inspection,
  selected,
  ownPort,
  onPick,
}: {
  inspection: OdpInspection
  selected: number | null
  /** Port yang sudah dihuni ONU pelanggan ini — boleh dipilih ulang. */
  ownPort: number | null
  onPick?: (port: number) => void
}) {
  const free = new Set(inspection.availablePortNumbers)
  const occupantByPort = new Map(inspection.occupants.map((o) => [o.portNumber, o]))
  const ports = Array.from({ length: inspection.capacity }, (_, i) => i + 1)
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(38px, 1fr))', gap: '0.3rem' }}>
      {ports.map((n) => {
        const occ = occupantByPort.get(n)
        const isOwn = n === ownPort
        const selectable = onPick != null && (free.has(n) || isOwn)
        const isSelected = n === selected
        const bg = isSelected
          ? 'var(--accent-soft)'
          : isOwn
            ? 'var(--good-ink)'
            : occ
              ? 'var(--surface-2, rgba(148,163,184,0.15))'
              : 'transparent'
        const border = isSelected ? 'var(--accent)' : free.has(n) ? 'var(--good-ink)' : 'var(--border)'
        return (
          // Tombol native (bukan Fluent Button) supaya inline-style dihormati apa adanya
          // dan tak terkena min-width 96px Fluent yang membuat sel grid meluber & tumpang tindih.
          <button
            key={n}
            type="button"
            disabled={!selectable}
            onClick={selectable ? () => onPick?.(n) : undefined}
            title={occ ? `Port ${n} · ${occ.customerCode} ${occ.customerName}` : `Port ${n} · kosong`}
            style={{
              minWidth: 0,
              width: '100%',
              boxSizing: 'border-box',
              padding: '0.3rem 0',
              borderRadius: 6,
              border: `1px solid ${border}`,
              background: bg,
              color: occ && !isOwn ? 'var(--muted)' : 'var(--text)',
              cursor: selectable ? 'pointer' : 'default',
              fontFamily: 'inherit',
              fontSize: '0.72rem',
              lineHeight: 1.2,
              textAlign: 'center',
            }}
          >
            <div className="tnum" style={{ fontWeight: 600 }}>
              {n}
            </div>
            <div style={{ fontSize: '0.6rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {occ ? occ.customerCode : '·'}
            </div>
          </button>
        )
      })}
    </div>
  )
}

function CablePanel({
  cable,
  causes,
  canEdit,
  canDelete,
  canSimulate,
  canViewOtdr,
  canRecordOtdr,
  otdrTests,
  onRecordOtdr,
  onDeleteOtdr,
  onFocusOtdr,
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
  canViewOtdr: boolean
  canRecordOtdr: boolean
  otdrTests: OtdrTest[] | null
  onRecordOtdr: (form: RecordOtdrTest) => void
  onDeleteOtdr: (testId: string) => void
  onFocusOtdr: (test: OtdrTest) => void
  onEdit: () => void
  onDelete: () => void
  onSimulate: () => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{cable.code}</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
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
        {cable.fromKind}
        {cable.fromPortLabel ? ` · ${cable.fromPortLabel}` : ''} → {cable.toKind}
        {cable.toPortNumber != null ? ` · Port ${cable.toPortNumber}` : ''} · {cable.route.points.length} titik jalur
      </p>
      {causes.length > 0 && <CableCauses causes={causes} />}
      {canSimulate && (
        <Button variant="subtle" style={{ justifyContent: 'flex-start', color: WHATIF_COLOR }} onClick={onSimulate}>
          Simulasi putus — siapa yang kena?
        </Button>
      )}
      {canViewOtdr && (
        <OtdrSection
          cable={cable}
          tests={otdrTests}
          canRecord={canRecordOtdr}
          onRecord={onRecordOtdr}
          onDelete={onDeleteOtdr}
          onFocus={onFocusOtdr}
        />
      )}
      {(canEdit || canDelete) && (
        <div className="row">
          {canEdit && (
            <Button variant="primary" onClick={onEdit}>
              <IconRoute size={15} /> Edit jalur
            </Button>
          )}
          {canDelete && (
            <Button variant="danger" onClick={onDelete}>
              Hapus
            </Button>
          )}
        </div>
      )}
    </aside>
  )
}

/**
 * Bagian uji OTDR di dalam panel kabel: daftar hasil ukur (tiap baris terbang ke
 * titik perkiraannya di peta bila diklik) plus form catat jarak gangguan. Jarak
 * yang dimasukkan adalah panjang serat dari ujung ukur (hulu/hilir); server
 * memetakannya ke titik di jalur kabel — di sini cukup ditampilkan & diplot.
 */
function OtdrSection({
  cable,
  tests,
  canRecord,
  onRecord,
  onDelete,
  onFocus,
}: {
  cable: CableView
  tests: OtdrTest[] | null
  canRecord: boolean
  onRecord: (form: RecordOtdrTest) => void
  onDelete: (testId: string) => void
  onFocus: (test: OtdrTest) => void
}) {
  const [distance, setDistance] = useState('')
  const [measuredFrom, setMeasuredFrom] = useState<CableEnd>('FROM')
  const [eventType, setEventType] = useState<OtdrEventType>('BREAK')
  const [lossDb, setLossDb] = useState('')
  const [note, setNote] = useState('')

  const distanceNum = Number(distance)
  const canSubmit = distance.trim() !== '' && Number.isFinite(distanceNum) && distanceNum >= 0

  const submit = () => {
    if (!canSubmit) return
    const loss = Number(lossDb)
    onRecord({
      distanceMeters: distanceNum,
      measuredFrom,
      eventType,
      lossDb: lossDb.trim() !== '' && Number.isFinite(loss) ? loss : null,
      note: note.trim() || null,
    })
    setDistance('')
    setLossDb('')
    setNote('')
  }

  const list = tests ?? []

  return (
    <div className="stack" style={{ gap: '0.5rem', borderTop: '1px solid var(--line)', paddingTop: '0.6rem' }}>
      <div className="spread">
        <strong style={{ fontSize: '0.85rem' }}>Uji OTDR</strong>
        <span className="muted" style={{ fontSize: '0.75rem' }}>{list.length} hasil</span>
      </div>

      {list.length > 0 && (
        <div className="stack" style={{ gap: '0.25rem', maxHeight: 190, overflowY: 'auto' }}>
          {list.map((t) => (
            <div key={t.id} className="spread" style={{ gap: '0.4rem', alignItems: 'center' }}>
              <Button
                variant="subtle"
                style={{ justifyContent: 'flex-start', flex: 1, padding: '0.25rem 0.4rem', fontSize: '0.8rem' }}
                onClick={() => onFocus(t)}
                disabled={!t.estimatedPoint}
                title={t.estimatedPoint ? 'Fokuskan peta ke titik perkiraan' : 'Titik tak bisa dipetakan'}
              >
                <span className="tnum" style={{ fontWeight: 600 }}>{formatLength(t.distanceMeters)}</span>
                <span className="badge" style={{ marginLeft: '0.4rem' }}>{OTDR_EVENT_LABEL[t.eventType]}</span>
                <span className="muted" style={{ marginLeft: '0.4rem' }}>
                  dari {t.measuredFrom === 'FROM' ? cable.fromKind : cable.toKind}
                </span>
                {t.beyondCable && (
                  <span className="badge" style={{ marginLeft: '0.4rem', color: WHATIF_COLOR, borderColor: WHATIF_COLOR }}>
                    di luar
                  </span>
                )}
              </Button>
              {canRecord && (
                <Button variant="subtle" icon={<IconClose size={14} />} onClick={() => onDelete(t.id)} aria-label="Hapus uji" />
              )}
            </div>
          ))}
        </div>
      )}

      {tests === null && <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>Memuat…</p>}
      {tests !== null && list.length === 0 && !canRecord && (
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>Belum ada uji OTDR.</p>
      )}

      {canRecord && (
        <div className="stack" style={{ gap: '0.4rem' }}>
          <div className="row" style={{ gap: '0.4rem' }}>
            <TextField
              label="Jarak serat (m)"
              type="number"
              min={0}
              step="0.1"
              value={distance}
              onChange={(_, data) => setDistance(data.value)}
              placeholder="mis. 320"
              style={{ flex: 1 }}
            />
            <SelectField
              label="Diukur dari"
              value={measuredFrom}
              onChange={(_, data) => setMeasuredFrom(data.value as CableEnd)}
              style={{ width: '8.5rem' }}
            >
              <option value="FROM">Hulu ({cable.fromKind})</option>
              <option value="TO">Hilir ({cable.toKind})</option>
            </SelectField>
          </div>
          <div className="row" style={{ gap: '0.4rem' }}>
            <SelectField
              label="Peristiwa"
              value={eventType}
              onChange={(_, data) => setEventType(data.value as OtdrEventType)}
              style={{ flex: 1 }}
            >
              {OTDR_EVENT_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </SelectField>
            <TextField
              label="Redaman (dB)"
              type="number"
              min={0}
              step="0.1"
              value={lossDb}
              onChange={(_, data) => setLossDb(data.value)}
              placeholder="opsional"
              style={{ width: '8.5rem' }}
            />
          </div>
          <TextField
            label="Catatan (opsional)"
            value={note}
            onChange={(_, data) => setNote(data.value)}
            placeholder="mis. dekat tiang 12"
          />
          <Button variant="primary" disabled={!canSubmit} onClick={submit}>
            Plot &amp; simpan
          </Button>
        </div>
      )}
    </div>
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
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
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
  PPPOE_DOWN: 'PPPoE putus',
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
  canRelocate,
  onRelocate,
  onDelete,
  onClose,
}: {
  blast: BlastRadiusView
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const withPhone = blast.customers.filter((c) => c.phone).length
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{blast.code}</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
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
      {(canRelocate || canDelete) && (
        <div className="row wrap" style={{ gap: '0.4rem' }}>
          <RelocateButton show={canRelocate} onClick={onRelocate} />
          {canDelete && (
            <Button variant="danger" onClick={onDelete}>
              Hapus ODC
            </Button>
          )}
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
  CUSTOMER: 'ONT / Pelanggan',
  ONU: 'ONU',
  ODP: 'ODP (FAT)',
  ODC: 'ODC (FDT)',
  OLT: 'OLT',
  PON: 'PON',
  PON_PORT: 'PON',
  SITE: 'Site/POP',
  BRAS: 'BRAS',
}

/**
 * Panel telusur pelanggan: jalur fisik dari rumah pelanggan menaiki topologi
 * sampai OLT, dengan status optik dan perkiraan anggaran redaman — menjawab
 * "kenapa pelanggan ini bermasalah dan lewat mana kabelnya".
 */
function CustomerTracePanel({
  trace,
  canRelocate,
  onRelocate,
  onClose,
}: {
  trace: CustomerTrace
  canRelocate: boolean
  onRelocate: () => void
  onClose: () => void
}) {
  const up = trace.upstream
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{trace.customerName}</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {trace.customerCode}
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        {trace.onuStatus && <StatusBadge status={trace.onuStatus} />}
        {trace.onuSerialNumber && <span className="badge">{trace.onuSerialNumber}</span>}
        {trace.odpPortNumber != null && <span className="badge">port {trace.odpPortNumber}</span>}
      </div>

      {(trace.installRxPowerDbm != null ||
        trace.opticalHealth ||
        trace.liveRxPowerDbm != null ||
        trace.estimatedLossDb != null) && (
        <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'center' }}>
          {trace.installRxPowerDbm != null && (
            <span style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'], fontWeight: 600 }}>
              {trace.installRxPowerDbm} dBm
            </span>
          )}
          {trace.opticalHealth && trace.installRxPowerDbm == null && (
            <span style={{ color: HEALTH_COLOR[trace.opticalHealth], fontWeight: 600 }}>{trace.opticalHealth}</span>
          )}
          {trace.liveRxPowerDbm != null && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Rx hidup <span className="tnum">{trace.liveRxPowerDbm.toFixed(1)} dBm</span>
              {trace.distanceMeters != null && ` · ${trace.distanceMeters} m`}
            </span>
          )}
          {trace.estimatedLossDb != null && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              perkiraan rugi total {trace.estimatedLossDb.toFixed(1)} dB
            </span>
          )}
        </div>
      )}

      {trace.bras && (
        <div>
          <strong>BRAS / sesi PPPoE</strong>
          <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem', lineHeight: 1.6 }}>
            <span style={{ color: trace.bras.online ? 'var(--good-ink)' : 'var(--critical-ink)', fontWeight: 600 }}>
              {trace.bras.online ? 'Online' : 'Offline'}
            </span>{' '}
            · {trace.bras.username}
            {trace.bras.framedIp && <> · IP <span className="tnum">{trace.bras.framedIp}</span></>}
            {trace.bras.nasName && ` · ${trace.bras.nasName}`}
            {trace.bras.rateProfileName && ` · ${trace.bras.rateProfileName}`}
          </p>
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
            {trace.hops.map((hop: TraceHop, i: number) => {
              const hopColor =
                hop.online == null ? undefined : hop.online ? 'var(--good-ink)' : 'var(--critical-ink)'
              return (
                <li key={`${hop.kind}-${hop.code}-${i}`}>
                  <span className="tl-dot" aria-hidden="true" style={hopColor ? { background: hopColor } : undefined} />
                  <div className="stack" style={{ gap: '0.1rem' }}>
                    <strong style={{ fontSize: '0.85rem', color: hopColor }}>
                      {HOP_LABEL[hop.kind] ?? hop.kind} {hop.code}
                    </strong>
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {hop.name}
                    </span>
                    {hop.detail && (
                      <span className="muted tnum" style={{ fontSize: '0.78rem' }}>
                        {hop.detail}
                      </span>
                    )}
                  </div>
                </li>
              )
            })}
          </ol>
        </div>
      )}
      {canRelocate && (
        <div className="row">
          <RelocateButton show={canRelocate} onClick={onRelocate} />
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
  canRelocate,
  onRelocate,
  onDelete,
  onClose,
}: {
  site: SiteInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  onDelete: () => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{site.code}</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
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
      {(canRelocate || canDelete) && (
        <div className="row wrap" style={{ gap: '0.4rem' }}>
          <RelocateButton show={canRelocate} onClick={onRelocate} />
          {canDelete && (
            <Button variant="danger" onClick={onDelete} disabled={site.oltCount > 0}>
              Hapus site
            </Button>
          )}
          {canDelete && site.oltCount > 0 && (
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

/**
 * Panel sebuah OLT saat markernya diklik: identitas perangkat (vendor/model/IP),
 * status, kesiapan SNMP, dan jumlah port PON — seragam dengan panel ODC/ODP/site.
 * Sengaja tanpa tombol hapus: OLT adalah perangkat inti dengan banyak hilir, jadi
 * penghapusan hanya dari halaman detail yang lebih sengaja lewat "Buka detail"
 * (di sana pun server menolak selama masih ada ODC menggantung).
 */
function OltPanel({
  olt,
  canView,
  canRelocate,
  onRelocate,
  onOpenDetail,
  onClose,
}: {
  olt: OltView
  canView: boolean
  canRelocate: boolean
  onRelocate: () => void
  onOpenDetail: () => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{olt.code}</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {olt.name}
      </p>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <StatusBadge status={olt.status} />
        <span className="badge">{olt.vendor}</span>
        {olt.model && <span className="badge">{olt.model}</span>}
        <span className="badge">{olt.ponPortCount} port PON</span>
      </div>
      {olt.siteName && (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Site {olt.siteName}
        </p>
      )}
      <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'center' }}>
        {olt.managementIp && (
          <span className="muted tnum" style={{ fontSize: '0.82rem' }}>
            IP {olt.managementIp}
          </span>
        )}
        <span
          className="badge"
          style={
            olt.pollable
              ? { color: 'var(--good-ink)', borderColor: 'var(--good-ink)' }
              : { color: 'var(--muted)' }
          }
        >
          {olt.pollable ? `SNMP siap · port ${olt.snmpPort}` : 'SNMP belum diset'}
        </span>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Perangkat inti: kalau OLT ini modar, seluruh jalur di hilirnya ikut mati.
      </p>
      {(canView || canRelocate) && (
        <div className="row wrap" style={{ gap: '0.4rem' }}>
          {canView && (
            <Button variant="primary" onClick={onOpenDetail}>
              Buka detail
            </Button>
          )}
          <RelocateButton show={canRelocate} onClick={onRelocate} />
        </div>
      )}
    </aside>
  )
}

/** Rasio splitter yang lazim dipakai — cukup untuk sebagian besar pemasangan. */
const SPLITTER_RATIOS = ['1:2', '1:4', '1:8', '1:16', '1:32', '1:64']

/** Vendor OLT yang didukung — selaras dengan daftar di halaman Inventaris. */
const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER']

/**
 * Form isian perangkat titik baru, muncul setelah lokasi diklik di peta. Field
 * menyesuaikan jenis: Site cukup alamat, ODC/ODP butuh rasio splitter & kapasitas.
 * Uplink (ODC→OLT feeder, ODP→ODC distribusi) TIDAK diisi di sini — ditetapkan
 * dengan menarik kabel di peta agar fisik = logis dan sumber kebenarannya tunggal.
 * Koordinat diambil dari titik klik (ditampilkan, tak bisa diubah manual di sini).
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
  // OLT: site induk (wajib), identitas perangkat, dan kesiapan SNMP.
  const [siteId, setSiteId] = useState('')
  const [sites, setSites] = useState<SiteView[]>([])
  const [vendor, setVendor] = useState('ZTE')
  const [model, setModel] = useState('')
  const [managementIp, setManagementIp] = useState('')
  const [snmpCommunity, setSnmpCommunity] = useState('')
  const [snmpPort, setSnmpPort] = useState('161')

  // Daftar site untuk memilih tempat berdirinya OLT. Wajib dipilih sebelum simpan.
  useEffect(() => {
    if (kind !== 'OLT') return
    let alive = true
    api
      .get<PageResponse<SiteView>>('/api/sites?size=100')
      .then((page) => {
        if (alive) setSites(page.content)
      })
      .catch(() => {
        /* pemilih site opsional untuk pemuatan — tetap wajib saat simpan */
      })
    return () => {
      alive = false
    }
  }, [kind])

  // Normalisasi kode aset: rapikan spasi & seragamkan huruf besar (kode aset konvensinya uppercase).
  const sanitizeCode = (raw: string) => raw.trim().replace(/\s+/g, ' ').toUpperCase()

  const submit = () => {
    const base: Record<string, unknown> = { code: sanitizeCode(code), name: name.trim() }
    if (kind === 'OLT') {
      base.siteId = siteId
      base.vendor = vendor
      if (model.trim()) base.model = model.trim()
      if (managementIp.trim()) base.managementIp = managementIp.trim()
      if (snmpCommunity.trim()) base.snmpCommunity = snmpCommunity.trim()
      base.snmpPort = Number(snmpPort) || 161
      onSave(base)
      return
    }
    if (address.trim()) base.address = address.trim()
    if (kind === 'SITE') {
      onSave(base)
      return
    }
    base.splitterRatio = splitterRatio
    base.capacity = capacity
    onSave(base)
  }

  // OLT wajib pilih site; aset lain hanya butuh kode + nama.
  const canSubmit = code.trim() !== '' && name.trim() !== '' && (kind !== 'OLT' || siteId !== '')

  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{meta.label} baru</h3>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onCancel} aria-label="Batal" />
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        <span className="tnum">
          {lat.toFixed(6)}, {lng.toFixed(6)}
        </span>{' '}
        · seret pin di peta untuk menyetel lokasi
      </p>
      <TextField label="Kode" value={code} onChange={(_, data) => setCode(data.value)} placeholder={`${kind}-001`} />
      <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} />
      {kind === 'OLT' && (
        <>
          <SelectField label="Site induk" value={siteId} onChange={(_, data) => setSiteId(data.value)}>
            <option value="">— pilih site —</option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.code} — {s.name}
              </option>
            ))}
          </SelectField>
          <div className="row" style={{ gap: '0.5rem' }}>
            <SelectField label="Vendor" value={vendor} onChange={(_, data) => setVendor(data.value)} style={{ flex: 1 }}>
              {VENDORS.map((v) => (
                <option key={v} value={v}>
                  {v}
                </option>
              ))}
            </SelectField>
            <TextField
              label={<>Model <span className="muted">(opsional)</span></>}
              value={model}
              onChange={(_, data) => setModel(data.value)}
              style={{ flex: 1 }}
            />
          </div>
          <TextField
            label={<>IP manajemen <span className="muted">(opsional)</span></>}
            value={managementIp}
            onChange={(_, data) => setManagementIp(data.value)}
            placeholder="10.0.0.1"
          />
          <div className="row" style={{ gap: '0.5rem' }}>
            <TextField
              label={<>SNMP community <span className="muted">(opsional)</span></>}
              value={snmpCommunity}
              onChange={(_, data) => setSnmpCommunity(data.value)}
              placeholder="public"
              style={{ flex: 1 }}
            />
            <TextField
              label="Port SNMP"
              type="number"
              min={1}
              max={65535}
              value={snmpPort}
              onChange={(_, data) => setSnmpPort(data.value)}
              style={{ width: '6.5rem' }}
            />
          </div>
        </>
      )}
      {kind !== 'SITE' && kind !== 'OLT' && (
        <TextField
          label={<>Alamat <span className="muted">(opsional)</span></>}
          value={address}
          onChange={(_, data) => setAddress(data.value)}
        />
      )}
      {kind === 'SITE' && (
        <TextField label="Alamat" value={address} onChange={(_, data) => setAddress(data.value)} />
      )}
      {kind === 'ODP' && (
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          ODC induk ditetapkan dengan menarik kabel distribusi dari ODC ke ODP ini di peta —
          bukan di sini — supaya jalur fisik & data uplink selalu sinkron.
        </p>
      )}
      {(kind === 'ODC' || kind === 'ODP') && (
        <div className="row" style={{ gap: '0.5rem' }}>
          <SelectField
            label="Rasio splitter"
            value={splitterRatio}
            onChange={(_, data) => setSplitterRatio(data.value)}
            style={{ flex: 1 }}
          >
            {SPLITTER_RATIOS.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Kapasitas"
            type="number"
            min={1}
            max={kind === 'ODP' ? 256 : 1024}
            value={String(capacity)}
            onChange={(_, data) => setCapacity(Number(data.value))}
            style={{ flex: 1 }}
          />
        </div>
      )}
      <div className="row">
        <Button variant="primary" disabled={!canSubmit} onClick={submit}>
          Simpan {meta.label}
        </Button>
        <Button variant="subtle" onClick={onCancel}>
          Batal
        </Button>
      </div>
    </aside>
  )
}

function Legend() {
  const items: Array<[string, string]> = [
    ['#b47cff', 'Site/POP'],
    [OLT_COLOR, 'OLT'],
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

/** Skala warna heatmap utilisasi port: gradasi hijau (lengang) → merah (penuh). */
function HeatmapLegend() {
  return (
    <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
      <span className="muted" style={{ fontSize: '0.85rem' }}>
        Utilisasi port
      </span>
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        0%
      </span>
      <span
        style={{
          width: 96,
          height: 10,
          borderRadius: 6,
          display: 'inline-block',
          background: 'linear-gradient(90deg,#22c55e,#eab308,#f97316,#ef4444)',
        }}
      />
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        100%
      </span>
    </span>
  )
}

/** Panel jawaban atas pertanyaan lapangan: "di ODP ini ada siapa saja, port mana yang kosong?" */
function OdpPanel({
  inspection,
  canDelete,
  canRelocate,
  onRelocate,
  onDelete,
  onClose,
}: {
  inspection: OdpInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const { upstream } = inspection
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{inspection.code}</h3>
        <Button onClick={onClose}>Tutup</Button>
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

      {(canRelocate || canDelete) && (
        <div className="row wrap" style={{ gap: '0.4rem' }}>
          <RelocateButton show={canRelocate} onClick={onRelocate} />
          {canDelete && (
            <Button variant="danger" onClick={onDelete} disabled={inspection.occupants.length > 0}>
              Hapus ODP
            </Button>
          )}
          {canDelete && inspection.occupants.length > 0 && (
            <span className="muted" style={{ fontSize: '0.78rem', alignSelf: 'center' }}>
              Masih ada pelanggan tersambung.
            </span>
          )}
        </div>
      )}
    </aside>
  )
}
