import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { FluentProvider } from '@fluentui/react-components'
import { useLocation } from 'react-router-dom'
import { azureDark, azureLight } from './azureTheme'
import { isInternalPath } from './internalRoutes'
import '@/operational.css'
import '@/internal-controls.css'

const KEY = 'ftth.theme'

export type Theme = 'light' | 'dark'

type ThemeCtx = { readonly theme: Theme; readonly internal: boolean; readonly setTheme: (t: Theme) => void; readonly toggle: () => void }

const Ctx = createContext<ThemeCtx | null>(null)

function initialTheme(): Theme {
  const saved = localStorage.getItem(KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(initialTheme)
  const { pathname } = useLocation()
  const internal = isInternalPath(pathname)
  useLayoutEffect(() => {
    document.documentElement.setAttribute('data-console', internal ? 'internal' : 'public')
    return () => { document.documentElement.removeAttribute('data-console') }
  }, [internal])

  useLayoutEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem(KEY, theme)
  }, [theme])

  const setTheme = useCallback((t: Theme) => setThemeState(t), [])
  const toggle = useCallback(() => setThemeState((t) => (t === 'dark' ? 'light' : 'dark')), [])

  const value = useMemo<ThemeCtx>(() => ({ theme, internal, setTheme, toggle }), [theme, internal, setTheme, toggle])
  const fluentTheme = theme === 'dark' ? azureDark : azureLight

  return (
    <Ctx.Provider value={value}>
      <FluentProvider
        theme={fluentTheme}
        className={internal ? 'internal-theme' : undefined}
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

export function useInternalTheme(): boolean {
  return useContext(Ctx)?.internal ?? false
}
