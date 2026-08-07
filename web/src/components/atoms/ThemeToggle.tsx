import { IconMoon, IconSun } from './icons'
import { useTheme } from '@/theme/ThemeProvider'

/**
 * Peralih tema terang/gelap.
 *
 * Sumber kebenaran tema kini di [ThemeProvider] — komponen ini cukup memicu
 * `toggle()`. Provider menstempel `data-theme` pada <html> (token index.css lama)
 * sekaligus mengganti tema Fluent (azureLight/azureDark).
 */
export function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <button
      className="ghost icon-btn"
      onClick={toggle}
      aria-label={`Ganti ke tema ${next === 'dark' ? 'gelap' : 'terang'}`}
      title={`Tema ${theme === 'dark' ? 'gelap' : 'terang'}`}
    >
      {theme === 'dark' ? <IconMoon size={18} /> : <IconSun size={18} />}
    </button>
  )
}
