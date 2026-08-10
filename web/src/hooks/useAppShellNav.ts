import { useCallback, useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * Perilaku navigasi shell (dipakai `Layout` operator & `PlatformLayout`), yang berbeda
 * antara layar lebar dan ponsel:
 *
 * - **Layar lebar** — tombol di header MENCIUTKAN sidebar jadi rel ikon; pilihannya
 *   diingat lintas sesi (localStorage), karena itu preferensi tetap seorang operator.
 * - **Ponsel** — tak ada ruang untuk kolom nav permanen, jadi tombol yang sama MEMBUKA
 *   sidebar sebagai laci melayang di atas konten. Keadaannya sengaja TIDAK disimpan:
 *   laci yang menutupi layar saat aplikasi dibuka adalah gangguan, bukan preferensi.
 *
 * Ambangnya (820px) sama dengan blok responsif di `index.css`; menaruhnya di dua tempat
 * memang mengulang, tapi alternatifnya (mengukur lebar di JS lalu menyetel kelas) membuat
 * tata letak berkedip saat memuat.
 */
const MOBILE_QUERY = '(max-width: 820px)'
const COLLAPSE_KEY = 'ftth.sidebarCollapsed'

export function useAppShellNav() {
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1')
  const [navOpen, setNavOpen] = useState(false)

  // Pindah halaman harus menutup laci: kalau tidak, menu tetap menutupi halaman yang
  // baru saja dipilih dan pengguna mengira tapnya tak terjadi apa-apa.
  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  const closeNav = useCallback(() => setNavOpen(false), [])

  const toggleNav = useCallback(() => {
    if (window.matchMedia(MOBILE_QUERY).matches) {
      setNavOpen((v) => !v)
      return
    }
    setCollapsed((v) => {
      const next = !v
      localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0')
      return next
    })
  }, [])

  /** Kelas untuk elemen `.app`; `nav-open` hanya berarti di lebar ponsel. */
  const shellClass = `app${collapsed ? ' sidebar-collapsed' : ''}${navOpen ? ' nav-open' : ''}`

  return { collapsed, navOpen, toggleNav, closeNav, shellClass }
}
