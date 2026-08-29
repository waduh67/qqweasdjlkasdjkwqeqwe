import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { IconAlert } from '@/components/atoms/icons'

// ---------- Toast ----------

type ToastKind = 'success' | 'error' | 'info'
interface Toast {
  id: number
  kind: ToastKind
  message: string
}
interface ToastApi {
  success: (message: string) => void
  error: (message: string) => void
  info: (message: string) => void
}

const ToastContext = createContext<ToastApi | null>(null)

/**
 * Umpan balik aksi lewat toast, bukan teks error inline: aksi (simpan, hapus,
 * akui alarm) sering memindahkan fokus atau memuat ulang daftar, sehingga pesan
 * di tempat lama mudah terlewat. Toast muncul di tempat yang konsisten.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const push = useCallback((kind: ToastKind, message: string) => {
    const id = Date.now() + Math.random()
    setToasts((prev) => [...prev, { id, kind, message }])
    // Auto-hilang; galat bertahan sedikit lebih lama karena lebih penting dibaca.
    window.setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), kind === 'error' ? 6000 : 3500)
  }, [])

  // Di-memo agar identitas `api` stabil (push sudah stabil): tanpa ini, tiap toast membuat
  // nilai context baru → `useEffect` ber-dep `[toast]` fetch-ulang tak sengaja (mis. metode
  // pembayaran di /payment-gateway ke-reset ke Manual saat ada toast).
  const api = useMemo<ToastApi>(
    () => ({
      success: (m) => push('success', m),
      error: (m) => push('error', m),
      info: (m) => push('info', m),
    }),
    [push],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-host">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.kind}`}>
            <span className="bar" />
            {toast.kind === 'error' && <IconAlert size={17} style={{ color: 'var(--critical)', flex: 'none' }} />}
            <Text as="span" size={300}>{toast.message}</Text>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast harus di dalam ToastProvider')
  return ctx
}
