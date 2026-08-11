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
  SurveyCapacityView,
  UnmappedCustomer,
  UtilizationHeatmap,
} from '../api/network'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Button } from '@/components/atoms'
import {
  AccessNodeDetail,
  Blade,
  OdfDetail,
  type AccessNodeKind,
} from '@/components/organisms'
import {
  AddHereMenu,
  BlastRadiusPanel,
  CableCutPanel,
  CablePanel,
  CustomerTracePanel,
  HeatmapLegend,
  Legend,
  MapSettingsDrawer,
  MapToolbar,
  JointBoxPanel,
  OdfPanel,
  OdpPanel,
  OltPanel,
  PlaceAssetForm,
  PlaceCustomerForm,
  SaveCablePanel,
  SitePanel,
  SurveyPanel,
} from '@/components/organisms/map'
import { CustomerDetailBlade } from './CustomerDetailPage'
import { OltDetail } from './OltDetailPage'
import type { MapFocus } from '@/map/mapFocus'
import {
  BASEMAPS,
  DASH_SEQUENCE,
  FUTURISTIC_STYLE,
  HEATMAP_COLOR,
  INITIAL_CENTER,
  MAP_LAYER_GROUPS,
  NODE_CRITICAL_COLOR,
  NODE_LAYERS,
  NODE_WARNING_COLOR,
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
  SURVEY_RADIUS_M,
  type AssetKind,
} from '@/map/mapAssets'
import {
  cableOriginOf,
  cableRequestBody,
  drawHint,
  formatLength,
} from '@/map/cableFormat'
import { traceVerdict } from '@/map/traceVerdict'
import { useToast } from '@/system'
import {
  IconCrosshair,
  IconRoute,
  IconSettings,
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
