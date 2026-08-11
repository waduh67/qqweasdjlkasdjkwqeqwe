/**
 * Ukuran kotak/kabinet sebagai SATU pilihan, bukan dua isian terpisah.
 *
 * Kenapa: di lapangan tak ada yang menyebut rasio splitter dan jumlah port sebagai
 * dua keputusan. Yang dipesan ke toko dan yang tertulis di BOM adalah "ODP 8 port"
 * — splitter 1:8 di dalamnya sudah sepaket, karena kotak 8 lubang memang tak
 * berguna diisi modul lain. Meminta dua angka yang saling menentukan cuma
 * mengundang salah ketik, dan salah ketiknya mahal: kapasitas 16 di kotak
 * bersplitter 1:8 berarti 8 port hantu yang selamanya tampak "tersedia" di
 * heatmap padahal tak ada cahaya yang sampai ke sana.
 *
 * Yang jarang tetap harus mungkin, jadi tiap daftar ditutup [CUSTOM_SIZE]: kabinet
 * cross-connect, kotak yang splitternya menyusul, ODC 96-core berisi tiga modul
 * rasio beda — semua itu benda nyata, dan untuk mereka kedua isian mentah dibuka
 * kembali. Modul kedua dan seterusnya tetap diurus panel "Isi kabinet".
 *
 * Dipakai bersama form taruh-perangkat di peta dan form sunting simpul, supaya
 * kata-katanya sama persis di mana pun operator bertemu pertanyaan ini.
 */

/** Nilai pilihan "Atur sendiri" — membuka isian rasio & kapasitas apa adanya. */
export const CUSTOM_SIZE = 'custom'

/** Nilai pilihan kotak tanpa splitter; string tersendiri karena `null` tak bisa jadi nilai <option>. */
export const NO_SPLITTER_SIZE = 'none'

export interface ClosureSize {
  /** Nilai <option>. Sengaja string agar muat 'none' & 'custom' di daftar yang sama. */
  value: string
  label: string
  /** null = kotak tanpa splitter (serat lewat/di-cross-connect), bukan isian yang terlewat. */
  splitterRatio: string | null
  capacity: number
}

export interface JointBoxSize {
  value: string
  label: string
  trayCount: number
  capacity: number
}

/**
 * ODP: kotak terminasi di tiang/dinding. Kapasitas = jumlah lubang drop yang bisa
 * dijual, dan untuk kotak berisi satu modul angka itu memang sama dengan kaki
 * splitternya. 1:8 didahulukan karena itu ukuran yang paling sering dipasang di
 * perumahan — 1:16 baru masuk akal di gang padat yang rumahnya rapat.
 */
export const ODP_SIZES: ClosureSize[] = [
  { value: '1:8', label: '8 port · splitter 1:8', splitterRatio: '1:8', capacity: 8 },
  { value: '1:16', label: '16 port · splitter 1:16', splitterRatio: '1:16', capacity: 16 },
  { value: '1:4', label: '4 port · splitter 1:4', splitterRatio: '1:4', capacity: 4 },
  { value: NO_SPLITTER_SIZE, label: 'Tanpa splitter · 8 port lewatan', splitterRatio: null, capacity: 8 },
  { value: CUSTOM_SIZE, label: 'Atur sendiri…', splitterRatio: null, capacity: 8 },
]

/**
 * ODC: kabinet distribusi. Kapasitasnya bukan jumlah pelanggan melainkan jumlah
 * CABANG distribusi yang bisa berangkat dari sini — tiap cabang nanti berujung di
 * satu ODP yang punya splitternya sendiri. Karena itu rasio kabinet biasanya kecil
 * (1:4 atau 1:8): pemecahan besar dilakukan di ODP, dekat rumah, supaya redaman
 * tak habis di tengah jalan.
 */
export const ODC_SIZES: ClosureSize[] = [
  { value: '1:8', label: '8 cabang ke ODP · splitter 1:8', splitterRatio: '1:8', capacity: 8 },
  { value: '1:4', label: '4 cabang ke ODP · splitter 1:4', splitterRatio: '1:4', capacity: 4 },
  { value: '1:16', label: '16 cabang ke ODP · splitter 1:16', splitterRatio: '1:16', capacity: 16 },
  {
    value: NO_SPLITTER_SIZE,
    label: 'Tanpa splitter (cross-connect) · 24 cabang',
    splitterRatio: null,
    capacity: 24,
  },
  { value: CUSTOM_SIZE, label: 'Atur sendiri…', splitterRatio: null, capacity: 8 },
]

/**
 * Joint box: tak ada splitter di dalamnya, ukurannya tray. Satu tray standar
 * memuat 12 sambungan (satu tube berisi 12 serat) — itulah kenapa angka
 * kapasitasnya selalu kelipatan 12 dan kenapa operator tak perlu menghitungnya
 * sendiri.
 */
export const SPLICES_PER_TRAY = 12

export const JOINT_BOX_SIZES: JointBoxSize[] = [
  { value: '2', label: '2 tray · 24 sambungan', trayCount: 2, capacity: 24 },
  { value: '4', label: '4 tray · 48 sambungan', trayCount: 4, capacity: 48 },
  { value: '1', label: '1 tray · 12 sambungan', trayCount: 1, capacity: 12 },
  { value: '6', label: '6 tray · 72 sambungan', trayCount: 6, capacity: 72 },
  { value: CUSTOM_SIZE, label: 'Atur sendiri…', trayCount: 2, capacity: 24 },
]

/**
 * Menebak pilihan mana yang mewakili sebuah kotak yang SUDAH ada. Dipakai form
 * sunting: kotak yang ukurannya lazim tampil sebagai pilihan biasa, yang tidak
 * lazim jatuh ke "Atur sendiri" apa adanya — bukan dipaksa mendekati pilihan
 * terdekat, karena membulatkan kapasitas kotak yang sudah terpasang berarti
 * mengarang port yang tak ada badannya.
 *
 * @param ratio ringkasan rasio kotak; kosong/"—" berarti tanpa splitter.
 */
export function matchClosureSize(sizes: ClosureSize[], ratio: string, capacity: number): string {
  const normalized = ratio.trim() === '—' ? '' : ratio.trim()
  const hit = sizes.find(
    (s) =>
      s.value !== CUSTOM_SIZE &&
      (s.splitterRatio ?? '') === normalized &&
      s.capacity === capacity,
  )
  return hit?.value ?? CUSTOM_SIZE
}

/** Padanan [matchClosureSize] untuk joint box — yang menentukan tray, bukan splitter. */
export function matchJointBoxSize(trayCount: number, capacity: number): string {
  const hit = JOINT_BOX_SIZES.find(
    (s) => s.value !== CUSTOM_SIZE && s.trayCount === trayCount && s.capacity === capacity,
  )
  return hit?.value ?? CUSTOM_SIZE
}
