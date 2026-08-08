import { IconMoon, IconSun } from './icons'
import { Button } from './Button'
import { useTheme } from '@/theme/ThemeProvider'

/**
 * Peralih tema terang/gelap.
 *
 * Sumber kebenaran tema kini di [ThemeProvider] — komponen ini cukup memicu
 * `toggle()`. Provider menstempel `data-theme` pada <html> (token index.css lama)
 * sekaligus mengganti tema Fluent (azureLight/azureDark). Tombol ikon-saja pakai
 * atom [Button] `subtle` (dulu `.ghost .icon-btn` native) agar gaya datang dari tema.
 */
export function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button
      variant="subtle"
      icon={theme === 'dark' ? <IconMoon size={18} /> : <IconSun size={18} />}
      onClick={toggle}
      aria-label={`Ganti ke tema ${next === 'dark' ? 'gelap' : 'terang'}`}
      title={`Tema ${theme === 'dark' ? 'gelap' : 'terang'}`}
    />
  )
}
