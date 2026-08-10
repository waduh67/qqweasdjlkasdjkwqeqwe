/**
 * Jarak waktu dalam bahasa manusia ("5 menit lalu").
 *
 * Angka jam dinding ("14:03") memaksa pembaca menghitung sendiri seberapa lama sesuatu
 * sudah menggantung — dan di antrean kerja, justru itulah satu-satunya yang ingin ia tahu.
 * Berhenti di satuan hari: apa pun yang lebih tua dari itu sudah bukan lagi "baru saja".
 */
export function timeAgo(iso: string): string {
  const secs = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (secs < 60) return 'baru saja'
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins} menit lalu`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} jam lalu`
  return `${Math.floor(hours / 24)} hari lalu`
}
