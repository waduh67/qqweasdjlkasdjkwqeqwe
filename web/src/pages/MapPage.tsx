import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { api, ApiError, tokenStore } from '../api/client'
import type {
  BlastRadiusView,
  CableCutView,
  CableInstallation,
  CableOwnership,
  CableType,
  CableView,
  CustomerTrace,
  ImpactCause,
  ImpactedOverlay,
  JointBoxView,
  OdfView,
  OdpInspection,
  OltView,
  OtdrTest,
  RecordOtdrTest,
  SiteInspection,
  SiteView,
  SurveyCapacityView,
  SurveyOdp,
  TraceHop,
  UnmappedCustomer,
  UtilizationHeatmap,
} from '../api/network'
import { SPLITTER_RATIOS, onuStatusLabel } from '../api/network'
import type { PageResponse } from '../api/types'
import { resetAccessLogin } from '../api/bng'
import { rebootCpe, runCpePing } from '../api/cpe'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Checkbox, MessageBar, MessageBarBody } from '@fluentui/react-components'
import { Button, Segmented, SelectField, StatusBadge, TextField } from '@/components/atoms'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import {
  AccessNodeDetail,
  Blade,
  OdfDetail,
  type AccessNodeKind,
} from '@/components/organisms'
import {
  AffectedRow,
  CableCutPanel,
  CablePanel,
  JointBoxPanel,
  OdfPanel,
  OdpPanel,
  OltPanel,
  SaveCablePanel,
  SitePanel,
  cableAction,
  deleteAction,
  relocateAction,
} from '@/components/organisms/map'
import { CustomerDetailBlade } from './CustomerDetailPage'
import { OltDetail } from './OltDetailPage'
import type { MapFocus } from '@/map/mapFocus'
import {
  BASEMAPS,
  BASEMAP_HINTS,
  BASEMAP_ORDER,
  DASH_SEQUENCE,
  FUTURISTIC_STYLE,
  HEATMAP_COLOR,
  INITIAL_CENTER,
  JOINT_BOX_COLOR,
  MAP_LAYER_GROUPS,
  NODE_CRITICAL_COLOR,
  NODE_LAYERS,
  NODE_WARNING_COLOR,
  ODF_COLOR,
  OLT_COLOR,
  OTDR_COLOR,
  PREF_BASEMAP,
  PREF_HIDDEN_LAYERS,
  PREF_LEGEND,
  SEVERITY_COLOR,
  WHATIF_COLOR,
  savedBasemap,
  savedHiddenLayers,
  watermarkTile,
  zoomWidth,
  type BasemapMode,
} from '@/map/mapStyle'
import {
  ASSET_META,
  CLICK_SWALLOW_MS,
  HOLD_DRIFT_PX,
  LONG_PRESS_MS,
  MENU_HEIGHT_PX,
  MENU_WIDTH_PX,
  MOVABLE_NODES,
  NODE_KIND_LABEL,
  SEARCH_DEBOUNCE_MS,
  SURVEY_RADIUS_M,
  type AssetKind,
} from '@/map/mapAssets'
import {
  TYPE_LABEL,
  cableOriginOf,
  cableRequestBody,
  drawHint,
  formatLength,
} from '@/map/cableFormat'
import { useConfirm, useToast } from '@/system'
import {
  IconChevronDown,
  IconCrosshair,
  IconCustomers,
  IconKey,
  IconMonitor,
  IconPlus,
  IconPower,
  IconRoute,
  IconSettings,
  IconWorkOrder,
} from '@/components/atoms/icons'
import { createCableTool, type CableTool, type ToolState } from '../map/cableTool'

/**
 * Peta jaringan berbasis vector tile.
 *
 * Tile dirender PostGIS (`ST_AsMVT`) dan diambil per ubin, sehingga jumlah aset
 * yang tergambar tidak membebani browser — inilah yang membuat peta tetap ringan
 * di puluhan ribu titik. Klik sebuah ODP untuk melihat siapa yang tersambung.
 */


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
  // Joint box yang panelnya terbuka. Tak ada endpoint "inspeksi" khusus seperti ODP:
  // kotak sambung tak punya hilir sendiri untuk diringkas — isinya justru sambungan
  // core, dan itu urusan layar sambungan. Jadi panelnya cukup memakai view CRUD-nya.
  const [jointBox, setJointBox] = useState<JointBoxView | null>(null)
  // Rak POP yang panelnya terbuka. Sama seperti joint box, tak ada endpoint inspeksi
  // GIS: yang menarik dari sebuah rak bukan "siapa di hilirnya" melainkan berapa
  // adapter yang masih kosong — dan itu sudah ada di view CRUD-nya.
  const [odf, setOdf] = useState<OdfView | null>(null)
  // Detail rak sebagai blade penuh di atas peta, sejalan dengan detail OLT/ODC/ODP.
  const [detailOdfId, setDetailOdfId] = useState<string | null>(null)
  // Heatmap utilisasi port: menyala/mati lewat toggle, mewarnai ODP menurut pemakaian.
  const [heatmap, setHeatmap] = useState(false)
  const [editing, setEditing] = useState<CableView | null>(null)
  const [toolState, setToolState] = useState<ToolState | null>(null)
  // Menu "tambah di sini": muncul di titik klik kanan / tahan-lama. `x`/`y` piksel
  // layar untuk menaruh kartunya, `lng`/`lat` titik peta yang jadi lokasi barunya.
  const [addMenu, setAddMenu] = useState<{ lng: number; lat: number; x: number; y: number } | null>(null)
  const [survey, setSurvey] = useState<SurveyCapacityView | null>(null)
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
  // Kelompok lapisan yang sedang DISEMBUNYIKAN (lihat [MAP_LAYER_GROUPS]).
  const [hiddenLayers, setHiddenLayers] = useState<Set<string>>(savedHiddenLayers)
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
    setJointBox(null)
    setOdf(null)
    setSurvey(null)
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
      else if (kind === 'joint_box') setJointBox(await api.get<JointBoxView>(`/api/joint-boxes/${id}`))
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

  /**
   * Memasang saklar lapisan ke peta. Sama seperti efek basemap, `mapReady` ikut jadi
   * pemicu karena efek ini berjalan lebih dulu daripada efek pembuat petanya —
   * tanpa itu pilihan yang tersimpan tak pernah terpasang saat halaman dibuka.
   *
   * Menyembunyikan lapisan juga membuatnya tak bisa diklik maupun dijadikan ujung
   * kabel: `queryRenderedFeatures` cuma menjawab yang tergambar. Itu memang yang
   * diharapkan — yang tak terlihat tak bisa ditunjuk — dan disebutkan di lacinya.
   */
  useEffect(() => {
    const m = map.current
    if (!m) return
    const apply = () => {
      for (const group of MAP_LAYER_GROUPS) {
        const visibility = hiddenLayers.has(group.key) ? 'none' : 'visible'
        for (const id of group.layers) {
          if (m.getLayer(id)) m.setLayoutProperty(id, 'visibility', visibility)
        }
      }
    }
    // Gerbangnya keberadaan salah satu lapisan gaya — BUKAN `isStyleLoaded()`, yang
    // sesaat berkata "belum" tiap kali basemap ditukar dan membuat saklar ini
    // tertunda ke pendengar yang tak pernah berbunyi lagi. Sama seperti efek basemap.
    if (m.getLayer('cable')) apply()
    else m.once('load', apply)
    localStorage.setItem(PREF_HIDDEN_LAYERS, JSON.stringify([...hiddenLayers]))
  }, [hiddenLayers, mapReady])

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

    // Klik joint box (mode idle) → panel kotak sambung: seberapa penuh traynya,
    // lalu jalan ke sambungan di dalamnya. Bukan endpoint GIS: yang menarik dari
    // sebuah joint box bukan "siapa di hilirnya" (ia meneruskan apa adanya),
    // melainkan isi kotaknya sendiri.
    instance.on('click', 'joint_box', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<JointBoxView>(`/api/joint-boxes/${id}`)
        .then((jb) => {
          clearPanels()
          setJointBox(jb)
          if (at) setMovable({ layer: 'joint_box', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail joint box'))
    })

    // Klik ODF (mode idle) → panel rak: berapa adapter yang masih kosong. Sama seperti
    // joint box, tak lewat endpoint GIS — rak tak punya "hilir" sendiri, ia cuma tempat
    // kabel luar berhenti dan patchcord melanjutkannya.
    instance.on('click', 'odf', (event) => {
      if (!acceptsClick()) return
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      const at = pointAt(feature)
      api
        .get<OdfView>(`/api/odfs/${id}`)
        .then((o) => {
          clearPanels()
          setOdf(o)
          if (at) setMovable({ layer: 'odf', id, code: codeOf(feature), ...at })
        })
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODF'))
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

    for (const layer of ['odp', 'odc', 'olt', 'cable', 'customer', 'site', 'joint_box', 'odf']) {
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
    // Permintaan tegas "tunjukkan yang ini" mengalahkan saklar lapisan yang tersimpan:
    // terbang ke titik yang lapisannya sedang dimatikan cuma memamerkan peta kosong.
    // Nama lapisan sorot memang sama dengan kunci kelompoknya — lihat [MAP_LAYER_GROUPS].
    setHiddenLayers((prev) => {
      if (!prev.has(layer)) return prev
      const next = new Set(prev)
      next.delete(layer)
      return next
    })
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
        case 'joint_box': {
          const jb = await api.get<JointBoxView>(`/api/joint-boxes/${id}`)
          return { code: jb.code, apply: () => setJointBox(jb) }
        }
        case 'odf': {
          const o = await api.get<OdfView>(`/api/odfs/${id}`)
          return { code: o.code, apply: () => setOdf(o) }
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
    jointBox ||
    odf ||
    survey ||
    placeAt ||
    detailCustomerId ||
    detailOltId ||
    detailOdfId ||
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

  /**
   * "Bisa dipasang di sini?" dijawab di tempat yang ditanyakan: titik yang barusan
   * diklik kanan, tanpa mode berlangkah dua dan tanpa pindah halaman. Panelnya
   * menggantikan penghuni sisi kanan seperti panel lain — satu hal pada satu waktu.
   */
  const checkCapacityHere = async () => {
    if (!addMenu) return
    const { lng, lat } = addMenu
    tool.current?.cancel()
    clearPanels()
    setEditing(null)
    setAddMenu(null)
    try {
      const view = await api.get<SurveyCapacityView>(
        `/api/network/survey/capacity?longitude=${lng}&latitude=${lat}&radiusMeters=${SURVEY_RADIUS_M}`,
      )
      setSurvey(view)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengecek kapasitas di titik ini')
    }
  }

  /**
   * Dari baris kotak di panel survey ke kotak itu sendiri. Terbang lebih dulu —
   * gerak peta adalah tanda pertama bahwa barisnya bisa diklik — baru panelnya
   * menyusul, sama seperti alur "tunjukkan yang ini" dari halaman lain.
   */
  const openSurveyOdp = async (odpId: string, lng: number, lat: number) => {
    map.current?.flyTo({ center: [lng, lat], zoom: 17 })
    try {
      const view = await api.get<OdpInspection>(`/api/gis/odps/${odpId}`)
      clearPanels()
      setSelected(view)
      setMovable({ layer: 'odp', id: odpId, code: view.code, lng, lat })
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat kotak itu')
    }
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
    setJointBox(null)
    setOdf(null)
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
    // Kosong = server yang merakit kodenya dari kode kedua ujung, lengkap dengan
    // akhiran angka bila sudah ada yang memakainya.
    code?: string
    name: string
    coreCount: number
    // Jenis yang DIPILIH operator di panel — sama dengan tersirat dari sepasang
    // ujungnya, kecuali joint box → joint box yang jenisnya diwarisi kabel hulu.
    cableType: CableType
    // Feeder: PON port OLT sumber. Distribusi/drop: kaki/slot sumber.
    fromPonPortId?: string
    fromPortNumber?: number
    // Drop → pelanggan: ONU yang ditautkan ke slot ODP sumber (form.fromPortNumber).
    onuId?: string
    // Fisik jalur; installation null = belum disurvei (bukan ditebak "udara").
    installation: CableInstallation | null
    ownership: CableOwnership
  }) => {
    const route = tool.current?.route() ?? []
    const state = toolState
    if (!state?.from || !state?.to || !state.cableType) return
    const odpId = state.from.id
    try {
      const saved = await api.post<CableView>('/api/cables', {
        code: form.code,
        name: form.name,
        cableType: form.cableType,
        coreCount: form.coreCount,
        route: route.map(([longitude, latitude]) => ({ longitude, latitude })),
        fromKind: state.from.kind,
        fromId: state.from.id,
        toKind: state.to.kind,
        toId: state.to.id,
        fromPonPortId: form.fromPonPortId,
        fromPortNumber: form.fromPortNumber,
        status: 'ACTIVE',
        installation: form.installation,
        ownership: form.ownership,
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
      // Kode hasilnya disebut, bukan sekadar "tersimpan": bila tadi bentrok, server
      // memberinya akhiran angka — dan yang harus ditulis di label selubung adalah
      // kode yang benar-benar tersimpan, bukan yang sempat terlihat di formulir.
      toast.success(`Kabel ${saved.code} tersimpan (${Math.round(state.lengthMeters)} m)${portNote}`)
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
        // Idem: fisik jalur bukan urusan mode edit geometri, tapi PUT mengganti
        // seluruh kabel — tak dikirim ulang berarti hasil survei ikut terhapus.
        installation: editing.installation,
        ownership: editing.ownership,
      })
      toast.success(`Jalur ${editing.code} diperbarui`)
      cancelTool()
      refreshTiles()
      void refreshImpacted()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperbarui jalur')
    }
  }

  /**
   * Setel cara pasang / kepemilikan langsung dari panel kabel. Dua fakta ini
   * biasanya baru diketahui SETELAH surveyor turun — jauh setelah jalurnya
   * digambar di peta. Memaksa operator masuk mode "Edit jalur" hanya untuk
   * mengubah dua dropdown berarti menyeret titik rute yang sudah benar ke dalam
   * risiko tergeser, jadi perubahannya berdiri sendiri di sini.
   */
  const saveCablePhysical = async (
    c: CableView,
    patch: { installation?: CableInstallation | null; ownership?: CableOwnership },
  ) => {
    try {
      const updated = await api.put<CableView>(`/api/cables/${c.id}`, {
        ...cableRequestBody(c),
        // `undefined` di patch = bidang itu tak disentuh; `null` pada installation
        // adalah nilai sah ("dikembalikan ke belum disurvei"), jadi tak boleh
        // diperlakukan sama dengan tak-disentuh oleh `??`.
        installation: patch.installation !== undefined ? patch.installation : c.installation,
        ownership: patch.ownership ?? c.ownership,
      })
      setCable(updated)
      toast.success(`Data fisik ${updated.name} diperbarui`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperbarui data fisik kabel')
    }
  }

  /**
   * Ganti kode kabel dari panelnya — jalan pulang bagi ruas yang terlanjur berkode
   * buruk, termasuk kabel lama yang kodenya masih UUID hasil generate versi dulu.
   * Tanpa ini, satu-satunya cara merapikan label adalah menghapus kabelnya dan
   * menggambar ulang — yang berarti ikut membuang seluruh baris meja sambung,
   * riwayat OTDR, dan core yang sudah terpakai pelanggan.
   */
  const renameCable = async (c: CableView, code: string) => {
    const next = code.trim().toUpperCase()
    if (next === '' || next === c.code) return
    try {
      const updated = await api.put<CableView>(`/api/cables/${c.id}`, { ...cableRequestBody(c), code: next })
      setCable(updated)
      refreshTiles()
      toast.success(`Kode kabel jadi ${updated.code}`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengganti kode kabel')
    }
  }

  /**
   * Kebalikan dari "ditinggal": rumah yang sama berlangganan lagi atas nama
   * penghuni baru, dan drop yang sudah tergantung di sana tinggal disambung
   * ulang. Tanpa jalan pulang ini, status ditinggal jadi lubang satu arah —
   * operator akhirnya menggambar kabel baru di atas kabel yang sudah ada, dan
   * peta pelan-pelan berisi dua serat untuk satu tiang.
   */
  const reuseCable = async (c: CableView) => {
    try {
      const updated = await api.put<CableView>(`/api/cables/${c.id}`, { ...cableRequestBody(c), status: 'ACTIVE' })
      setCable(updated)
      refreshTiles()
      toast.success(`${updated.name} kembali siap pakai`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengembalikan status kabel')
    }
  }

  /**
   * Baca ulang satu kabel setelah keadaannya berubah dari dalam panel (mis.
   * pelanggannya dicabut). Statusnya bisa berubah jadi "Ditinggal" dan pelanggan
   * yang tadinya terdampak kini tak lagi — dua hal yang tergambar di panel dan di
   * peta, jadi keduanya ikut disegarkan.
   */
  const reloadCable = async (id: string) => {
    try {
      setCable(await api.get<CableView>(`/api/cables/${id}`))
      refreshTiles()
      void refreshImpacted()
    } catch {
      /* panelnya sudah menampilkan hasil aksinya; gagal muat ulang tak fatal */
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
            {/* Legenda ikut menyusut saat lapisan disembunyikan: menjelaskan warna
                yang tak ada di layar cuma menambah yang harus dibaca. */}
            {heatmap ? <HeatmapLegend /> : <Legend hidden={hiddenLayers} />}
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
            hiddenLayers={hiddenLayers}
            onToggleLayer={(key, visible) =>
              setHiddenLayers((prev) => {
                const next = new Set(prev)
                if (visible) next.delete(key)
                else next.add(key)
                return next
              })
            }
            onShowAllLayers={() => setHiddenLayers(new Set())}
            can={can}
            onClose={() => setSettingsOpen(false)}
          />
        )}

        {/* Menu "tambah di sini" pada titik klik kanan / tahan-lama */}
        {addMenu && (
          <AddHereMenu
            at={addMenu}
            can={can}
            onPick={startPlaceAt}
            onSurvey={() => void checkCapacityHere()}
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
            toKind={toolState.to.kind}
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
        {survey && (
          <SurveyPanel
            survey={survey}
            onOpenOdp={(row) => void openSurveyOdp(row.odpId, row.location.longitude, row.location.latitude)}
            onClose={() => setSurvey(null)}
          />
        )}
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
        {jointBox && (
          <JointBoxPanel
            jointBox={jointBox}
            canView={can('network.jointbox.view')}
            canDelete={can('network.jointbox.delete')}
            canRelocate={can('network.jointbox.update')}
            onRelocate={startRelocate}
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
            onOpenDetail={() => setDetailNode({ kind: 'joint_box', id: jointBox.id, code: jointBox.code })}
            onDelete={() =>
              void deleteAsset('JOINT_BOX', jointBox.id, jointBox.code, () => setJointBox(null))
            }
            onClose={() => setJointBox(null)}
          />
        )}
        {odf && (
          <OdfPanel
            odf={odf}
            canView={can('network.odf.view')}
            canDelete={can('network.odf.delete')}
            canRelocate={can('network.odf.update')}
            onRelocate={startRelocate}
            onDrawCable={cableOrigin ? startDrawFrom : undefined}
            onOpenDetail={() => setDetailOdfId(odf.id)}
            onDelete={() => void deleteAsset('ODF', odf.id, odf.code, () => setOdf(null))}
            onClose={() => setOdf(null)}
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
            canReleaseDrop={can('network.splice.manage') && can('network.cable.update')}
            canViewOtdr={can('network.otdr.view')}
            canRecordOtdr={can('network.otdr.record')}
            otdrTests={otdrTests}
            onRecordOtdr={(form) => void recordOtdr(cable.id, form)}
            onDeleteOtdr={(testId) => void deleteOtdr(cable.id, testId)}
            onFocusOtdr={focusOtdr}
            onEdit={() => startEdit(cable)}
            onDelete={() => void deleteCable(cable)}
            onSimulate={() => void simulateCut(cable)}
            onReleased={() => void reloadCable(cable.id)}
            onReuse={() => void reuseCable(cable)}
            onPhysicalChange={(patch) => void saveCablePhysical(cable, patch)}
            onRename={(code) => void renameCable(cable, code)}
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

      {/* Detail ODF — sama persis dengan yang dibuka dari tab Inventory. Tak ikut blade
          `detailNode` di bawah karena rak bukan simpul akses: bentuk datanya beda (POP
          induk & port dua sisi, bukan alamat & rasio splitter). */}
      <Blade
        open={detailOdfId != null}
        title="Detail ODF"
        size="full"
        className="blade-detail"
        onClose={() => setDetailOdfId(null)}
      >
        {detailOdfId && (
          <OdfDetail
            key={detailOdfId}
            odfId={detailOdfId}
            onChanged={() => {
              refreshTiles()
              // Panel rak di belakang blade ditarik ulang supaya nama/jumlah portnya
              // tak tertinggal versi lama tepat di sebelah detail yang sudah benar.
              api
                .get<OdfView>(`/api/odfs/${detailOdfId}`)
                .then(setOdf)
                .catch(() => {
                  /* panelnya cuma basi, bukan rusak */
                })
            }}
            onDeleted={() => {
              setDetailOdfId(null)
              setOdf(null)
              refreshTiles()
            }}
            // Petanya sudah terbentang di belakang, penandanya pun tersorot.
            onShowOnMap={() => setDetailOdfId(null)}
          />
        )}
      </Blade>

      {/* Detail ODC/ODP — panel yang sama dengan tab Inventory, termasuk tombol Edit.
          Sebelumnya menyunting nama/kapasitas sebuah ODP berarti meninggalkan peta,
          mencarinya lagi di daftar, lalu kembali; sekarang cukup di tempat. */}
      <Blade
        open={detailNode != null}
        title={detailNode ? `Detail ${NODE_KIND_LABEL[detailNode.kind]}` : ''}
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
              // Panel inspeksi di belakangnya ikut ditutup — membiarkannya memajang
              // simpul yang barusan lenyap dari peta lebih membingungkan daripada
              // layar kosong.
              if (detailNode.kind === 'odc') setBlast(null)
              else if (detailNode.kind === 'joint_box') setJointBox(null)
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
  hiddenLayers,
  onToggleLayer,
  onShowAllLayers,
  can,
  onClose,
}: {
  basemap: BasemapMode
  onBasemap: (mode: BasemapMode) => void
  heatmap: boolean
  onHeatmap: (on: boolean) => void
  canHeatmap: boolean
  showLegend: boolean
  onShowLegend: (on: boolean) => void
  hiddenLayers: Set<string>
  onToggleLayer: (key: string, visible: boolean) => void
  onShowAllLayers: () => void
  can: (permission: string) => boolean
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const groups = MAP_LAYER_GROUPS.filter((g) => !g.perm || can(g.perm))
  const anyHidden = groups.some((g) => hiddenLayers.has(g.key))

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

        {/* Saklar lapisan. Yang tak berizin dilihat tak usah ditawarkan mati-hidupnya —
            operator akan bertanya-tanya kenapa mencentangnya tak memunculkan apa pun. */}
        {groups.length > 0 && (
          <section className="stack" style={{ gap: '0.4rem' }}>
            <div className="spread">
              <h4 className="map-settings-title" style={{ margin: 0 }}>Lapisan</h4>
              {anyHidden && (
                <Button variant="subtle" size="small" onClick={onShowAllLayers}>
                  Tampilkan semua
                </Button>
              )}
            </div>
            {groups.map((group) => (
              <Checkbox
                key={group.key}
                checked={!hiddenLayers.has(group.key)}
                onChange={(_, data) => onToggleLayer(group.key, !!data.checked)}
                label={
                  <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
                    <span
                      aria-hidden="true"
                      style={
                        group.color
                          ? { width: 10, height: 10, borderRadius: '50%', background: group.color, display: 'inline-block' }
                          : // Kabel: contoh berbentuk garis, sebab warnanya berganti
                            // menurut jenis kabelnya (lihat [MAP_LAYER_GROUPS]).
                            { width: 10, height: 2, borderRadius: 999, background: '#7c8aa5', display: 'inline-block' }
                      }
                    />
                    {group.label}
                  </span>
                }
              />
            ))}
            <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>
              Lapisan yang dimatikan tak bisa diklik maupun dijadikan ujung kabel — berguna saat
              titik-titik di satu POP saling menutupi.
            </p>
          </section>
        )}

        <section className="stack" style={{ gap: '0.4rem' }}>
          <h4 className="map-settings-title">Petunjuk</h4>
          <p className="muted" style={{ margin: 0, fontSize: '0.78rem', lineHeight: 1.45 }}>
            <strong>Klik kanan</strong> (atau tahan di layar sentuh) pada peta untuk menambah site, OLT, ODF,
            ODC, ODP, joint box, atau menaruh pelanggan yang belum berkoordinat.
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
  onSurvey,
  onClose,
}: {
  at: { lng: number; lat: number; x: number; y: number }
  can: (perm: string) => boolean
  onPick: (kind: AssetKind | 'CUSTOMER') => void
  onSurvey: () => void
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
  // Mengecek kapasitas tidak mengubah apa pun, jadi izinnya cukup "lihat ODP" —
  // dan justru orang yang tak boleh menambah aset (sales) yang paling sering
  // menanyakannya.
  const canSurvey = can('network.odp.view')
  if (assets.length === 0 && !canPlaceCustomer && !canSurvey) return null

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
      {canSurvey && (
        <button
          type="button"
          className="map-menu-item"
          role="menuitem"
          onClick={onSurvey}
          title="Kotak siap pakai & core menganggur di sekitar titik ini"
        >
          <IconCrosshair size={15} /> Cek kapasitas di sini
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

/* ---------- Primitif blade panel peta ----------
   Semua panel peta memakai kerangka yang sama — kepala lengket, command bar datar,
   badan berisi daftar properti "Essentials" — supaya klik ODP, OLT, ODC, site, atau
   pelanggan menghasilkan bentuk yang seragam, persis blade Azure Portal. */

/**
 * Panel cek kapasitas untuk survey.
 *
 * Susunannya mengikuti urutan orang mengambil keputusan di lapangan, bukan
 * urutan data di server: kalimat kesimpulan dulu (itu yang diucapkan ke calon
 * pelanggan), lalu kotak yang siap pakai, baru selubung yang lewat sebagai jalan
 * keluar kalau semua kotak penuh. Angka detail — sisa port, sisa kaki splitter,
 * nomor core kosong — ada di bawahnya untuk yang mau memeriksa.
 */
function SurveyPanel({
  survey,
  onOpenOdp,
  onClose,
}: {
  survey: SurveyCapacityView
  onOpenOdp: (row: SurveyOdp) => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Cek kapasitas"
        subtitle={`${survey.location.latitude.toFixed(6)}, ${survey.location.longitude.toFixed(6)} · radius ${formatLength(survey.radiusMeters)}`}
        onClose={onClose}
      />

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={survey.serviceable ? 'success' : survey.cables.length > 0 ? 'warning' : 'error'}>
          <MessageBarBody>{survey.verdict}</MessageBarBody>
        </MessageBar>

        {survey.odps.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Kotak dalam jangkauan ({survey.odps.length})</p>
            {survey.odps.map((o) => (
              <button
                key={o.odpId}
                type="button"
                className="card clickable"
                style={{ textAlign: 'left', width: '100%', padding: '0.55rem 0.7rem' }}
                onClick={() => onOpenOdp(o)}
              >
                <div className="spread" style={{ gap: '0.5rem' }}>
                  <span style={{ fontWeight: 600 }}>{o.code}</span>
                  <span className="tnum muted">{formatLength(o.distanceMeters)}</span>
                </div>
                <div className="row wrap" style={{ gap: '0.35rem', marginTop: '0.3rem' }}>
                  <span
                    className="badge"
                    style={{
                      color: o.ready ? 'var(--good-ink)' : 'var(--warning-ink)',
                      borderColor: o.ready ? 'var(--good-ink)' : 'var(--warning-ink)',
                    }}
                  >
                    {o.ready ? 'siap pakai' : 'belum bisa'}
                  </span>
                  <span className="muted tnum">
                    {o.freePorts}/{o.capacity} port kosong
                  </span>
                  {o.splitterLegs > 0 && (
                    <span className="muted tnum">
                      · {o.freeLegs}/{o.splitterLegs} kaki splitter
                    </span>
                  )}
                </div>
                {o.note && (
                  <p className="dim" style={{ margin: '0.3rem 0 0', fontSize: '0.72rem', lineHeight: 1.35 }}>
                    {o.note}
                  </p>
                )}
              </button>
            ))}
          </div>
        )}

        {survey.cables.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Selubung yang lewat ({survey.cables.length})</p>
            {survey.cables.map((c) => (
              <div key={c.cableId} className="card" style={{ padding: '0.55rem 0.7rem' }}>
                <div className="spread" style={{ gap: '0.5rem' }}>
                  <span style={{ fontWeight: 600 }}>{c.code}</span>
                  <span className="tnum muted">{formatLength(c.distanceMeters)}</span>
                </div>
                <div className="row wrap" style={{ gap: '0.35rem', marginTop: '0.3rem' }}>
                  <span className="badge">{TYPE_LABEL[c.cableType]}</span>
                  <span className="muted tnum">
                    {c.freeCores}/{c.coreCount} core menganggur
                  </span>
                </div>
                <p className="dim" style={{ margin: '0.3rem 0 0', fontSize: '0.72rem', lineHeight: 1.35 }}>
                  Kupas di {formatLength(c.tapDistanceMeters)} dari ujung awal kabel · core kosong{' '}
                  {c.freeCoreNumbers.join(', ')}
                  {c.freeCores > c.freeCoreNumbers.length && ', …'}
                </p>
              </div>
            ))}
          </div>
        )}

        {survey.warnings.map((w) => (
          <p key={w} className="dim" style={{ margin: 0, fontSize: '0.72rem', lineHeight: 1.35 }}>
            {w}
          </p>
        ))}
      </div>
    </aside>
  )
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

/** Vendor OLT yang didukung — selaras dengan daftar di halaman Inventaris. */
const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER']

/**
 * Form isian perangkat titik baru, muncul setelah lokasi diklik di peta. Field
 * menyesuaikan jenis: Site cukup alamat, ODC/ODP butuh rasio splitter & kapasitas,
 * joint box butuh jumlah tray & kapasitas sambungan (di dalamnya tak ada splitter),
 * ODF butuh POP induk & jumlah port (rak tak punya alamat sendiri — alamatnya POP-nya).
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
  // Joint box: 2 tray × 12 sambungan = 24, ukuran closure inline yang paling lazim
  // dipakai untuk menyambung haspel di lapangan.
  const [trayCount, setTrayCount] = useState(2)
  const [capacity, setCapacity] = useState(kind === 'ODP' ? 8 : kind === 'JOINT_BOX' ? 24 : 64)
  // ODF: 24 port = satu panel 1U penuh, ukuran rak POP kecil yang paling lazim.
  const [portCount, setPortCount] = useState(24)
  // OLT & ODF: site induk (wajib), lalu identitas perangkat & kesiapan SNMP (OLT saja).
  const [siteId, setSiteId] = useState('')
  const [sites, setSites] = useState<SiteView[]>([])
  const [vendor, setVendor] = useState('ZTE')
  const [model, setModel] = useState('')
  const [managementIp, setManagementIp] = useState('')
  const [snmpCommunity, setSnmpCommunity] = useState('')
  const [snmpPort, setSnmpPort] = useState('161')

  // Daftar site untuk memilih tempat berdirinya OLT/ODF. Wajib dipilih sebelum simpan:
  // keduanya perangkat DALAM ruangan — mereka selalu berdiri di dalam sebuah POP.
  useEffect(() => {
    if (kind !== 'OLT' && kind !== 'ODF') return
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
    // Rak tak beralamat sendiri: ia berdiri di dalam POP, dan alamat POP itulah
    // alamatnya. Yang menentukan ukurannya jumlah port — tiap port berkepala dua.
    if (kind === 'ODF') {
      base.siteId = siteId
      base.portCount = portCount
      onSave(base)
      return
    }
    if (address.trim()) base.address = address.trim()
    if (kind === 'SITE') {
      onSave(base)
      return
    }
    // Joint box tak berisi splitter: ukurannya tray & jumlah sambungan yang muat.
    if (kind === 'JOINT_BOX') {
      base.trayCount = trayCount
      base.capacity = capacity
      onSave(base)
      return
    }
    // Kosong = kabinet tanpa splitter (cross-connect), bukan isian yang terlewat.
    base.splitterRatio = splitterRatio || null
    base.capacity = capacity
    onSave(base)
  }

  // OLT & ODF wajib pilih site; aset lain hanya butuh kode + nama.
  const needsSite = kind === 'OLT' || kind === 'ODF'
  const canSubmit = code.trim() !== '' && name.trim() !== '' && (!needsSite || siteId !== '')

  return (
    <aside className="map-panel blade">
      <BladeHead
        title={`${meta.label} baru`}
        subtitle={`${lat.toFixed(6)}, ${lng.toFixed(6)} · seret pin untuk menggeser`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
        <TextField
          label="Kode"
          value={code}
          onChange={(_, data) => setCode(data.value)}
          placeholder={kind === 'JOINT_BOX' ? 'JB-001' : `${kind}-001`}
        />
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
        {kind === 'ODF' && (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Rak terminasi di dalam POP: tempat kabel luar BERHENTI. Seratnya dilas ke pigtail
              di sisi belakang port, lalu patchcord dari sisi depannya yang mencolok ke port PON —
              jadi kabel lapangan tak pernah menempel langsung ke badan OLT.
            </p>
            <SelectField label="POP induk" value={siteId} onChange={(_, data) => setSiteId(data.value)}>
              <option value="">— pilih POP —</option>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} — {s.name}
                </option>
              ))}
            </SelectField>
            <TextField
              label="Jumlah port"
              type="number"
              min={1}
              max={1152}
              value={String(portCount)}
              onChange={(_, data) => setPortCount(Number(data.value))}
            />
          </>
        )}
        {kind !== 'SITE' && kind !== 'OLT' && kind !== 'ODF' && (
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
        {kind === 'JOINT_BOX' && (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Kotak sambung: tempat dua haspel kabel bertemu, jalur bercabang di persimpangan,
              atau kabel putus disambung darurat. Tak ada splitter di dalamnya — serat masuk
              disambung langsung ke serat keluar.
            </p>
            <div className="row" style={{ gap: '0.5rem' }}>
              <TextField
                label="Jumlah tray"
                type="number"
                min={1}
                max={64}
                value={String(trayCount)}
                onChange={(_, data) => setTrayCount(Number(data.value))}
                style={{ flex: 1 }}
              />
              <TextField
                label="Kapasitas sambungan"
                type="number"
                min={1}
                max={1536}
                value={String(capacity)}
                onChange={(_, data) => setCapacity(Number(data.value))}
                style={{ flex: 1 }}
              />
            </div>
          </>
        )}
        {(kind === 'ODC' || kind === 'ODP') && (
          <div className="row" style={{ gap: '0.5rem' }}>
            <SelectField
              label="Splitter"
              value={splitterRatio}
              onChange={(_, data) => setSplitterRatio(data.value)}
              style={{ flex: 1 }}
            >
              {/* Kabinet cross-connect memang tak berisi splitter — dan modul kedua,
                  ketiga, dst. ditambahkan belakangan dari panel "Isi kabinet". */}
              <option value="">Tanpa splitter</option>
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

/**
 * Kartu legenda kiri-bawah. `hidden` = kelompok lapisan yang sedang dimatikan dari
 * laci setelan; barisnya ikut hilang, sebab menjelaskan warna yang tak ada di layar
 * cuma menambah yang harus dibaca tanpa menambah yang bisa dilihat.
 */
function Legend({ hidden }: { hidden: Set<string> }) {
  const items = (
    [
      ['site', '#b47cff', 'Site/POP'],
      ['olt', OLT_COLOR, 'OLT'],
      ['odf', ODF_COLOR, 'ODF'],
      ['odc', '#22d3ee', 'ODC'],
      ['odp', '#fbbf24', 'ODP'],
      ['joint_box', JOINT_BOX_COLOR, 'Joint box'],
      ['customer', '#34d399', 'Pelanggan online'],
      ['customer', '#ff5470', 'ONU mati'],
      ['customer', '#8b95a7', 'Belum terpantau'],
    ] as Array<[string, string, string]>
  )
    .filter(([group]) => !hidden.has(group))
    .map(([, color, label]) => [color, label] as [string, string])
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
