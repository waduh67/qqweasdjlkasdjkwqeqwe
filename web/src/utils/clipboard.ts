/**
 * Salin teks ke papan klip, dengan jalur mundur untuk konteks non-secure.
 *
 * `navigator.clipboard` HANYA ada di secure context (https, atau localhost). Konsol ini
 * kerap dibuka lewat `http://` polos di atas IP VPS — di sana objeknya `undefined`, dan
 * pola `navigator.clipboard?.writeText(x).then(...)` gagal paling buruk: optional chaining
 * memutus SELURUH rantai, jadi tak ada yang tersalin, `.then` tak pernah jalan, dan
 * pengguna tak menerima toast sukses maupun error. Tombolnya diklik, lalu senyap.
 *
 * Maka: pakai API modern bila ada, jatuh ke `<textarea>` + `document.execCommand('copy')`
 * bila tidak, dan kembalikan `false` bila dua-duanya gagal — supaya pemanggil bisa
 * mengatakan yang sejujurnya lewat `toast.error`.
 */
export async function copyText(value: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // Izin ditolak atau dokumen tak fokus — masih ada kesempatan lewat execCommand.
    }
  }
  return copyViaTextarea(value)
}

/**
 * Jalur mundur warisan. `execCommand` sudah usang tapi masih didukung semua browser
 * arus utama, dan ia satu-satunya yang bekerja tanpa secure context.
 *
 * Textarea-nya harus benar-benar ada di dokumen dan terpilih agar perintahnya berlaku;
 * `position: fixed` di luar layar mencegah halaman melompat saat difokuskan.
 */
function copyViaTextarea(value: string): boolean {
  const area = document.createElement('textarea')
  area.value = value
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.select()
    area.setSelectionRange(0, value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
  }
}
