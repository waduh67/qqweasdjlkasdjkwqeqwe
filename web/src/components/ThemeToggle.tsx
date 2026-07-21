import { useEffect, useState } from 'react'
import { IconMoon, IconSun } from './icons'

/**
 * Peralih tema terang/gelap.
 *
 * Nilai awal mengikuti preferensi sistem; pilihan pengguna disimpan di
 * localStorage dan distempel sebagai `data-theme` pada <html>, yang menang atas
 * media query (lihat token di index.css). Tanpa penyimpanan, tema akan
 * "meloncat" ke bawaan sistem tiap kali halaman dimuat ulang.
 */
const KEY = 'ftth.theme'

type Theme = 'light' | 'dark'

function initialTheme(): Theme {
  const saved = localStorage.getItem(KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(initialTheme)

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem(KEY, theme)
  }, [theme])

  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <button
      className="ghost icon-btn"
      onClick={() => setTheme(next)}
      aria-label={`Ganti ke tema ${next === 'dark' ? 'gelap' : 'terang'}`}
      title={`Tema ${theme === 'dark' ? 'gelap' : 'terang'}`}
    >
      {theme === 'dark' ? <IconMoon size={18} /> : <IconSun size={18} />}
    </button>
  )
}
