import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { FluentProvider } from '@fluentui/react-components'
import { azureDark, azureLight } from './azureTheme'

/**
 * Sumber kebenaran TUNGGAL untuk tema. Menyimpan pilihan di `localStorage` dan
 * menstempel `data-theme` pada <html> (dipakai token index.css lama selama migrasi),
 * SEKALIGUS memberi tahu `FluentProvider` agar seluruh komponen Fluent ikut ganti
 * antara `azureLight`/`azureDark`. `ThemeToggle` cukup memanggil `toggle()` dari sini.
 */
const KEY = 'ftth.theme'

export type Theme = 'light' | 'dark'

type ThemeCtx = { theme: Theme; setTheme: (t: Theme) => void; toggle: () => void }

const Ctx = createContext<ThemeCtx | null>(null)

function initialTheme(): Theme {
  const saved = localStorage.getItem(KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(initialTheme)

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem(KEY, theme)
  }, [theme])

  const setTheme = useCallback((t: Theme) => setThemeState(t), [])
  const toggle = useCallback(() => setThemeState((t) => (t === 'dark' ? 'light' : 'dark')), [])

  const value = useMemo<ThemeCtx>(() => ({ theme, setTheme, toggle }), [theme, setTheme, toggle])

  return (
    <Ctx.Provider value={value}>
      <FluentProvider
        theme={theme === 'dark' ? azureDark : azureLight}
        style={{ minHeight: '100vh', background: 'transparent' }}
      >
        {children}
      </FluentProvider>
    </Ctx.Provider>
  )
}

export function useTheme(): ThemeCtx {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useTheme harus dipakai di dalam <ThemeProvider>')
  return ctx
}
