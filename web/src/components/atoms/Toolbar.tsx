import type { ReactNode } from 'react'

/**
 * Bilah filter di atas tabel — membungkus kontrol (pencarian, dropdown, tombol)
 * dalam satu baris yang membungkus rapi di layar sempit. Sekadar wadah tata letak
 * supaya semua halaman tabel punya jarak yang sama.
 */
export function Toolbar({ children }: { children: ReactNode }) {
  return <div className="toolbar">{children}</div>
}
