/**
 * Simpan sebuah Blob sebagai berkas unduhan.
 *
 * Unduhan yang butuh header `Authorization` tak bisa dipicu dengan `<a href>` biasa — isinya
 * harus diambil dulu lewat fetch, lalu "diserahkan" ke browser lewat object URL. Tarian
 * membuat anchor, mengkliknya, dan mencabut URL-nya sama di tiap tempat, dan yang paling
 * mudah terlupa adalah `revokeObjectURL`: tanpa itu blob (bisa puluhan MB) tetap dipegang
 * halaman sampai tab ditutup.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
