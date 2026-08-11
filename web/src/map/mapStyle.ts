/**
 * Rupa peta: basemap, palet warna, dan susunan lapisan MapLibre.
 *
 * Dipisah dari halamannya bukan sekadar demi memendekkan berkas. Yang di sini
 * adalah keputusan TAMPILAN yang berumur panjang — rona tiap jenis aset, urutan
 * siapa digambar di atas siapa, mulai zoom berapa label muncul — dan tiap kali
 * ia bersisian dengan logika klik/ambil-data, keduanya sama-sama sulit dibaca.
 * Halaman peta kini cukup memakai gaya ini; menyetel warna tak perlu menyentuh
 * satu baris pun kode interaksi.
 */

// Atribusi gabungan semua penyedia basemap (Carto & Esri) karena pengguna bisa
// berpindah mode; tetap ditampilkan apa pun mode yang aktif.
const MAP_ATTRIBUTION =
  '&copy; Kontributor OpenStreetMap &copy; CARTO &middot; Citra satelit &copy; Esri'

/** Pusat awal: Bekasi, sekadar titik berangkat sebelum data pertama masuk. */
export const INITIAL_CENTER: [number, number] = [106.995, -6.243]

/**
 * Mode basemap yang bisa dipilih pengguna. Semua tile raster KEYLESS — memadai untuk
 * pengembangan; untuk PRODUKSI pindah ke penyedia berlangganan / tile sendiri (Carto
 * & Esri membatasi pemakaian komersial). Ganti mode cukup menukar tile & opacity
 * sumber raster `basemap` via `setTiles`, jadi tak menyentuh layer overlay vektor.
 * Catatan skema ubin: Carto memakai {z}/{x}/{y} (XYZ standar), Esri {z}/{y}/{x}.
 */
export type BasemapMode = 'streets' | 'satellite' | 'dark'

export const BASEMAPS: Record<BasemapMode, { label: string; tiles: string[]; opacity: number }> = {
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
export const BASEMAP_ORDER: BasemapMode[] = ['streets', 'satellite', 'dark']

/** Untuk apa tiap tema dipakai — supaya pilihannya soal pekerjaan, bukan selera. */
export const BASEMAP_HINTS: Record<BasemapMode, string> = {
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
export const PREF_BASEMAP = 'ftth.map.basemap'
export const PREF_LEGEND = 'ftth.map.legend'
/**
 * Yang disimpan lapisan yang DISEMBUNYIKAN, bukan yang ditampilkan — dan itu bukan
 * selera penulisan. Kalau daftar "tampil" yang disimpan, setiap jenis simpul baru
 * (ODF & joint box baru saja lahir, dan akan ada lagi) mendarat sebagai tak-tercentang
 * di layar orang yang pernah menyentuh laci ini: fitur baru yang tak kelihatan sama
 * sekali. Menyimpan yang disembunyikan membuat bawaannya selalu "semua tampak".
 */
export const PREF_HIDDEN_LAYERS = 'ftth.map.hidden-layers'

export function savedBasemap(): BasemapMode {
  const saved = localStorage.getItem(PREF_BASEMAP)
  return BASEMAP_ORDER.includes(saved as BasemapMode) ? (saved as BasemapMode) : DEFAULT_BASEMAP
}

export function savedHiddenLayers(): Set<string> {
  try {
    const raw = JSON.parse(localStorage.getItem(PREF_HIDDEN_LAYERS) ?? '[]')
    return new Set(Array.isArray(raw) ? raw.filter((k): k is string => typeof k === 'string') : [])
  } catch {
    return new Set()
  }
}

export const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

/**
 * Warna kabel per tipe, dipakai bersama oleh lapisan glow & garis inti supaya
 * konsisten. Nada terang agar bercahaya di atas basemap gelap.
 *
 * Backbone memakai indigo pekat — serumpun dengan ungu feeder karena keduanya
 * memang sisi hulu yang tak membawa pelanggan, tapi lebih gelap supaya tulang
 * punggung tetap terbaca sebagai lapisan tersendiri saat keduanya bersisian.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const CABLE_COLOR: any = [
  'match',
  ['get', 'cable_type'],
  'BACKBONE',
  '#7c5cff',
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
export const SEVERITY_COLOR: any = ['match', ['get', 'severity'], 'CRITICAL', '#ff3b5c', 'WARNING', '#fbbf24', '#ff3b5c']

/** Warna marker OLT — biru, sengaja lepas dari cyan ODC & ungu site agar perangkat aktif menonjol. */
export const OLT_COLOR = '#4f9dff'

/**
 * Warna marker joint box — merah muda, satu-satunya rona yang belum dipakai jalur
 * utama (ungu site → biru OLT → cyan ODC → amber ODP → hijau pelanggan). Sengaja
 * lepas dari gradasi itu: joint box bukan tingkat baru dalam rantai, ia titik
 * sambung yang bisa menclok di tingkat mana saja.
 */
export const JOINT_BOX_COLOR = '#f472b6'

/**
 * Warna marker ODF — indigo, sengaja duduk PERSIS di antara ungu site dan biru OLT.
 * Itu memang tempatnya dalam cerita: rak berdiri di dalam POP dan jadi pendamping
 * pasif OLT, tempat kabel luar berhenti sebelum patchcord melanjutkannya ke perangkat.
 */
export const ODF_COLOR = '#818cf8'

/** Warna simpul terdampak (OLT/ODC/ODP/pelanggan) saat alarm hidup — merah/amber, sama palet kabel. */
export const NODE_CRITICAL_COLOR = '#ff3b5c'
export const NODE_WARNING_COLOR = '#fbbf24'

/**
 * Warna sorotan simulasi "kalau putus" — amber, sengaja beda dari merah alarm
 * hidup: yang ini hipotetis (belum terjadi), bukan gangguan nyata yang berjalan.
 */
export const WHATIF_COLOR = '#f59e0b'

/**
 * Warna titik perkiraan uji OTDR — magenta, sengaja lepas dari palet lain (merah
 * alarm, amber simulasi, gradasi heatmap, warna aset): penanda diagnostik yang
 * jelas "hasil ukur", bukan gangguan hidup maupun hipotesis.
 */
export const OTDR_COLOR = '#f472b6'

/**
 * Gradasi warna heatmap utilisasi port: hijau (lengang) → kuning → jingga →
 * merah (penuh). Diinterpolasi dari properti `util` (0–100) tiap titik ODP,
 * sehingga sekali pandang terlihat ODP mana yang butuh perluasan kapasitas.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const HEATMAP_COLOR: any = [
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
export const CUSTOMER_COLOR: any = [
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
export const DASH_SEQUENCE: number[][] = [
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
export const zoomWidth = (near: number, far: number): any => [
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
export const FUTURISTIC_STYLE: any = {
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
    // Joint box digambar lebih kecil dari ODP dan di bawahnya: ia perangkat PASIF
    // (tak ada splitter, tak ada port), jadi ia tak boleh menarik mata lebih dulu
    // daripada kotak yang benar-benar melayani pelanggan.
    ...glowCircle('joint_box', 'joint_box', JOINT_BOX_COLOR, 5),
    ...glowCircle('odp', 'odp', '#fbbf24', 6),
    ...glowCircle('odc', 'odc', '#22d3ee', 8),
    // Rak digambar tepat DI BAWAH OLT dan sedikit lebih kecil: keduanya berdiri di
    // titik yang nyaris sama (satu POP), dan yang dicari orang saat menyapu peta
    // adalah perangkat aktifnya — rak baru dicari setelah OLT-nya ketemu.
    ...glowCircle('odf', 'odf', ODF_COLOR, 7),
    ...glowCircle('olt', 'olt', OLT_COLOR, 9),
    ...glowCircle('site', 'site', '#b47cff', 10),
    {
      // Label joint box baru muncul sangat dekat (z16): kotak sambung padat di
      // sepanjang jalur panjang, dan menamai semuanya lebih awal cuma menutupi
      // ODP & pelanggan yang justru dicari orang.
      id: 'joint_box-label',
      type: 'symbol',
      source: 'ftth',
      'source-layer': 'joint_box',
      minzoom: 16,
      layout: { 'text-field': ['get', 'code'], 'text-size': 10, 'text-offset': [0, 1.4] },
      paint: { 'text-color': '#fbcfe8', 'text-halo-color': '#0a0e14', 'text-halo-width': 1.5 },
    },
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
      // Label rak menyusul label OLT (z14, bukan z13): saat keduanya bertumpuk di
      // satu POP, nama perangkatnya yang lebih dulu perlu terbaca.
      id: 'odf-label',
      type: 'symbol',
      source: 'ftth',
      'source-layer': 'odf',
      minzoom: 14,
      layout: { 'text-field': ['get', 'code'], 'text-size': 11, 'text-offset': [0, 1.5] },
      paint: { 'text-color': '#c7d2fe', 'text-halo-color': '#0a0e14', 'text-halo-width': 1.5 },
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
export const NODE_LAYERS: Array<{ id: string; base: any }> = [
  { id: 'customer', base: CUSTOMER_COLOR },
  { id: 'joint_box', base: JOINT_BOX_COLOR },
  { id: 'odp', base: '#fbbf24' },
  { id: 'odc', base: '#22d3ee' },
  { id: 'olt', base: OLT_COLOR },
  { id: 'site', base: '#b47cff' },
]

/**
 * Kelompok lapisan yang bisa disembunyikan sendiri-sendiri dari laci setelan.
 *
 * Peta sekarang menumpuk tujuh jenis simpul di atas satu sama lain, dan di POP yang
 * padat titik-titiknya memang saling menutupi — rak, OLT, dan site berdiri di alamat
 * yang sama persis. Saklar ini bukan hiasan: ia satu-satunya cara melihat apa yang
 * ada DI BAWAH tumpukan itu tanpa menggeser apa pun.
 *
 * Satu baris = satu kelompok, sebab lingkaran + halo + labelnya sebenarnya tiga
 * lapisan MapLibre yang harus hidup-mati berbarengan; memisahkannya cuma melahirkan
 * keadaan aneh (label melayang tanpa titiknya). Urutannya hulu→hilir seperti alur
 * jaringannya, dengan kabel di paling bawah karena ia latar, bukan simpul.
 */
export const MAP_LAYER_GROUPS: Array<{ key: string; label: string; color?: string; layers: string[]; perm?: string }> = [
  { key: 'site', label: 'Site / POP', color: '#b47cff', layers: ['site', 'site-glow'], perm: 'network.site.view' },
  { key: 'olt', label: 'OLT', color: OLT_COLOR, layers: ['olt', 'olt-glow', 'olt-label'], perm: 'network.olt.view' },
  { key: 'odf', label: 'ODF', color: ODF_COLOR, layers: ['odf', 'odf-glow', 'odf-label'], perm: 'network.odf.view' },
  { key: 'odc', label: 'ODC', color: '#22d3ee', layers: ['odc', 'odc-glow'], perm: 'network.odc.view' },
  { key: 'odp', label: 'ODP', color: '#fbbf24', layers: ['odp', 'odp-glow', 'odp-label'], perm: 'network.odp.view' },
  {
    key: 'joint_box',
    label: 'Joint box',
    color: JOINT_BOX_COLOR,
    layers: ['joint_box', 'joint_box-glow', 'joint_box-label'],
    perm: 'network.jointbox.view',
  },
  {
    key: 'customer',
    label: 'Pelanggan',
    // Hijau "online" — warna markernya sesungguhnya berganti menurut status ONU,
    // dan yang dipakai di sini keadaan sehatnya, sama seperti di legenda.
    color: '#34d399',
    layers: ['customer', 'customer-glow'],
    perm: 'customer.customer.view',
  },
  // Tanpa warna, sengaja: kabel berganti rona menurut jenisnya (ungu feeder, cyan
  // distribusi, hijau drop), jadi satu bulatan apa pun warnanya akan berbohong.
  // Barisnya memakai contoh berbentuk garis — lihat [LayerToggleRow].
  //
  // Juga tak berizin sendiri: yang boleh membuka peta pasti boleh melihat jalurnya
  // — tanpa garis, sekumpulan titik tak bercerita apa-apa.
  { key: 'cable', label: 'Kabel', layers: ['cable', 'cable-glow', 'cable-flow'] },
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
export function watermarkTile(label: string): string {
  const text = escapeXml(label)
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="340" height="180">` +
    `<text x="20" y="150" transform="rotate(-28 20 150)" ` +
    `font-family="system-ui,-apple-system,sans-serif" font-size="13" font-weight="600" ` +
    `fill="rgba(255,255,255,0.07)" letter-spacing="0.5">${text}</text></svg>`
  return `url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}")`
}
