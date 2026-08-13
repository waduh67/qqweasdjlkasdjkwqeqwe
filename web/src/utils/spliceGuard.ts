/**
 * Peringatan saat kedua sisi meja kerja menunjuk kabel yang SAMA.
 *
 * Server menolak pasangan seperti itu — menyambung core 1 ke core 2 sehelai
 * kabel membuat cahaya berbalik pulang ke tempat asalnya — tapi ditolak sesudah
 * menekan tombol berarti orang di lapangan sudah terlanjur memilih core dan
 * memasang alat las. Karena itu alasannya disebut lebih dulu, di layar, sebelum
 * tombolnya bisa ditekan.
 *
 * Dua kalimat berbeda karena penyebabnya berbeda. Kotak yang cuma disinggahi
 * SATU kabel bukan sedang salah pilih: memang belum ada apa pun untuk disambung,
 * dan yang kurang kabel lanjutannya — entah karena kabelnya belum ditarik, entah
 * karena kabel yang sudah ada di dalam kotak belum tercatat singgahannya.
 *
 * @param cableCode kode kabel yang muncul di kedua sisi; null = sisi-sisinya beda
 * @param onlyCableHere tak ada kabel/tujuan lain yang tercatat di kotak ini
 * @returns kalimat peringatan, atau null bila pasangannya memang sah
 */
export function sameCableWarning(cableCode: string | null, onlyCableHere: boolean): string | null {
  if (cableCode == null) return null
  return onlyCableHere
    ? `Kotak ini baru disinggahi satu kabel (${cableCode}), jadi belum ada yang bisa disambung. ` +
        'Isi sebuah kotak sambung adalah pertemuan kabel yang DATANG dengan kabel LANJUTAN — ' +
        'catat singgahan kabel lanjutannya, atau tarik dulu kabelnya di peta.'
    : `Kedua sisi menunjuk kabel yang sama (${cableCode}). Menyambung dua core sehelai kabel ` +
        'membuat cahaya berbalik pulang ke tempat asalnya: dua core habis, tak ada yang terlayani. ' +
        'Ganti salah satu sisi ke kabel atau tujuan lain.'
}

/** Satu ujung yang sedang dinilai — seperlunya untuk tahu ke mana cahayanya pergi. */
export interface SpliceEnd {
  kind: string
  nodeId: string | null
  portNumber: number | null
  /** Kabel pemilik core ini; null untuk kaki/input/port. */
  cableId: string | null
  cableCode: string | null
}

/** Sepasang ujung yang SUDAH tersambung di kotak yang sedang dibuka. */
export interface WiredPair {
  a: SpliceEnd
  b: SpliceEnd
}

const SPLITTER_IN = 'SPLITTER_IN'
const SPLITTER_OUT = 'SPLITTER_OUT'

/** Kabel yang seratnya sudah dilas ke titik [kind] milik splitter ini. */
function cablesWiredTo(wired: WiredPair[], splitterId: string, kind: string): Set<string> {
  const found = new Set<string>()
  for (const row of wired) {
    for (const [here, far] of [
      [row.a, row.b],
      [row.b, row.a],
    ] as const) {
      if (here.kind === kind && here.nodeId === splitterId && far.cableId != null) found.add(far.cableId)
    }
  }
  return found
}

/**
 * Peringatan untuk pasangan yang mustahil ada wujudnya di dalam splitter.
 *
 * Splitter cuma mengalirkan cahaya satu arah: masuk lewat input, keluar terbagi
 * di kaki. Dari situ empat bentuk gugur dengan sendirinya — kaki ↔ kaki (tak ada
 * yang menyuapi), input ↔ input (tak ada yang disuapi), input ↔ kaki modul yang
 * sama (berputar di tempat), dan kaki ↔ core kabel yang justru MENYUAPI input
 * modul itu.
 *
 * Yang terakhir adalah kekeliruan paling sering di meja ODP dan yang paling
 * sulit dilihat: kabel distribusi masuk, core 1 ke input — benar — lalu core
 * tetangganya di selubung yang sama ke salah satu kaki. Alat las tak protes,
 * tapi cahaya yang sudah dibagi delapan langsung pulang ke ODC lewat serat
 * sebelahnya. Yang menunggu di ujung kaki adalah kabel DROP ke rumah pelanggan.
 *
 * Server menolak semuanya; di sini disebutkan lebih dulu supaya orang di lapangan
 * tak terlanjur memilih core dan memasang alat las — sama alasannya dengan
 * [sameCableWarning].
 *
 * @param wired sambungan yang sudah ada di kotak ini; dari situ ketahuan kabel
 *   mana yang sedang menyuapi input tiap splitter
 * @returns kalimat peringatan, atau null bila pasangannya memang punya wujud
 */
export function impossibleSpliceWarning(
  a: SpliceEnd | null,
  b: SpliceEnd | null,
  wired: WiredPair[],
): string | null {
  if (a == null || b == null) return null
  for (const [point, other] of [
    [a, b],
    [b, a],
  ] as const) {
    if (point.kind === SPLITTER_OUT && other.kind === SPLITTER_OUT) {
      return (
        'Dua kaki splitter tak bisa dikawinkan — keduanya KELUARAN, tak ada yang menyuapi yang lain. ' +
        'Kaki splitter disambung ke core kabel drop menuju rumah pelanggan, atau ke INPUT splitter ' +
        'lain bila splitternya bertingkat.'
      )
    }
    if (point.kind === SPLITTER_IN && other.kind === SPLITTER_IN) {
      return (
        'Dua input splitter tak bisa dikawinkan — keduanya menunggu disuapi. Input splitter ' +
        'disambung ke core kabel yang datang dari ODC/OLT, atau ke kaki splitter di tingkat atasnya.'
      )
    }
    if (point.kind === SPLITTER_IN && other.kind === SPLITTER_OUT && point.nodeId === other.nodeId) {
      return (
        'Input dan kaki splitter yang SAMA — cahayanya berputar di dalam modulnya sendiri dan tak ' +
        'pernah sampai ke mana pun. Splitter bertingkat memakai input modul LAIN.'
      )
    }
    if (
      (point.kind === SPLITTER_IN || point.kind === SPLITTER_OUT) &&
      point.nodeId != null &&
      other.cableId != null
    ) {
      const clashing = point.kind === SPLITTER_OUT ? SPLITTER_IN : SPLITTER_OUT
      if (!cablesWiredTo(wired, point.nodeId, clashing).has(other.cableId)) continue
      const cable = other.cableCode ?? 'kabel itu'
      return point.kind === SPLITTER_OUT
        ? `Kaki ${point.portNumber} diarahkan balik ke ${cable} — kabel yang justru MENYUAPI input ` +
            'splitter ini. Cahaya yang sudah dibagi akan pulang lewat serat tetangganya di selubung ' +
            'yang sama dan tak ada pelanggan yang terlayani. Yang disambung ke kaki adalah core ' +
            'kabel DROP menuju rumah pelanggan.'
        : `${cable} sudah dipakai salah satu kaki splitter ini, jadi menjadikannya input berarti ` +
            'keluarannya pulang ke masukannya sendiri. Lepas dulu sambungan kaki yang salah itu, ' +
            'atau suapi inputnya dari core kabel yang datang dari arah ODC/OLT.'
    }
  }
  return null
}
