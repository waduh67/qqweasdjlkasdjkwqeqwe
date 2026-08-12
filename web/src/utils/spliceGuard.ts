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
