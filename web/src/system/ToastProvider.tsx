import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react'
import { AriaLiveAnnouncer, Toast, ToastTitle, Toaster, useAnnounce, useId, useToastController, type ToasterProps } from '@fluentui/react-components'

type ToastKind = 'success' | 'error' | 'info'
interface ToastApi {
  success: (message: string) => void
  error: (message: string) => void
  info: (message: string) => void
}
const ToastContext = createContext<ToastApi | null>(null)

/** Fluent announces feedback even while a resource form owns the modal focus. */
export function ToastProvider({ children }: { children: ReactNode }) {
  return <AriaLiveAnnouncer><ToastService>{children}</ToastService></AriaLiveAnnouncer>
}

function ToastService({ children }: { children: ReactNode }) {
  const { announce } = useAnnounce()
  const announceToast = useCallback<NonNullable<ToasterProps['announce']>>((message, { politeness }) => {
    announce(message, { polite: politeness === 'polite', priority: politeness === 'assertive' ? 1 : 0 })
  }, [announce])
  const toasterId = useId('app-notifications')
  const { dispatchToast } = useToastController(toasterId)
  const push = useCallback((kind: ToastKind, message: string) => {
    dispatchToast(<Toast><ToastTitle>{message}</ToastTitle></Toast>, {
      intent: kind, timeout: kind === 'error' ? 6000 : 3500,
      politeness: kind === 'error' ? 'assertive' : 'polite',
    })
  }, [dispatchToast])
  const api = useMemo<ToastApi>(() => ({
    success: message => push('success', message),
    error: message => push('error', message),
    info: message => push('info', message),
  }), [push])
  return <ToastContext.Provider value={api}>
    {children}
    <Toaster toasterId={toasterId} position="bottom-end" announce={announceToast} />
  </ToastContext.Provider>
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast harus di dalam ToastProvider')
  return ctx
}
