import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
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
  UnmappedCustomer,
  UtilizationHeatmap,
} from '../api/network'
import { onuStatusLabel } from '../api/network'
import type { PageResponse } from '../api/types'
import { resetAccessLogin } from '../api/bng'
import { rebootCpe, runCpePing } from '../api/cpe'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Checkbox, MessageBar, MessageBarBody } from '@fluentui/react-components'
import { Button, Segmented, SelectField, StatusBadge, TextField } from '@/components/atoms'
import { CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { AccessNodeDetail, Blade, type AccessNodeKind } from '@/components/organisms'
import { CustomerDetailBlade } from './CustomerDetailPage'
import { OltDetail } from './OltDetailPage'
import type { MapFocus } from '@/map/mapFocus'
import { useConfirm, useToast } from '@/system'
import {
  IconChevronDown,
  IconClose,
  IconCrosshair,
  IconCustomers,
  IconFlask,
  IconKey,
  IconMonitor,
  IconPlus,
  IconPower,
  IconRoute,
  IconSettings,
  IconTrash,
  IconWorkOrder,
} from '@/components/atoms/icons'
import {
  canStartCableFrom,
  createCableTool,
  layerNodeKind,
  type CableTool,
  type SnappedDevice,
  type ToolState,
} from '../map/cableTool'

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

/** Untuk apa tiap tema dipakai — supaya pilihannya soal pekerjaan, bukan selera. */
const BASEMAP_HINTS: Record<BasemapMode, string> = {
  streets: 'Nama jalan & alamat terbaca — enak untuk survei dan menuntun teknisi.',
  satellite: 'Citra udara — memastikan tiang, gang, dan atap rumah yang sebenarnya.',
  dark: 'Latar gelap; aset & kabel paling menyala — pandangan NOC.',
}

/** Mode awal: tetap gelap (gaya NOC) agar aset & kabel bercahaya paling menonjol. */
const DEFAULT_BASEMAP: BasemapMode = 'dark'

/**
 * Setelan tampilan peta diingat antar-kunjungan. Ini preferensi mata satu orang di
 * satu perangkat (tema basemap, legenda ditampilkan atau tidak) — bukan data tenant,
 * jadi rumahnya `localStorage`, bukan server. Nilai asing/rusak jatuh ke bawaan.
 */
const PREF_BASEMAP = 'ftth.map.basemap'
const PREF_LEGEND = 'ftth.map.legend'

function savedBasemap(): BasemapMode {
  const saved = localStorage.getItem(PREF_BASEMAP)
  return BASEMAP_ORDER.includes(saved as BasemapMode) ? (saved as BasemapMode) : DEFAULT_BASEMAP
}

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

/**
 * Warna ONU pelanggan menurut status hidup — dasar "perangkat modar → merah".
 *
 * Amber SENGAJA tak dipakai di sini: itu warna identitas ODP (dan warna simpul
 * peringatan). Dulu pelanggan OFFLINE ikut amber sehingga di peta titik pelanggan
 * padam tak bisa dibedakan dari ODP selain dari ukurannya. Kini pelanggan hanya
 * memakai palet kesehatan: hijau (hidup), merah (mati/LOS), kelabu (belum
 * terpantau) — persis yang dijanjikan legenda.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const CUSTOMER_COLOR: any = [
  'match',
  ['get', 'onu_status'],
  'ONLINE',
  '#34d399',
  'OFFLINE',
  '#ff5470',
  'LOS',
  '#ff3b5c',
  // Belum pernah terpantau (PENDING) atau dibongkar → kelabu, bukan merah:
  // tak tahu kabarnya bukan berarti mati.
  '#8b95a7',
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

/**
 * Ambang gerak-isyarat menu "tambah di sini". 500 ms mengikuti tekan-lama bawaan
 * peramban seluler (jadi terasa sama dengan yang sudah dikenal jari operator), dan
 * 10 px memberi ruang goyang tangan tanpa menelan geseran peta yang sesungguhnya.
 */
const LONG_PRESS_MS = 500
const HOLD_DRIFT_PX = 10

/** Jeda buang-klik sesudah menu terbuka — cukup untuk klik susulan dari jari yang sama. */
const CLICK_SWALLOW_MS = 500

/** Perkiraan ukuran kartu menu (lihat `.map-menu`), dipakai menahannya di dalam kanvas. */
const MENU_WIDTH_PX = 224
const MENU_HEIGHT_PX = 210

/** Endapan ketikan pencarian: satu kueri per jeda mengetik, bukan per huruf. */
const SEARCH_DEBOUNCE_MS = 300

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
  const modeRef = useRef<'idle' | 'draw' | 'edit' | 'drag'>('idle')
  // Sampai kapan (ms epoch) klik peta diabaikan. Tahan-lama di layar sentuh melahirkan
  // menu DAN sebuah klik dari jari yang sama; tanpa jeda ini menu tambah baru muncul
  // lalu langsung tertutup — atau lebih buruk, membuka panel perangkat di bawahnya.
  const swallowClickUntil = useRef(0)
  const animRef = useRef<number | null>(null)
  const impactedRef = useRef<number | null>(null)
  // Pin yang bisa diseret untuk menyetel lokasi perangkat baru sebelum disimpan.
  const placeMarker = useRef<maplibregl.Marker | null>(null)
  // Penanda draggable untuk simpul yang SEDANG dipindah lokasinya (mode relokasi);
  // dibuat/dibuang oleh efek ber-dep `relocating`.
  const relocateMarker = useRef<maplibregl.Marker | null>(null)
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
  // Detail pelanggan lengkap sebagai flyout DI ATAS peta. Operator yang menelusuri satu
  // pelanggan hampir selalu perlu melihat sekitarnya lagi setelah membaca detailnya —
  // pindah rute ke halaman Pelanggan membuang konteks peta (zoom, sorotan, panel telusur)
  // dan memaksa dia mencari titik itu lagi.
  const [detailCustomerId, setDetailCustomerId] = useState<string | null>(null)
  // Detail OLT dengan alasan yang sama persis: perangkat inti dibaca sambil melihat
  // hilirnya di peta, jadi ia datang sebagai panel — bukan rute yang membuang peta.
  const [detailOltId, setDetailOltId] = useState<string | null>(null)
  // ODC & ODP ikut aturan yang sama. Panel inspeksinya menjawab pertanyaan lapangan
  // (siapa ikut mati, port mana kosong); detailnya menyimpan identitas, kapasitas, dan
  // satu-satunya jalan menyuntingnya — dulu itu hanya ada di Inventory.
  const [detailNode, setDetailNode] = useState<{ kind: AccessNodeKind; id: string; code: string } | null>(null)
  const [siteInsp, setSiteInsp] = useState<SiteInspection | null>(null)
  const [oltInsp, setOltInsp] = useState<OltView | null>(null)
  // Heatmap utilisasi port: menyala/mati lewat toggle, mewarnai ODP menurut pemakaian.
  const [heatmap, setHeatmap] = useState(false)
  const [editing, setEditing] = useState<CableView | null>(null)
  const [toolState, setToolState] = useState<ToolState | null>(null)
  // Menu "tambah di sini": muncul di titik klik kanan / tahan-lama. `x`/`y` piksel
  // layar untuk menaruh kartunya, `lng`/`lat` titik peta yang jadi lokasi barunya.
  const [addMenu, setAddMenu] = useState<{ lng: number; lat: number; x: number; y: number } | null>(null)
  // Titik yang sudah dipilih & menunggu formnya: perangkat baru, atau pelanggan lama
  // yang belum berkoordinat. Pin draggable-nya sama untuk keduanya.
  const [placeAt, setPlaceAt] = useState<{ kind: AssetKind | 'CUSTOMER'; lng: number; lat: number } | null>(null)
  // Simpul (perangkat/pelanggan) yang panel infonya sedang terbuka & bisa dipindah:
  // dari sini tombol "Pindahkan lokasi" tahu jenis, id, dan titik awalnya, dan tombol
  // "Tarik kabel" tahu ujung awal mana yang harus dikunci (`code` = labelnya di bilah
  // petunjuk, jadi operator melihat "Dari ODP-012" alih-alih ujung tanpa nama).
  const [movable, setMovable] = useState<
    { layer: string; id: string; code: string; lng: number; lat: number } | null
  >(null)
  // Simpul yang SEDANG dalam mode relokasi (penanda draggable aktif). `null` = tak ada.
  const [relocating, setRelocating] = useState<
    { layer: string; id: string; label: string; color: string; lng: number; lat: number } | null
  >(null)
  const [error, setError] = useState<string | null>(null)
  const [basemap, setBasemap] = useState<BasemapMode>(savedBasemap)
  // Laci setelan (kanan). Satu-satunya penghuni sisi kanan pada satu waktu — panel
  // info menutupnya lewat `clearPanels`, jadi keduanya tak pernah bertumpuk.
  const [settingsOpen, setSettingsOpen] = useState(false)
  // Legenda boleh disembunyikan: operator yang sudah hafal warnanya lebih butuh
  // pandangan peta yang lapang daripada kartu yang menjelaskannya lagi.
  const [showLegend, setShowLegend] = useState(() => localStorage.getItem(PREF_LEGEND) !== 'off')
  // Menandai peta sudah berdiri & gayanya termuat — lihat efek basemap di bawah.
  const [mapReady, setMapReady] = useState(false)
  const { can } = useCan()
  const { user } = useAuth()
  const toast = useToast()
  // Dipakai tombol "Buka detail" di panel OLT untuk pindah ke halaman lengkapnya.
  const navigate = useNavigate()
  const location = useLocation()

  // Pesan "buka di peta" dari halaman lain (Inventory, detail pelanggan). Dibaca di sini,
  // tapi dikerjakan efek jauh di bawah — sesudah efek yang MEMBUAT peta, sebab menyuruh
  // peta terbang sebelum ia ada tak berbunyi apa-apa.
  const focus = (location.state as { focus?: MapFocus } | null)?.focus

  // Menutup semua panel info sebelum membuka yang baru — hanya satu tampil.
  const clearPanels = useCallback(() => {
    setSelected(null)
    setCable(null)
    setOtdrTests(null)
    setBlast(null)
    setWhatIf(null)
    setTrace(null)
    setSiteInsp(null)
    setOltInsp(null)
    setMovable(null)
    setSettingsOpen(false)
  }, [])

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
   * Menarik ulang panel inspeksi sebuah ODC/ODP setelah detailnya disunting — tanpa ini
   * panel di belakang blade tetap memajang nama/kapasitas lama tepat di sebelah detail
   * yang sudah benar. Gagal tarik dibiarkan diam: panelnya cuma basi, bukan rusak.
   */
  const reloadNodePanel = async (kind: AccessNodeKind, id: string) => {
    try {
      if (kind === 'odp') setSelected(await api.get<OdpInspection>(`/api/gis/odps/${id}`))
      else setBlast(await api.get<BlastRadiusView>(`/api/gis/odcs/${id}/blast-radius`))
    } catch {
      /* biarkan panel apa adanya */
    }
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
    localStorage.setItem(PREF_BASEMAP, basemap)
    // `mapReady` ikut jadi pemicu, bukan hiasan: saat mount efek ini berjalan LEBIH
    // DULU daripada efek yang membuat petanya, jadi tanpa itu pilihan basemap yang
    // tersimpan tak pernah terpasang — peta selalu terbuka dengan gaya bawaannya.
  }, [basemap, mapReady])

  useEffect(() => {
    localStorage.setItem(PREF_LEGEND, showLegend ? 'on' : 'off')
  }, [showLegend])

  useEffect(() => {
    if (!container.current || map.current) return

    const instance = new maplibregl.Map({
      container: container.current,
      center: INITIAL_CENTER,
      zoom: 14,
      // Klik-ganda dipakai untuk menghapus titik belok saat kabel diedit; kalau ia juga
      // men-zoom, peta melompat tiap kali titik dibuang. Zoom tetap lewat scroll/pinch
      // & kontrol +/−.
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

    /**
     * Koordinat titik yang diklik, diambil dari geometri fiturnya (bukan titik
     * kursor) supaya presisi. Dipakai menandai simpul mana yang panel infonya
     * terbuka sehingga tombol "Pindahkan lokasi" tahu titik awalnya.
     */
    const pointAt = (feature: maplibregl.MapGeoJSONFeature | undefined): { lng: number; lat: number } | null => {
      const g = feature?.geometry
      return g?.type === 'Point' ? { lng: g.coordinates[0], lat: g.coordinates[1] } : null
    }

    /** Kode aset dari properti tile — label ujung kabel & judul mode pindah. */
    const codeOf = (feature: maplibregl.MapGeoJSONFeature | undefined): string =>
      String(feature?.properties?.code ?? '')

    /**
     * Peta menerima klik? Tidak selagi alat kabel/relokasi memegangnya, dan tidak untuk
     * klik "hantu" yang lahir dari tahan-lama pembuka menu tambah (lihat [swallowClickUntil]).
     */
    const acceptsClick = () => modeRef.current === 'idle' && Date.now() >= swallowClickUntil.current

    /**
     * Membuka menu "tambah di sini" pada satu titik peta. Satu pintu untuk dua pemicu
     * yang maksudnya sama: klik kanan di desktop, tahan-lama di layar sentuh.
     */
    const openAddMenu = (lngLat: maplibregl.LngLat, x: number, y: number) => {
      if (modeRef.current !== 'idle') return
      // Jari yang sama akan melepas dan melahirkan sebuah klik; jangan sampai klik itu
      // menutup menu yang baru saja dimintanya.
      swallowClickUntil.current = Date.now() + CLICK_SWALLOW_MS
      // Ditahan di dalam kanvas: menu yang terbit di dekat tepi kanan/bawah akan
      // terpotong, dan pilihan yang terpotong sama saja dengan pilihan yang hilang.
      const box = instance.getCanvas().getBoundingClientRect()
      setAddMenu({
        lng: lngLat.lng,
        lat: lngLat.lat,
        x: Math.max(0, Math.min(x, box.width - MENU_WIDTH_PX)),
        y: Math.max(0, Math.min(y, box.height - MENU_HEIGHT_PX)),
      })
    }

    // Klik kanan di peta → menu tambah. `originalEvent.preventDefault()` menahan menu
    // konteks bawaan peramban, yang kalau muncul akan menutupi menu kita sendiri.
    instance.on('contextmenu', (event) => {
      event.preventDefault()
      event.originalEvent.preventDefault()
      openAddMenu(event.lngLat, event.point.x, event.point.y)
    })

    // Padanan sentuhnya: tahan satu jari diam di satu titik. Dideteksi sendiri, bukan
    // menumpang `contextmenu` — peramban seluler tak seragam memunculkannya di atas
    // kanvas WebGL, jadi menyandarkan fitur ini padanya berarti fitur itu hilang di
    // sebagian ponsel. Jari yang bergeser = operator sedang menggeser peta, batal.
    const canvasArea = instance.getCanvasContainer()
    let holdTimer: number | null = null
    let holdFrom: { x: number; y: number } | null = null
    let holdFired = false
    const cancelHold = () => {
      if (holdTimer != null) window.clearTimeout(holdTimer)
      holdTimer = null
      holdFrom = null
    }
    const onTouchStart = (event: TouchEvent) => {
      cancelHold()
      holdFired = false
      // Dua jari = cubit untuk zoom, bukan tahan-lama.
      if (event.touches.length !== 1) return
      const touch = event.touches[0]
      const rect = canvasArea.getBoundingClientRect()
      const x = touch.clientX - rect.left
      const y = touch.clientY - rect.top
      holdFrom = { x: touch.clientX, y: touch.clientY }
      holdTimer = window.setTimeout(() => {
        holdTimer = null
        holdFired = true
        openAddMenu(instance.unproject([x, y]), x, y)
      }, LONG_PRESS_MS)
    }
    const onTouchMove = (event: TouchEvent) => {
      const touch = event.touches[0]
      if (!holdFrom || !touch) return cancelHold()
      const drift = Math.hypot(touch.clientX - holdFrom.x, touch.clientY - holdFrom.y)
      if (drift > HOLD_DRIFT_PX) cancelHold()
    }
    const onTouchEnd = () => {
      // Jeda buang-klik dihitung ulang DARI SAAT JARI DIANGKAT: menu terbit di detik
      // pertama tahanan, sedangkan jari bisa saja menempel beberapa detik lagi — dan
      // klik susulannya baru lahir sesudah itu.
      if (holdFired) swallowClickUntil.current = Date.now() + CLICK_SWALLOW_MS
      holdFired = false
      cancelHold()
    }
    canvasArea.addEventListener('touchstart', onTouchStart, { passive: true })
    canvasArea.addEventListener('touchmove', onTouchMove, { passive: true })
    canvasArea.addEventListener('touchend', onTouchEnd)
    canvasArea.addEventListener('touchcancel', onTouchEnd)

    // Menu tambah menempel pada satu titik layar; begitu petanya digeser/di-zoom, titik
    // itu tak lagi menunjuk tempat yang sama — jadi ditutup, bukan dibiarkan berbohong.
    instance.on('movestart', () => setAddMenu(null))

    // Klik TUNGGAL di lahan kosong menutup menu tambah yang sedang mengambang.
    // Klik aset/kabel tetap dilayani handler layer di bawah.
    instance.on('click', () => {
      if (!acceptsClick()) return
      setAddMenu(null)
    })

    instance.on('click', 'odp', (event) => {
      // Selagi menggambar/mengedit kabel atau memindah simpul, klik dikuasai alat itu.
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<OdpInspection>(`/api/gis/odps/${id}`)
        .then((odp) => {
          clearPanels()
          setSelected(odp)
          if (at) setMovable({ layer: 'odp', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODP'))
    })

    // Klik pelanggan (mode idle) → telusur jalur ONU → ODP → ODC → OLT.
    instance.on('click', 'customer', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<CustomerTrace>(`/api/gis/trace/customers/${id}`)
        .then((t) => {
          clearPanels()
          setTrace(t)
          if (at) setMovable({ layer: 'customer', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat telusur pelanggan'))
    })

    // Klik site/POP (mode idle) → isi site: OLT + rekap perangkat & pelanggan hilir.
    instance.on('click', 'site', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<SiteInspection>(`/api/gis/sites/${id}`)
        .then((s) => {
          clearPanels()
          setSiteInsp(s)
          if (at) setMovable({ layer: 'site', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail site'))
    })

    // Klik OLT (mode idle) → panel ringkas perangkat (vendor/model/IP + kesiapan
    // SNMP), seragam dengan ODC/ODP/site. Panelnya menyediakan tombol "Buka detail"
    // untuk masuk ke halaman lengkap (edit lokasi/identitas/SNMP & PON port).
    instance.on('click', 'olt', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<OltView>(`/api/olts/${id}`)
        .then((o) => {
          clearPanels()
          setOltInsp(o)
          if (at) setMovable({ layer: 'olt', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail OLT'))
    })

    // Klik ODC (mode idle) → blast radius: siapa saja di hilirnya.
    instance.on('click', 'odc', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<BlastRadiusView>(`/api/gis/odcs/${id}/blast-radius`)
        .then((b) => {
          clearPanels()
          setBlast(b)
          if (at) setMovable({ layer: 'odc', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat blast radius ODC'))
    })

    // Klik kabel (mode idle) → tampilkan detail + aksi edit/hapus.
    instance.on('click', 'cable', (event) => {
      if (!acceptsClick()) return
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
      setMapReady(true)
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
    // DILEWATI bila kita datang membawa pesan sorot: geolokasi menjawab belakangan dan
    // akan menerbangkan peta pergi dari aset yang justru baru saja diminta dilihat.
    if (!focus) locateMe(false)

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
      instance.remove()
      map.current = null
    }
    // Init peta sekali saat mount; `locateMe` stabil (useCallback) & sengaja tak jadi dep
    // agar peta tak dibangun ulang. `focus` sengaja dibaca dari render pertama — yang
    // penting cuma ada/tidaknya pesan sorot saat peta lahir.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * Menjalankan pesan "buka di peta" dari halaman lain: terbang ke titiknya lalu buka
   * panel infonya. Hasilnya sengaja sama persis dengan operator mengklik penanda itu
   * sendiri — endpoint dan panelnya pun sama — jadi tak ada tampilan kedua yang harus
   * dipelajari untuk data yang sama.
   *
   * Berdiri SETELAH efek pembuat peta supaya `map.current` dijamin ada: efek dijalankan
   * menurut urutan deklarasi, dan `flyTo` ke peta yang belum lahir hilang tanpa jejak.
   */
  useEffect(() => {
    if (!focus) return
    const { layer, id, lng, lat } = focus
    let alive = true

    // Terbang lebih dulu, tak menunggu tarikan panel: gerak peta adalah tanda pertama
    // bahwa tombolnya bekerja. Zoom 17 = satu blok perumahan — cukup rapat untuk melihat
    // titik mana, cukup lebar untuk menampilkan tetangga hulunya di layar yang sama.
    map.current?.flyTo({ center: [lng, lat], zoom: 17 })
    clearPanels()
    // Kodenya belum diketahui sampai tarikan mendarat; diisi di `.then` di bawah.
    setMovable({ layer, id, code: '', lng, lat })

    // Tarikan dipisah dari pemasangan supaya penjaga `alive` memeriksa keadaan TERBARU,
    // bukan keadaan saat permintaan dikirim.
    const load = async (): Promise<{ code: string; apply: () => void }> => {
      switch (layer) {
        case 'customer': {
          const t = await api.get<CustomerTrace>(`/api/gis/trace/customers/${id}`)
          return { code: t.customerCode, apply: () => setTrace(t) }
        }
        case 'odp': {
          const o = await api.get<OdpInspection>(`/api/gis/odps/${id}`)
          return { code: o.code, apply: () => setSelected(o) }
        }
        case 'odc': {
          const b = await api.get<BlastRadiusView>(`/api/gis/odcs/${id}/blast-radius`)
          return { code: b.code, apply: () => setBlast(b) }
        }
        case 'olt': {
          const o = await api.get<OltView>(`/api/olts/${id}`)
          return { code: o.code, apply: () => setOltInsp(o) }
        }
        case 'site': {
          const s = await api.get<SiteInspection>(`/api/gis/sites/${id}`)
          return { code: s.code, apply: () => setSiteInsp(s) }
        }
      }
    }

    void load()
      .then(({ code, apply }) => {
        if (!alive) return
        apply()
        setMovable({ layer, id, code, lng, lat })
      })
      .catch((err) => {
        if (alive) setError(err instanceof ApiError ? err.message : 'Gagal memuat detail aset')
      })
      .finally(() => {
        // Pesan router dibersihkan SETELAH panel terpasang, bukan sebelum: membersihkannya
        // di awal mengubah dependensi efek ini, efeknya dijalankan ulang, dan pembersih
        // `alive` membuang hasil tarikan yang belum sempat mendarat — peta diam di tempat.
        // Tetap wajib dibersihkan supaya menyegarkan halaman tak melompat lagi ke aset
        // lama yang sudah tak relevan.
        if (alive) navigate(location.pathname, { replace: true, state: null })
      })

    return () => {
      alive = false
    }
  }, [focus, clearPanels, location.pathname, navigate])

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

  // Sisi kanan peta dihuni satu hal saja pada satu waktu: panel info/telusur, form
  // titik baru, atau laci setelan. Dipakai menyembunyikan tombol gerigi.
  const rightSideBusy = !!(
    selected ||
    cable ||
    blast ||
    whatIf ||
    trace ||
    siteInsp ||
    oltInsp ||
    placeAt ||
    detailCustomerId ||
    detailOltId ||
    detailNode ||
    toolState?.complete
  )

  // Ujung awal kabel = simpul yang panel infonya sedang terbuka. Kosong bila tak
  // berizin, titiknya tak diketahui, atau jenisnya memang tak boleh jadi awal.
  const cableOrigin = cableOriginOf(movable, can('network.cable.create'))

  /**
   * Menarik kabel BERMULA dari perangkat yang panelnya terbuka. Alur lama (tekan
   * "Tarik kabel" di toolbar, lalu cari perangkat sumbernya di peta) menuntut operator
   * menunjuk dua kali: sekali untuk membuka perangkatnya, sekali lagi untuk memilihnya
   * sebagai ujung awal — padahal yang terpampang di layar sudah yang dia maksud.
   */
  const startDrawFrom = () => {
    if (!cableOrigin) return
    // Satu alat aktif pada satu waktu: tutup panel & titik yang menunggu form dulu.
    setPlaceAt(null)
    clearPanels()
    setEditing(null)
    tool.current?.startDraw(cableOrigin)
  }

  const cancelTool = () => {
    tool.current?.cancel()
    setEditing(null)
  }

  /**
   * Titik dari menu "tambah di sini" diteruskan ke formnya. Tak ada lagi mode taruh
   * berlangkah dua (pilih jenis di toolbar → cari lagi titiknya di peta): klik kanan
   * sudah menyebut tempatnya, jadi jenis yang dipilih di menu langsung mendarat di situ.
   */
  const startPlaceAt = (kind: AssetKind | 'CUSTOMER') => {
    if (!addMenu) return
    tool.current?.cancel()
    clearPanels()
    setEditing(null)
    setAddMenu(null)
    setPlaceAt({ kind, lng: addMenu.lng, lat: addMenu.lat })
  }

  /** Menyimpan perangkat titik baru di lokasi yang diklik, lalu menyegarkan tile. */
  const savePlacedAsset = async (payload: Record<string, unknown>) => {
    if (!placeAt || placeAt.kind === 'CUSTOMER') return
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
   * Menaruh pelanggan lama di titik yang dipilih. Bukan membuat pelanggan baru:
   * yang belum berkoordinat itu pelanggan hasil impor massal (tanpa kolom lat/long)
   * yang tersimpan di koordinat penampung 0,0 — jadi yang dikerjakan di sini persis
   * sama dengan memindahkan titiknya, lewat endpoint yang sama pula.
   */
  const savePlacedCustomer = async (customer: UnmappedCustomer) => {
    if (!placeAt) return
    try {
      await api.put(`/api/customers/${customer.id}/location`, {
        longitude: placeAt.lng,
        latitude: placeAt.lat,
      })
      toast.success(`${customer.code} ditaruh di peta`)
      setPlaceAt(null)
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menaruh pelanggan')
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

        {/* Kartu pojok kiri-bawah tinggal legenda — pemilih tema, saklar heatmap, dan
            petunjuk pindah ke laci setelan. Boleh disembunyikan sekalian dari laci. */}
        {showLegend && (
          <div className="map-info">
            {heatmap ? <HeatmapLegend /> : <Legend />}
          </div>
        )}

        {/* Toolbar kiri-atas. Tampil saat idle — termasuk state awal sebelum alat
            pernah dipakai (toolState masih null). */}
        {(!toolState || toolState.mode === 'idle') && !placeAt && !relocating && (
          <MapToolbar onLocate={() => locateMe(true)} />
        )}

        {/* Tombol setelan pojok kanan-atas + lacinya. Disembunyikan selama panel info
            terbuka: keduanya menghuni sisi kanan, dan yang ditunggu operator saat itu
            jelas panelnya — bukan tombol yang akan menimpanya. */}
        {!settingsOpen && !rightSideBusy && (
          <button
            type="button"
            className="map-settings-btn"
            title="Setelan peta"
            aria-label="Setelan peta"
            onClick={() => {
              setAddMenu(null)
              setSettingsOpen(true)
            }}
          >
            <IconSettings size={18} />
          </button>
        )}
        {settingsOpen && (
          <MapSettingsDrawer
            basemap={basemap}
            onBasemap={setBasemap}
            heatmap={heatmap}
            onHeatmap={setHeatmap}
            canHeatmap={can('network.odp.view')}
            showLegend={showLegend}
            onShowLegend={setShowLegend}
            onClose={() => setSettingsOpen(false)}
          />
        )}

        {/* Menu "tambah di sini" pada titik klik kanan / tahan-lama */}
        {addMenu && (
          <AddHereMenu
            at={addMenu}
            can={can}
            onPick={startPlaceAt}
            onClose={() => setAddMenu(null)}
          />
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
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
            onOpenDetail={
              can('network.odc.view') ? () => setDetailNode({ kind: 'odc', id: blast.odcId, code: blast.code }) : undefined
            }
            onDelete={() => void deleteAsset('ODC', blast.odcId, blast.code, () => setBlast(null))}
            onClose={() => setBlast(null)}
          />
        )}
        {whatIf && <CableCutPanel cut={whatIf} onClose={() => setWhatIf(null)} />}
        {trace && (
          <CustomerTracePanel
            // key: panel menyimpan state sendiri (lipatan hop, tombol sibuk) — ganti
            // pelanggan harus memulainya bersih, bukan mewarisi keadaan yang lama.
            key={trace.customerId}
            trace={trace}
            canRelocate={can('customer.customer.update')}
            canResetLogin={can('bng.session.reset')}
            canRebootCpe={can('cpe.device.reboot')}
            canDiagnose={can('cpe.diagnostic.run')}
            canCreateWorkOrder={can('workorder.order.create')}
            canOpenCustomer={can('customer.customer.view')}
            onRelocate={startRelocate}
            // Draft WO dibawa lewat router state: pelanggan, tipe (belum tersambung →
            // pasang baru, sisanya perbaikan), dan vonis panel sebagai deskripsi awal —
            // supaya teknisi membaca temuan yang sama dengan yang dilihat dispatcher.
            onCreateWorkOrder={() =>
              navigate('/work-orders', {
                state: {
                  woDraft: {
                    customerId: trace.customerId,
                    customerName: trace.customerName,
                    type: trace.upstream ? 'REPAIR' : 'PSB',
                    title: `${trace.upstream ? 'Perbaikan' : 'Pasang baru'} ${trace.customerName}`,
                    description: traceVerdict(trace).text,
                  },
                },
              })
            }
            onOpenCustomer={() => setDetailCustomerId(trace.customerId)}
            onClose={() => setTrace(null)}
          />
        )}
        {siteInsp && (
          <SitePanel
            site={siteInsp}
            canDelete={can('network.site.delete')}
            canRelocate={can('network.site.update')}
            onRelocate={startRelocate}
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
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
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
            onOpenDetail={() => setDetailOltId(oltInsp.id)}
            onClose={() => setOltInsp(null)}
          />
        )}
        {selected && (
          <OdpPanel
            inspection={selected}
            canDelete={can('network.odp.delete')}
            canRelocate={can('network.odp.update')}
            onRelocate={startRelocate}
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
            onOpenDetail={
              can('network.odp.view')
                ? () => setDetailNode({ kind: 'odp', id: selected.odpId, code: selected.code })
                : undefined
            }
            onDelete={() => void deleteAsset('ODP', selected.odpId, selected.code, () => setSelected(null))}
            onClose={() => setSelected(null)}
          />
        )}
        {placeAt &&
          (placeAt.kind === 'CUSTOMER' ? (
            <PlaceCustomerForm
              lng={placeAt.lng}
              lat={placeAt.lat}
              onCancel={() => setPlaceAt(null)}
              onSave={savePlacedCustomer}
            />
          ) : (
            <PlaceAssetForm
              kind={placeAt.kind}
              lng={placeAt.lng}
              lat={placeAt.lat}
              onCancel={() => setPlaceAt(null)}
              onSave={savePlacedAsset}
            />
          ))}
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

      {/* Detail pelanggan tampil sebagai flyout separuh layar DI ATAS peta — bentuk yang
          sama persis dengan yang dibuka dari menu Pelanggan, jadi operator tak perlu
          belajar dua tampilan untuk data yang sama. Peta tetap hidup di belakangnya;
          menutup flyout mengembalikan konteks telusur apa adanya.
          "Lihat di peta" di sini cukup menutup flyout: petanya sudah terbentang di
          belakang, lengkap dengan penanda pelanggan yang panel telusurnya terbuka. */}
      <CustomerDetailBlade
        customerId={detailCustomerId}
        onClose={() => setDetailCustomerId(null)}
        onShowOnMap={() => setDetailCustomerId(null)}
      />

      {/* Detail OLT — isi yang sama dengan blade Inventory & halaman `/olts/:id`, tampil
          di atas peta supaya membaca perangkat tak menghapus konteks jaringannya. */}
      <Blade
        open={detailOltId != null}
        title="Detail OLT"
        size="full"
        className="blade-detail"
        onClose={() => setDetailOltId(null)}
      >
        {detailOltId && (
          <OltDetail
            // `key` per-id: memilih OLT lain menukar isi panel, bukan mewarisi tab &
            // data OLT sebelumnya.
            key={detailOltId}
            oltId={detailOltId}
            // Petanya sudah terbentang di belakang panel ini, lengkap dengan penanda OLT
            // yang panel inspeksinya terbuka — "Lihat di peta" cukup menyingkir.
            onShowOnMap={() => setDetailOltId(null)}
            onDeleted={() => {
              // OLT-nya lenyap: tutup panelnya lalu gambar ulang tile agar markernya
              // benar-benar hilang dari peta, bukan cuma dari panel.
              setDetailOltId(null)
              setOltInsp(null)
              refreshTiles()
            }}
          />
        )}
      </Blade>

      {/* Detail ODC/ODP — panel yang sama dengan tab Inventory, termasuk tombol Edit.
          Sebelumnya menyunting nama/kapasitas sebuah ODP berarti meninggalkan peta,
          mencarinya lagi di daftar, lalu kembali; sekarang cukup di tempat. */}
      <Blade
        open={detailNode != null}
        title={detailNode ? `Detail ${detailNode.kind.toUpperCase()}` : ''}
        subtitle={detailNode?.code}
        size="full"
        className="blade-detail"
        onClose={() => setDetailNode(null)}
      >
        {detailNode && (
          <AccessNodeDetail
            // `key`: pindah ke simpul lain menukar isi panel, bukan menumpuk state lama.
            key={detailNode.id}
            kind={detailNode.kind}
            nodeId={detailNode.id}
            // Kode, nama, atau titiknya bisa berubah. Tile digambar ulang DAN panel
            // inspeksi di belakang ditarik ulang — tanpa itu panelnya tetap memajang
            // nama lama tepat di sebelah detail yang sudah benar.
            onChanged={() => {
              refreshTiles()
              void reloadNodePanel(detailNode.kind, detailNode.id)
            }}
            onDeleted={() => {
              setDetailNode(null)
              if (detailNode.kind === 'odc') setBlast(null)
              else setSelected(null)
              refreshTiles()
            }}
            // Petanya sudah di belakang panel & penandanya tersorot — cukup menyingkir.
            onShowOnMap={() => setDetailNode(null)}
          />
        )}
      </Blade>
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
/**
 * Laci setelan peta (kanan). Alasan keberadaannya bukan "tempat menaruh kontrol",
 * melainkan MENGOSONGKAN peta: pemilih tema, saklar heatmap, dan legenda dulu
 * bertumpuk di kartu mengambang yang menemani operator sepanjang hari padahal
 * disentuh sekali-dua. Di laci, semuanya sejangkauan tapi tak ikut menutupi jaringan.
 *
 * Pilihan tema & legenda diingat di [localStorage] (lihat PREF_*) — preferensi mata
 * satu orang di satu perangkat, bukan data tenant.
 */
function MapSettingsDrawer({
  basemap,
  onBasemap,
  heatmap,
  onHeatmap,
  canHeatmap,
  showLegend,
  onShowLegend,
  onClose,
}: {
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  heatmap: boolean
  onHeatmap: (on: boolean) => void
  canHeatmap: boolean
  showLegend: boolean
  onShowLegend: (on: boolean) => void
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <aside className="map-panel blade map-settings">
      <BladeHead title="Setelan peta" onClose={onClose} />
      <div className="blade-body stack" style={{ gap: '1.1rem' }}>
        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Tema peta</h4>
          <BasemapSwitcher value={basemap} onChange={onBasemap} />
          <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
            {BASEMAP_HINTS[basemap]}
          </p>
        </section>

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Tampilan</h4>
          {canHeatmap && (
            <>
              <Checkbox
                label="Heatmap utilisasi ODP"
                checked={heatmap}
                onChange={(_, data) => onHeatmap(!!data.checked)}
              />
              <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
                Mewarnai ODP menurut pemakaian port — untuk melihat di mana kapasitas hampir habis.
              </p>
            </>
          )}
          <Checkbox
            label="Tampilkan legenda"
            checked={showLegend}
            onChange={(_, data) => onShowLegend(!!data.checked)}
          />
        </section>

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Petunjuk</h4>
          <p className="muted" style={{ margin: 0, fontSize: '0.78rem', lineHeight: 1.45 }}>
            <strong>Klik kanan</strong> (atau tahan di layar sentuh) pada peta untuk menambah site, OLT, ODC,
            ODP, atau menaruh pelanggan yang belum berkoordinat.
            <br />
            <strong>Tarik kabel</strong> dimulai dari panel perangkatnya: klik perangkatnya dulu, lalu tekan
            &quot;Tarik kabel&quot;.
          </p>
        </section>
      </div>
    </aside>
  )
}

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

/**
 * Menu "tambah di sini": daftar yang bisa dibuat PADA titik yang barusan ditunjuk.
 * Muncul di titik itu juga, bukan di pojok layar — supaya hubungan "yang ini, di
 * sini" tak perlu diingat-ingat operator. Kosong kalau operator tak berizin membuat
 * apa pun; pemanggil yang memutuskan tak menampilkannya sama sekali.
 *
 * Menutup lewat Escape & klik di luar (peta sendiri menutupnya lewat handler klik).
 */
function AddHereMenu({
  at,
  can,
  onPick,
  onClose,
}: {
  at: { lng: number; lat: number; x: number; y: number }
  can: (perm: string) => boolean
  onPick: (kind: AssetKind | 'CUSTOMER') => void
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const toast = useToast()
  const assets = (Object.keys(ASSET_META) as AssetKind[]).filter((k) => can(ASSET_META[k].createPerm))
  // Menaruh pelanggan = memberi koordinat pada pelanggan yang SUDAH ada (impor massal
  // menaruhnya di 0,0), jadi izinnya "ubah pelanggan", bukan "buat pelanggan".
  const canPlaceCustomer = can('customer.customer.update')
  if (assets.length === 0 && !canPlaceCustomer) return null

  return (
    <div
      className="map-menu"
      style={{ left: at.x, top: at.y }}
      role="menu"
    >
      <button
        type="button"
        className="map-menu-head tnum"
        title="Klik untuk menyalin koordinat"
        onClick={() => {
          const text = `${at.lat.toFixed(6)}, ${at.lng.toFixed(6)}`
          void navigator.clipboard?.writeText(text).then(() => toast.success('Koordinat disalin'))
        }}
      >
        {at.lat.toFixed(6)}, {at.lng.toFixed(6)}
      </button>
      {assets.map((k) => (
        <button key={k} type="button" className="map-menu-item" role="menuitem" onClick={() => onPick(k)}>
          <IconPlus size={15} /> {ASSET_META[k].label}
        </button>
      ))}
      {canPlaceCustomer && (
        <button
          type="button"
          className="map-menu-item"
          role="menuitem"
          onClick={() => onPick('CUSTOMER')}
          title="Pelanggan hasil impor yang belum punya titik di peta"
        >
          <IconCustomers size={15} /> Pelanggan belum berkoordinat
        </button>
      )}
    </div>
  )
}

/**
 * Toolbar kiri-atas. Tinggal satu tombol: menambah perangkat kini lewat menu klik
 * kanan / tahan-lama di titik yang dituju, sehingga peta tak lagi dipenuhi tombol
 * yang semuanya berakhir dengan "sekarang klik lokasinya".
 */
function MapToolbar({ onLocate }: { onLocate: () => void }) {
  return (
    <div className="map-toolbar">
      <Button variant="subtle" onClick={onLocate}>
        <IconCrosshair size={15} /> Lokasi saya
      </Button>
    </div>
  )
}

/**
 * Ujung awal kabel dari simpul yang panelnya terbuka — `null` = tombol "Tarik kabel"
 * tak usah muncul. Aturan "siapa boleh jadi awal" dipinjam dari alat kabelnya sendiri
 * supaya panel tak pernah menawarkan awalan yang nanti ditolak alatnya.
 */
function cableOriginOf(
  movable: { layer: string; id: string; code: string; lng: number; lat: number } | null,
  allowed: boolean,
): SnappedDevice | null {
  if (!movable || !allowed) return null
  const kind = layerNodeKind(movable.layer)
  if (!kind || !canStartCableFrom(kind)) return null
  return { kind, id: movable.id, code: movable.code, lng: movable.lng, lat: movable.lat }
}

/**
 * Tombol "Pindahkan lokasi" seragam di setiap panel info simpul. Menekannya masuk
 * mode relokasi: penanda draggable khas muncul di titik itu (lihat startRelocate).
 * Tersembunyi bila pengguna tak punya izin ubah jenis simpul terkait.
 */
/* ---------- Primitif blade panel peta ----------
   Semua panel peta memakai kerangka yang sama — kepala lengket, command bar datar,
   badan berisi daftar properti "Essentials" — supaya klik ODP, OLT, ODC, site, atau
   pelanggan menghasilkan bentuk yang seragam, persis blade Azure Portal. */

/**
 * Kepala blade: judul (kode aset), baris jenis sumber daya, dan tombol tutup.
 * Judul dipotong elipsis, bukan dibungkus, agar tinggi kepala tetap dan command
 * bar di bawahnya tak naik-turun mengikuti panjang nama.
 */
function BladeHead({
  title,
  subtitle,
  onClose,
  closeLabel = 'Tutup',
}: {
  title: string
  subtitle?: string
  onClose: () => void
  closeLabel?: string
}) {
  return (
    <header className="blade-head">
      <div className="spread">
        <div style={{ minWidth: 0 }}>
          <h3 className="blade-title">{title}</h3>
          {subtitle && <span className="blade-sub">{subtitle}</span>}
        </div>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label={closeLabel} />
      </div>
    </header>
  )
}

/**
 * Aksi "pindahkan lokasi" — sama persis di setiap panel aset, jadi dirakit sekali.
 * Labelnya dipendekkan jadi "Pindahkan": blade cuma selebar 28rem dan konteks
 * "lokasi" sudah jelas dari petanya sendiri.
 */
function relocateAction(onClick: () => void, dividerBefore = false): CommandAction {
  return { key: 'relocate', label: 'Pindahkan', icon: <IconCrosshair size={15} />, onClick, dividerBefore }
}

/**
 * Aksi "Tarik kabel" di panel perangkat: ujung awalnya perangkat ini, tinggal klik
 * titik belok lalu perangkat tujuan. Ditaruh paling depan karena menarik kabel jauh
 * lebih sering dilakukan dari sebuah simpul ketimbang memindahkannya.
 */
function cableAction(onClick: () => void): CommandAction {
  return { key: 'cable', label: 'Tarik kabel', icon: <IconRoute size={15} />, onClick }
}

/** Aksi hapus aset. Datar seperti "Hapus" di command bar halaman tabel, bukan tombol merah. */
function deleteAction(label: string, onClick: () => void, disabled = false): CommandAction {
  return { key: 'delete', label, icon: <IconTrash size={15} />, onClick, disabled }
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
    <aside className="map-panel blade">
      <BladeHead
        title="Kabel baru"
        subtitle={`${TYPE_LABEL[cableType]} · ${from} → ${to} · ${formatLength(lengthMeters)}`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
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
  const primary: CommandAction | undefined = canEdit
    ? { key: 'edit', label: 'Edit jalur', icon: <IconRoute size={15} />, onClick: onEdit }
    : undefined
  const actions: CommandAction[] = []
  if (canSimulate)
    actions.push({ key: 'simulate', label: 'Simulasi putus', icon: <IconFlask size={15} />, onClick: onSimulate })
  if (canDelete) actions.push(deleteAction('Hapus', onDelete))

  return (
    <aside className="map-panel blade">
      {/* Nama yang memimpin, bukan kode: kode kabel kerap auto-generate (UUID) sehingga
          tak dikenali operator — sedangkan namanya menyebut kedua ujung ruas. */}
      <BladeHead title={cable.name} subtitle={`Kabel ${TYPE_LABEL[cable.cableType]} · ${cable.code}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {causes.length > 0 && <CableCauses causes={causes} />}

        <dl className="essentials">
          <Ess label="Status">
            <StatusBadge status={cable.status} />
          </Ess>
          <Ess label="Jenis">{TYPE_LABEL[cable.cableType]}</Ess>
          <Ess label="Jumlah core">{cable.coreCount}</Ess>
          <Ess label="Panjang">
            <span className="tnum">{formatLength(cable.lengthMeters)}</span>
          </Ess>
          <Ess label="Dari">
            {cable.fromKind}
            {cable.fromPortLabel && <span className="muted"> · {cable.fromPortLabel}</span>}
          </Ess>
          <Ess label="Ke">
            {cable.toKind}
            {cable.toPortNumber != null && <span className="muted"> · port {cable.toPortNumber}</span>}
          </Ess>
          <Ess label="Titik jalur">{cable.route.points.length}</Ess>
        </dl>

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
      </div>
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
    <aside className="map-panel blade">
      <BladeHead
        title="Simulasi putus"
        subtitle={`Kabel ${TYPE_LABEL[cut.cableType]} · ${CUT_ROOT_LABEL[cut.severedRootKind] ?? cut.severedRootKind}`}
        onClose={onClose}
      />

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent="warning">
          <MessageBarBody>Kalau ruas ini putus, {cut.customerCount} pelanggan kehilangan layanan.</MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="ODC terdampak">{cut.odcCount > 0 && cut.odcCount}</Ess>
          <Ess label="ODP terdampak">{cut.odpCount > 0 && cut.odpCount}</Ess>
          <Ess label="Pelanggan">{cut.customerCount}</Ess>
          <Ess label="Sudah mati">
            {cut.downCount > 0 && <span style={{ color: 'var(--critical-ink)', fontWeight: 600 }}>{cut.downCount}</span>}
          </Ess>
          <Ess label="Siap broadcast">{withPhone > 0 && `${withPhone} nomor`}</Ess>
        </dl>

        {cut.customers.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Pelanggan terdampak ({cut.customers.length})</p>
            <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
              {cut.customers.map((c) => (
                <AffectedRow key={c.customerId} c={c} />
              ))}
            </div>
          </div>
        )}
      </div>
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
    <div className="stack" style={{ gap: '0.5rem' }}>
      <MessageBar intent="error">
        <MessageBarBody>Kenapa merah — {causes.length} alarm hidup di hilir ruas ini.</MessageBarBody>
      </MessageBar>
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
            style={{ fontSize: '0.8rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          >
            {c.label}
          </span>
        </div>
      ))}
    </div>
  )
}

/** Titik status ONU di daftar panel — dijaga seirama dengan [CUSTOMER_COLOR] di peta. */
const ONU_DOT: Record<string, string> = {
  ONLINE: '#34d399',
  LOS: '#ff3b5c',
  OFFLINE: '#ff5470',
  PENDING: '#8b95a7',
  DISMANTLED: '#8b95a7',
}

/** Panel "kalau ODC ini putus, siapa yang kena" — daftar pelanggan hilir + kesiapan broadcast. */
function BlastRadiusPanel({
  blast,
  canDelete,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  blast: BlastRadiusView
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  /** Kosong = operator tak berizin melihat detail ODC. */
  onOpenDetail?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const withPhone = blast.customers.filter((c) => c.phone).length
  // Panel ini menjawab "siapa yang ikut mati"; identitas & kapasitasnya ada di detail —
  // dibuka sebagai blade di atas peta, sama seperti OLT & pelanggan.
  const primary: CommandAction | undefined = onOpenDetail
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus ODC', onDelete))

  return (
    <aside className="map-panel blade">
      <BladeHead title={blast.code} subtitle={`ODC (FDT) · ${blast.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={blast.energized ? 'warning' : 'error'}>
          <MessageBarBody>
            {blast.energized
              ? `Kalau ODC ini putus, ${blast.customerCount} pelanggan kehilangan layanan.`
              : `ODC tanpa uplink — ${blast.customerCount} pelanggan di hilirnya sudah tak punya jalur.`}
          </MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="Uplink">
            <StatusBadge
              status={blast.energized ? 'ACTIVE' : 'INACTIVE'}
              label={blast.energized ? 'Berenergi' : 'Tanpa uplink'}
            />
          </Ess>
          <Ess label="ODP di hilir">{blast.odpCount}</Ess>
          <Ess label="Pelanggan">{blast.customerCount}</Ess>
          <Ess label="Sudah mati">
            {blast.downCount > 0 && <span style={{ color: 'var(--critical-ink)', fontWeight: 600 }}>{blast.downCount}</span>}
          </Ess>
          <Ess label="Siap broadcast">{withPhone > 0 && `${withPhone} nomor`}</Ess>
        </dl>

        {blast.customers.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Pelanggan terdampak ({blast.customers.length})</p>
            <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
              {blast.customers.map((c) => (
                <AffectedRow key={c.customerId} c={c} />
              ))}
            </div>
          </div>
        )}
      </div>
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
 * Ambang redaman Rx (dBm) untuk memberi vonis di panel. Sengaja disamakan dengan
 * ambang alarm `ONU_LOW_RX` di modul monitoring supaya kalimat vonis dan warna
 * simpul di peta tak pernah bertengkar.
 */
const RX_WARN_DBM = -25
const RX_CRIT_DBM = -27

type VerdictTone = 'good' | 'warning' | 'critical' | 'neutral'

/** Nada vonis → `intent` MessageBar Fluent, agar ikon & tint-nya digambar tema. */
const VERDICT_INTENT: Record<VerdictTone, 'success' | 'warning' | 'error' | 'info'> = {
  good: 'success',
  warning: 'warning',
  critical: 'error',
  neutral: 'info',
}

const VERDICT_COLOR: Record<VerdictTone, string> = {
  good: 'var(--good-ink)',
  warning: 'var(--warning-ink)',
  critical: 'var(--critical-ink)',
  neutral: 'var(--muted)',
}

/**
 * Kata sifat pendamping angka Rx. Warna saja tak boleh jadi satu-satunya pembawa
 * arti (buta warna, cetakan hitam-putih), jadi nilainya selalu didampingi kata.
 */
const RX_WORD: Record<VerdictTone, string> = {
  good: 'wajar',
  warning: 'lemah',
  critical: 'parah',
  neutral: '',
}

/**
 * Satu kalimat "apa yang salah dan tindakan pertamanya apa" — pengganti kerja
 * membaca-silang enam angka. Urutannya sengaja mengikuti urutan kerja operator:
 * yang paling hulu (belum tersambung) dan paling fisik (LOS/mati) lebih dulu,
 * sebab tak ada gunanya menyalahkan PPPoE kalau fibernya putus. Isolir berada di
 * atas pemeriksaan redaman karena itulah alasan sesungguhnya layanan mati.
 */
function traceVerdict(trace: CustomerTrace): { tone: VerdictTone; text: string } {
  const onu = trace.liveOnuStatus ?? trace.onuStatus
  const rx = trace.liveRxPowerDbm ?? trace.installRxPowerDbm
  const bras = trace.bras

  if (!trace.onuSerialNumber || !trace.upstream)
    return { tone: 'neutral', text: 'Belum tersambung — ONU/port ODP belum ditetapkan. Butuh WO pemasangan.' }
  if (onu === 'LOS')
    return { tone: 'critical', text: 'ONU LOS — sinyal fiber hilang. Curigai drop core putus atau konektor lepas.' }
  if (onu && onu !== 'ONLINE')
    return { tone: 'critical', text: 'ONU mati — pastikan listrik/adaptor di rumah dulu sebelum turun ke fiber.' }
  if (bras?.accessStatus === 'ISOLATED')
    return { tone: 'warning', text: 'Akun diisolir — layanan sengaja diputus. Pulihkan dari detail pelanggan.' }
  if (rx != null && rx <= RX_CRIT_DBM)
    return { tone: 'critical', text: `Redaman parah ${rx.toFixed(1)} dBm — perlu perbaikan splicing/konektor.` }
  if (rx != null && rx <= RX_WARN_DBM)
    return { tone: 'warning', text: `Redaman lemah ${rx.toFixed(1)} dBm — masih jalan tapi rawan. Jadwalkan cek jalur.` }
  if (!bras)
    return { tone: 'warning', text: 'ONU online tapi belum punya akun PPPoE — layanan belum bisa dipakai.' }
  if (!bras.online)
    return { tone: 'warning', text: 'Fisik sehat, PPPoE tak tersambung — coba Reset Login, lalu cek user/password.' }
  if (trace.cpeOnline === false)
    return { tone: 'warning', text: 'Layanan jalan, tapi router tak melapor ke ACS — remote management mati.' }
  return { tone: 'good', text: 'Sehat — ONU online, sinyal wajar, sesi PPPoE tersambung.' }
}

/** "3 hari lalu" / "2 jam lalu" — cukup untuk menakar seberapa basi sebuah bacaan. */
function agoLabel(iso: string): string {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'barusan'
  if (minutes < 60) return `${minutes} menit lalu`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} jam lalu`
  return `${Math.round(hours / 24)} hari lalu`
}

/**
 * Panel telusur pelanggan: jalur fisik dari rumah pelanggan menaiki topologi
 * sampai BRAS — menjawab "kenapa pelanggan ini bermasalah dan apa tindakannya".
 *
 * Disusun seperti blade Azure Portal: kepala (nama + jenis sumber daya), command
 * bar datar berisi aksi, lalu badan berupa MessageBar vonis + daftar properti
 * "Essentials" dua kolom. Bentuk ini dipilih karena operator MEMINDAI properti,
 * bukan membaca kalimat bersambung titik-tengah. Rantai hop tetap dilipat: yang
 * menarik cuma saat ada yang salah, dan ringkasannya sudah ada di baris "Jalur".
 */
function CustomerTracePanel({
  trace,
  canRelocate,
  canResetLogin,
  canRebootCpe,
  canDiagnose,
  canCreateWorkOrder,
  canOpenCustomer,
  onRelocate,
  onCreateWorkOrder,
  onOpenCustomer,
  onClose,
}: {
  trace: CustomerTrace
  canRelocate: boolean
  canResetLogin: boolean
  canRebootCpe: boolean
  canDiagnose: boolean
  canCreateWorkOrder: boolean
  canOpenCustomer: boolean
  onRelocate: () => void
  onCreateWorkOrder: () => void
  onOpenCustomer: () => void
  onClose: () => void
}) {
  const toast = useToast()
  const confirm = useConfirm()
  const [busy, setBusy] = useState<string | null>(null)
  const verdict = traceVerdict(trace)
  // Rantai hop terbuka sendiri saat ada masalah; saat sehat cukup remah-remah jalur.
  const [hopsOpen, setHopsOpen] = useState(verdict.tone !== 'good')

  const bras = trace.bras
  const rxLive = trace.liveRxPowerDbm
  // Warna Rx dihitung dari angkanya sendiri, bukan dari `opticalHealth` — health itu
  // turunan redaman SAAT INSTALASI dan sering UNKNOWN, jadi tak boleh mengaburkan
  // bacaan hidup yang justru paling dipercaya operator.
  const rxTone = rxLive == null ? 'neutral' : rxLive <= RX_CRIT_DBM ? 'critical' : rxLive <= RX_WARN_DBM ? 'warning' : 'good'

  const act = async (key: string, run: () => Promise<string>) => {
    setBusy(key)
    try {
      toast.success(await run())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Aksi gagal')
    } finally {
      setBusy(null)
    }
  }

  const doResetLogin = async () => {
    if (!bras) return
    const ok = await confirm({
      title: 'Reset Login',
      message: `Putus sesi PPPoE ${bras.username} agar router dial ulang? Pelanggan terputus beberapa detik.`,
      confirmLabel: 'Reset Login',
    })
    if (!ok) return
    await act('reset', async () => {
      await resetAccessLogin(bras.accessId)
      return 'Sesi diputus — router akan dial ulang'
    })
  }

  const doReboot = async () => {
    if (!trace.cpeDeviceId) return
    const ok = await confirm({
      title: 'Reboot ONT/router',
      message: `Reboot perangkat ${trace.customerName}? Layanan mati sekitar 1–2 menit.`,
      confirmLabel: 'Reboot',
      danger: true,
    })
    if (!ok) return
    await act('reboot', async () => {
      const res = await rebootCpe(trace.cpeDeviceId as string)
      return res.status === 'SUCCESS' ? 'Reboot dikirim ke perangkat' : (res.detail ?? 'Reboot gagal dikirim')
    })
  }

  const doPing = () =>
    act('ping', async () => {
      const res = await runCpePing(trace.cpeDeviceId as string)
      if (!res.ok) return res.message
      const avg = res.averageResponseMs
      return `Ping ${res.host}: ${res.successCount ?? 0} ok / ${res.failureCount ?? 0} gagal${
        avg != null ? ` · rata-rata ${avg} ms` : ''
      }`
    })

  const showResetLogin = canResetLogin && bras != null
  const showCpeActions = trace.cpeDeviceId != null

  // Command bar ala Azure: aksi utama dipatok kiri sebagai CTA biru, aksi perangkat
  // menyusul, lalu aksi navigasi dipisah garis vertikal. Label berubah saat sibuk —
  // tombol datar tak punya spinner, jadi teksnyalah yang menjadi tanda kerja jalan.
  const primaryAction: CommandAction | undefined = canCreateWorkOrder
    ? { key: 'wo', label: 'Buat WO', icon: <IconWorkOrder size={15} />, onClick: onCreateWorkOrder, disabled: busy != null }
    : undefined

  const actions: CommandAction[] = []
  if (showResetLogin)
    actions.push({
      key: 'reset',
      label: busy === 'reset' ? 'Memutus…' : 'Reset Login',
      icon: <IconKey size={15} />,
      onClick: () => void doResetLogin(),
      disabled: busy != null,
    })
  if (showCpeActions && canRebootCpe)
    actions.push({
      key: 'reboot',
      label: busy === 'reboot' ? 'Mengirim…' : 'Reboot ONT',
      icon: <IconPower size={15} />,
      onClick: () => void doReboot(),
      disabled: busy != null,
    })
  if (showCpeActions && canDiagnose)
    actions.push({
      key: 'ping',
      label: busy === 'ping' ? 'Ping…' : 'Ping',
      icon: <IconRoute size={15} />,
      onClick: () => void doPing(),
      disabled: busy != null,
    })
  if (canOpenCustomer)
    actions.push({
      key: 'detail',
      label: 'Detail pelanggan',
      icon: <IconCustomers size={15} />,
      onClick: onOpenCustomer,
      disabled: busy != null,
      dividerBefore: actions.length > 0,
    })
  if (canRelocate) actions.push({ ...relocateAction(onRelocate, !canOpenCustomer && actions.length > 0), disabled: busy != null })

  const onuStatus = trace.liveOnuStatus ?? trace.onuStatus
  // Kode ODP tak dibawa sebagai kolom tersendiri di trace — hop ODP-lah sumbernya.
  const odpHop = trace.hops.find((h) => h.kind === 'ODP')

  return (
    <aside className="map-panel blade">
      <BladeHead title={trace.customerName} subtitle={`Pelanggan · ${trace.customerCode}`} onClose={onClose} />

      {(primaryAction || actions.length > 0) && <CommandBar primary={primaryAction} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={VERDICT_INTENT[verdict.tone]}>
          <MessageBarBody>{verdict.text}</MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="Status ONU">
            {onuStatus ? (
              <StatusBadge status={onuStatus} label={onuStatusLabel(onuStatus)} />
            ) : (
              <span className="muted">Belum terpasang</span>
            )}
          </Ess>
          <Ess label="Serial ONU">{trace.onuSerialNumber && <span className="tnum">{trace.onuSerialNumber}</span>}</Ess>
          <Ess label="Titik ODP">
            {odpHop && (
              <>
                {odpHop.code}
                {trace.odpPortNumber != null && <span className="muted"> · port {trace.odpPortNumber}</span>}
              </>
            )}
          </Ess>
          <Ess label="Redaman Rx">
            {rxLive != null ? (
              <>
                <span className="tnum" style={{ color: VERDICT_COLOR[rxTone], fontWeight: 600 }}>
                  {rxLive.toFixed(1)} dBm
                </span>
                <span className="muted">
                  {' '}
                  {RX_WORD[rxTone]}
                  {trace.installRxPowerDbm != null && ` · saat pasang ${trace.installRxPowerDbm.toFixed(1)} dBm`}
                </span>
              </>
            ) : trace.installRxPowerDbm != null ? (
              <>
                <span className="tnum">{trace.installRxPowerDbm.toFixed(1)} dBm</span>
                <span className="muted"> saat pasang · belum ada bacaan hidup</span>
              </>
            ) : (
              /* Estimasi hanya berguna selagi tak ada ukuran nyata; menampilkannya
                 bersama Rx terukur cuma memancing "yang mana yang benar". */
              trace.estimatedLossDb != null && (
                <span className="muted">perkiraan rugi {trace.estimatedLossDb.toFixed(1)} dB · belum pernah terukur</span>
              )
            )}
          </Ess>
          <Ess label="Jarak serat">{trace.distanceMeters != null && `${trace.distanceMeters} m`}</Ess>
          <Ess label="Sesi PPPoE">
            {bras ? (
              <>
                <StatusBadge status={bras.online ? 'ONLINE' : 'OFFLINE'} label={bras.online ? 'Online' : 'Offline'} />
                {!bras.online && bras.lastSeenAt && <span className="muted"> · terakhir {agoLabel(bras.lastSeenAt)}</span>}
              </>
            ) : (
              <span className="muted">Belum ada akun</span>
            )}
          </Ess>
          <Ess label="Akun">{bras?.username}</Ess>
          <Ess label="Alamat IP">{bras?.framedIp && <span className="tnum">{bras.framedIp}</span>}</Ess>
          <Ess label="Paket">{bras?.rateProfileName}</Ess>
          <Ess label="BRAS">{bras?.nasName}</Ess>
          <Ess label="Router (ACS)">
            {trace.cpeDeviceId != null && (
              <StatusBadge
                status={trace.cpeOnline ? 'ONLINE' : 'OFFLINE'}
                label={trace.cpeOnline ? 'Melapor' : 'Tak melapor'}
              />
            )}
          </Ess>
        </dl>

        {trace.hops.length > 0 && (
          <div className="stack" style={{ gap: '0.5rem' }}>
            <button
              type="button"
              className="blade-disclosure"
              onClick={() => setHopsOpen((v) => !v)}
              aria-expanded={hopsOpen}
            >
              <span className="chev" aria-hidden>
                <IconChevronDown size={14} />
              </span>
              Telusur jalur ({trace.hops.length} hop)
            </button>
            {!hopsOpen && (
              <p className="muted" style={{ margin: 0, fontSize: '0.78rem', lineHeight: 1.6 }}>
                {/* Hop pelanggan tak ber-kode (cuma "Rumah pelanggan") — pakai namanya
                    agar remah-remah jalur tak diawali panah menggantung. */}
                {trace.hops.map((h) => h.code || h.name).join(' → ')}
              </p>
            )}
            {hopsOpen && (
              <ol className="timeline">
                {trace.hops.map((hop: TraceHop, i: number) => {
                  const hopColor =
                    hop.online == null ? undefined : hop.online ? 'var(--good-ink)' : 'var(--critical-ink)'
                  return (
                    <li key={`${hop.kind}-${hop.code}-${i}`}>
                      <span
                        className="tl-dot"
                        aria-hidden="true"
                        style={hopColor ? { background: hopColor } : undefined}
                      />
                      <div className="stack" style={{ gap: '0.1rem' }}>
                        <strong style={{ fontSize: '0.82rem', color: hopColor }}>
                          {[HOP_LABEL[hop.kind] ?? hop.kind, hop.code].filter(Boolean).join(' ')}
                        </strong>
                        <span className="muted" style={{ fontSize: '0.78rem' }}>
                          {hop.name}
                        </span>
                        {hop.detail && (
                          <span className="muted tnum" style={{ fontSize: '0.76rem' }}>
                            {hop.detail}
                          </span>
                        )}
                      </div>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        )}
      </div>
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
  onDrawCable,
  onDelete,
  onClose,
}: {
  site: SiteInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  // Server menolak hapus site selama masih ada OLT berdiri di sini, jadi tombolnya
  // dikunci lebih dulu — lebih jujur daripada membiarkan operator kena galat.
  const deleteBlocked = site.oltCount > 0
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus site', onDelete, deleteBlocked))

  return (
    <aside className="map-panel blade">
      <BladeHead title={site.code} subtitle={`Site/POP · ${site.name}`} onClose={onClose} />
      {actions.length > 0 && <CommandBar actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {canDelete && deleteBlocked && (
          <MessageBar intent="info">
            <MessageBarBody>Site tak bisa dihapus selama masih ada {site.oltCount} OLT terpasang.</MessageBarBody>
          </MessageBar>
        )}

        <dl className="essentials">
          <Ess label="Alamat">{site.address}</Ess>
          <Ess label="OLT">{site.oltCount}</Ess>
          <Ess label="ODC">{site.odcCount}</Ess>
          <Ess label="ODP">{site.odpCount}</Ess>
          <Ess label="Pelanggan">{site.customerCount}</Ess>
        </dl>
        <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
          Seluruh perangkat &amp; pelanggan yang bergantung pada site ini.
        </p>

        {site.olts.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">OLT di site ini ({site.olts.length})</p>
            {site.olts.map((olt) => (
              <SiteOltRow key={olt.id} olt={olt} />
            ))}
          </div>
        )}
      </div>
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
  onDrawCable,
  onOpenDetail,
  onClose,
}: {
  olt: OltView
  canView: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  onOpenDetail: () => void
  onClose: () => void
}) {
  const primary: CommandAction | undefined = canView
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))

  return (
    <aside className="map-panel blade">
      <BladeHead title={olt.code} subtitle={`OLT · ${olt.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {/* SNMP mati bukan sekadar keterangan — tanpa itu OLT ini tak terpantau sama
            sekali, jadi diangkat jadi peringatan alih-alih lencana abu di tengah baris. */}
        {!olt.pollable && (
          <MessageBar intent="warning">
            <MessageBarBody>SNMP belum diset — status ONU di bawah OLT ini tak akan pernah ter-poll.</MessageBarBody>
          </MessageBar>
        )}

        <dl className="essentials">
          <Ess label="Status">
            <StatusBadge status={olt.status} />
          </Ess>
          <Ess label="Vendor">{olt.vendor}</Ess>
          <Ess label="Model">{olt.model}</Ess>
          <Ess label="Port PON">{olt.ponPortCount}</Ess>
          <Ess label="Site">{olt.siteName}</Ess>
          <Ess label="IP manajemen">{olt.managementIp && <span className="tnum">{olt.managementIp}</span>}</Ess>
          <Ess label="SNMP">
            {olt.pollable ? (
              <>
                <StatusBadge status="ACTIVE" label="Siap" />
                <span className="muted"> · port {olt.snmpPort}</span>
              </>
            ) : (
              <span className="muted">Belum diset</span>
            )}
          </Ess>
        </dl>
        <p className="muted" style={{ margin: 0, fontSize: '0.78rem' }}>
          Perangkat inti: kalau OLT ini modar, seluruh jalur di hilirnya ikut mati.
        </p>
      </div>
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
    <aside className="map-panel blade">
      <BladeHead
        title={`${meta.label} baru`}
        subtitle={`${lat.toFixed(6)}, ${lng.toFixed(6)} · seret pin untuk menggeser`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
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
      </div>
    </aside>
  )
}

/**
 * Pemilih "pelanggan belum berkoordinat" untuk titik yang barusan ditunjuk. Peta tak
 * membuat pelanggan baru — pendaftaran ada di halaman Pelanggan lengkap dengan paket
 * & identitas; yang kurang di peta justru sebaliknya: pelanggan hasil impor massal
 * yang sudah terdaftar tapi tak pernah dapat titik. Jadi ini daftar-pilih, bukan form.
 */
function PlaceCustomerForm({
  lng,
  lat,
  onCancel,
  onSave,
}: {
  lng: number
  lat: number
  onCancel: () => void
  onSave: (customer: UnmappedCustomer) => void
}) {
  const [query, setQuery] = useState('')
  const [rows, setRows] = useState<UnmappedCustomer[] | null>(null)
  const [picked, setPicked] = useState<UnmappedCustomer | null>(null)
  const [busy, setBusy] = useState(false)

  // Ketikan diendapkan dulu: daftarnya dicari di server (yang belum berkoordinat bisa
  // ribuan sesudah impor), dan menembakkan satu kueri per huruf hanya membuat hasil
  // lama menimpa hasil baru. `alive` menjaga respons basi tak mendarat.
  useEffect(() => {
    let alive = true
    const timer = window.setTimeout(() => {
      api
        .get<UnmappedCustomer[]>(`/api/customers/unmapped?limit=30&query=${encodeURIComponent(query.trim())}`)
        .then((list) => {
          if (alive) setRows(list)
        })
        .catch(() => {
          if (alive) setRows([])
        })
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      alive = false
      window.clearTimeout(timer)
    }
  }, [query])

  const submit = () => {
    if (!picked) return
    setBusy(true)
    onSave(picked)
  }

  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Taruh pelanggan"
        subtitle={`${lat.toFixed(6)}, ${lng.toFixed(6)} · seret pin untuk menggeser`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
        <TextField
          label="Cari pelanggan"
          value={query}
          onChange={(_, data) => setQuery(data.value)}
          placeholder="Kode, nama, alamat, atau nomor HP"
        />

        {rows == null && <p className="muted" style={{ margin: 0 }}>Memuat…</p>}
        {rows != null && rows.length === 0 && (
          <MessageBar intent="info">
            <MessageBarBody>
              {query.trim()
                ? 'Tak ada pelanggan belum berkoordinat yang cocok.'
                : 'Semua pelanggan sudah punya titik di peta. Pelanggan baru didaftarkan di halaman Pelanggan.'}
            </MessageBarBody>
          </MessageBar>
        )}
        {rows != null && rows.length > 0 && (
          <ul className="pick-list" role="listbox" aria-label="Pelanggan belum berkoordinat">
            {rows.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={picked?.id === c.id}
                  className={`pick-item${picked?.id === c.id ? ' is-picked' : ''}`}
                  onClick={() => setPicked(c)}
                >
                  <span className="pick-title">
                    {c.code} — {c.name}
                  </span>
                  <span className="muted">{[c.address, c.phone].filter(Boolean).join(' · ')}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="row">
          <Button variant="primary" disabled={!picked || busy} onClick={submit}>
            {picked ? `Taruh ${picked.code} di sini` : 'Pilih pelanggan dulu'}
          </Button>
          <Button variant="subtle" onClick={onCancel}>
            Batal
          </Button>
        </div>
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
    ['#8b95a7', 'Belum terpantau'],
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
  onDrawCable,
  onOpenDetail,
  onDelete,
  onClose,
}: {
  inspection: OdpInspection
  canDelete: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  /** Kosong = operator tak berizin melihat detail ODP. */
  onOpenDetail?: () => void
  onDelete: () => void
  onClose: () => void
}) {
  const { upstream } = inspection
  // Sama seperti site: server menolak hapus ODP yang masih dihuni, jadi dikunci di sini.
  const deleteBlocked = inspection.occupants.length > 0
  // Panel ini soal port & penghuni; identitas, kapasitas, dan suntingnya ada di detail.
  const primary: CommandAction | undefined = onOpenDetail
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))
  if (canDelete) actions.push(deleteAction('Hapus ODP', onDelete, deleteBlocked))

  return (
    <aside className="map-panel blade">
      <BladeHead title={inspection.code} subtitle={`ODP (FAT) · ${inspection.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {!upstream.complete && (
          <MessageBar intent="warning">
            <MessageBarBody>Jalur hulu belum lengkap — ODP ini belum tersambung utuh sampai OLT.</MessageBarBody>
          </MessageBar>
        )}
        {canDelete && deleteBlocked && (
          <MessageBar intent="info">
            <MessageBarBody>
              ODP tak bisa dihapus selama masih ada {inspection.occupants.length} pelanggan tersambung.
            </MessageBarBody>
          </MessageBar>
        )}

        <div className="stack" style={{ gap: '0.35rem' }}>
          <div className="spread">
            <span style={{ fontSize: '0.82rem' }}>
              {inspection.usedPorts}/{inspection.capacity} port terpakai
            </span>
            <span className="tnum" style={{ fontSize: '0.82rem', fontWeight: 600 }}>
              {inspection.utilizationPercent}%
            </span>
          </div>
          <div className="meter">
            <div
              className={`meter-fill ${
                inspection.utilizationPercent >= 90 ? 'crit' : inspection.utilizationPercent >= 70 ? 'warn' : ''
              }`}
              style={{ width: `${inspection.utilizationPercent}%` }}
            />
          </div>
        </div>

        <dl className="essentials">
          <Ess label="Port kosong">
            {inspection.availablePortNumbers.length > 0 ? (
              <span className="tnum">{inspection.availablePortNumbers.join(', ')}</span>
            ) : (
              <span className="muted">Penuh</span>
            )}
          </Ess>
          <Ess label="ODC induk">{upstream.odcCode}</Ess>
          <Ess label="PON">{upstream.ponPortLabel}</Ess>
          <Ess label="OLT">{upstream.oltCode}</Ess>
          <Ess label="Site">{upstream.siteCode}</Ess>
          <Ess label="Rugi splitter">
            <span className="tnum">{upstream.splitterLossDb.toFixed(1)} dB</span>
          </Ess>
        </dl>

        <div className="stack" style={{ gap: '0.45rem' }}>
          <p className="blade-section-title">Pelanggan ({inspection.occupants.length})</p>
          {inspection.occupants.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Belum ada pelanggan tersambung.
            </p>
          ) : (
            <table>
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
                    <td className="tnum">{occupant.portNumber}</td>
                    <td>
                      {occupant.customerName}
                      <br />
                      <span className="muted" style={{ fontSize: '0.8rem' }}>
                        {occupant.phone ?? occupant.customerCode}
                      </span>
                    </td>
                    <td>
                      <span className="muted tnum" style={{ fontSize: '0.8rem' }}>
                        {occupant.onuSerialNumber}
                      </span>
                      <br />
                      <StatusBadge status={occupant.onuStatus} label={onuStatusLabel(occupant.onuStatus)} />
                    </td>
                    <td>
                      <span className="tnum" style={{ color: HEALTH_COLOR[occupant.opticalHealth], fontWeight: 600 }}>
                        {occupant.installRxPowerDbm != null ? `${occupant.installRxPowerDbm} dBm` : occupant.opticalHealth}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </aside>
  )
}
