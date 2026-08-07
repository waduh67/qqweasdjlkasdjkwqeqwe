import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { IconAlert, IconClose, IconInbox, IconSearch } from './icons'

/**
 * Primitif UI bersama. Dipusatkan agar status (aset/alarm/ONU) ditampilkan dengan
 * warna dan istilah yang seragam di seluruh aplikasi — nada status yang tidak
 * konsisten membuat operator ragu, dan itu lebih berbahaya daripada polos.
 */

export type Tone = 'neutral' | 'good' | 'warning' | 'serious' | 'critical' | 'accent'

/** Memetakan status domain ke nada visual. Satu sumber kebenaran untuk semua tabel. */
const STATUS_TONE: Record<string, Tone> = {
  // Aset jaringan & pelanggan
  ACTIVE: 'good',
  ONLINE: 'good',
  GOOD: 'good',
  PLANNED: 'accent',
  PENDING: 'warning',
  MAINTENANCE: 'warning',
  WARNING: 'warning',
  ISOLATED: 'serious',
  SUSPENDED: 'serious',
  OFFLINE: 'serious',
  UNKNOWN: 'neutral',
  INACTIVE: 'neutral',
  PROSPECT: 'neutral',
  DISABLED: 'neutral',
  TERMINATED: 'critical',
  LOS: 'critical',
  CRITICAL: 'critical',
  DISMANTLED: 'critical',
  // Alarm
  INFO: 'accent',
  ACKNOWLEDGED: 'warning',
  CLEARED: 'neutral',
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const tone = STATUS_TONE[status] ?? 'neutral'
  return (
    <span className={`badge ${tone}`}>
      <span className="dot" />
      {label ?? prettify(status)}
    </span>
  )
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return <span className={`badge ${tone}`}>{children}</span>
}

/** Ubah `ONU_LOW_RX` / `ACTIVE` menjadi teks yang enak dibaca. */
function prettify(value: string): string {
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/^\w/, (c) => c.toUpperCase())
}

export function EmptyState({ title, hint, icon }: { title: string; hint?: string; icon?: ReactNode }) {
  return (
    <div className="empty">
      {icon ?? <IconInbox size={34} />}
      <strong style={{ color: 'var(--text-2)' }}>{title}</strong>
      {hint && <span style={{ fontSize: '0.85rem' }}>{hint}</span>}
    </div>
  )
}

export function Spinner() {
  return <span className="spinner" role="status" aria-label="Memuat" />
}

/** Baris skeleton untuk keadaan memuat tabel. */
export function SkeletonRows({ rows = 4, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="row" style={{ gap: '0.75rem' }}>
          {Array.from({ length: cols }).map((_, c) => (
            <div
              key={c}
              className="skeleton"
              style={{ height: 14, flex: c === 0 ? 2 : 1, borderRadius: 6 }}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

/** Panel geser dari kanan untuk detail (tren ONU, dsb). */
export function Drawer({
  title,
  onClose,
  children,
}: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">
        <div className="drawer-head">
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
            <IconClose size={18} />
          </button>
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </>
  )
}

/**
 * Bilah filter di atas tabel — membungkus kontrol (pencarian, dropdown, tombol)
 * dalam satu baris yang membungkus rapi di layar sempit. Sekadar wadah tata letak
 * supaya semua halaman tabel punya jarak yang sama.
 */
export function Toolbar({ children }: { children: ReactNode }) {
  return <div className="toolbar">{children}</div>
}

/** Kotak pencarian teks-bebas dengan ikon kaca pembesar + tombol bersihkan. */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Cari…',
}: {
  value: string
  onChange: (value: string) => void
  placeholder?: string
}) {
  return (
    <div className="search-input">
      <IconSearch size={16} />
      <input
        type="search"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
      {value && (
        <button className="ghost icon-btn" onClick={() => onChange('')} aria-label="Bersihkan pencarian">
          <IconClose size={15} />
        </button>
      )}
    </div>
  )
}

/**
 * Dialog terpusat untuk form singkat (buat/ubah) — beda peran dari [Drawer] yang
 * dipakai untuk panel detail geser-kanan. Esc & klik latar menutup; `wide` untuk
 * form dua kolom.
 */
export function Modal({
  title,
  onClose,
  children,
  footer,
  wide,
}: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className={`modal${wide ? ' modal-wide' : ''}`} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="ghost icon-btn" onClick={onClose} aria-label="Tutup">
            <IconClose size={18} />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </>
  )
}

/**
 * Dialog konfirmasi in-app di atas [Modal] — pengganti `window.confirm` bawaan browser
 * agar konsisten dengan konvensi UI. `danger` mewarnai tombol aksi merah; `busy`
 * mengunci tombol saat aksi berjalan (mencegah klik ganda & penutupan tak sengaja).
 */
export function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Ya',
  cancelLabel = 'Batal',
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}: {
  title: ReactNode
  message: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  busy?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Modal
      title={title}
      onClose={() => !busy && onClose()}
      footer={
        <>
          <button className="ghost" onClick={onClose} disabled={busy}>
            {cancelLabel}
          </button>
          <button className={danger ? 'danger' : 'primary'} onClick={onConfirm} disabled={busy}>
            {busy ? 'Memproses…' : confirmLabel}
          </button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.6rem' }}>
        {message}
      </div>
    </Modal>
  )
}

/**
 * Strip tab untuk memecah panel padat (mis. detail work order) jadi bagian yang
 * terpisah tapi tetap satu konteks — lebih terbaca ketimbang satu kolom panjang.
 * Terkendali penuh: pemanggil memegang tab aktif. `badge` opsional untuk hitungan.
 */
export function Tabs<T extends string>({
  tabs,
  active,
  onChange,
}: {
  tabs: { key: T; label: ReactNode; badge?: ReactNode }[]
  active: T
  onChange: (key: T) => void
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          role="tab"
          aria-selected={active === t.key}
          className={`tab${active === t.key ? ' active' : ''}`}
          onClick={() => onChange(t.key)}
        >
          {t.label}
          {t.badge != null && <span className="tab-badge">{t.badge}</span>}
        </button>
      ))}
    </div>
  )
}

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
            <span>{toast.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

// ---------- Dialog imperatif (confirm/prompt) ----------

interface ConfirmOptions {
  title?: ReactNode
  message: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
}
interface PromptOptions {
  title?: ReactNode
  message?: ReactNode
  label?: ReactNode
  placeholder?: string
  defaultValue?: string
  confirmLabel?: string
  cancelLabel?: string
  multiline?: boolean
  required?: boolean
}
interface DialogApi {
  confirm: (opts: ConfirmOptions) => Promise<boolean>
  prompt: (opts: PromptOptions) => Promise<string | null>
}

type PendingConfirm = { kind: 'confirm'; opts: ConfirmOptions; resolve: (v: boolean) => void }
type PendingPrompt = { kind: 'prompt'; opts: PromptOptions; resolve: (v: string | null) => void }
type Pending = PendingConfirm | PendingPrompt

const DialogContext = createContext<DialogApi | null>(null)

/**
 * Pengganti `window.confirm`/`window.prompt` bawaan browser dengan dialog in-app
 * (di atas [Modal]) agar konsisten dengan gaya Azure. API imperatif berbasis Promise:
 * `const ok = await confirm({ ... })` / `const val = await prompt({ ... })` —
 * mengembalikan `false`/`null` bila dibatalkan. Dipasang sekali di dekat akar aplikasi.
 */
export function DialogProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  const [value, setValue] = useState('')

  const api = useMemo<DialogApi>(
    () => ({
      confirm: (opts) => new Promise<boolean>((resolve) => setPending({ kind: 'confirm', opts, resolve })),
      prompt: (opts) =>
        new Promise<string | null>((resolve) => {
          setValue(opts.defaultValue ?? '')
          setPending({ kind: 'prompt', opts, resolve })
        }),
    }),
    [],
  )

  const settle = useCallback(
    (result: boolean | string | null) => {
      setPending((cur) => {
        if (cur) cur.resolve(result as never)
        return null
      })
    },
    [],
  )

  const promptRequired =
    pending?.kind === 'prompt' && pending.opts.required && value.trim() === ''

  return (
    <DialogContext.Provider value={api}>
      {children}
      {pending?.kind === 'confirm' && (
        <ConfirmDialog
          title={pending.opts.title ?? 'Konfirmasi'}
          message={pending.opts.message}
          confirmLabel={pending.opts.confirmLabel}
          cancelLabel={pending.opts.cancelLabel}
          danger={pending.opts.danger}
          onConfirm={() => settle(true)}
          onClose={() => settle(false)}
        />
      )}
      {pending?.kind === 'prompt' && (
        <Modal
          title={pending.opts.title ?? 'Masukan'}
          onClose={() => settle(null)}
          footer={
            <>
              <button className="ghost" onClick={() => settle(null)}>
                {pending.opts.cancelLabel ?? 'Batal'}
              </button>
              <button className="primary" onClick={() => settle(value)} disabled={promptRequired}>
                {pending.opts.confirmLabel ?? 'OK'}
              </button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.6rem' }}>
            {pending.opts.message && <p style={{ margin: 0 }}>{pending.opts.message}</p>}
            <label>
              {pending.opts.label && <span>{pending.opts.label}</span>}
              {pending.opts.multiline ? (
                <textarea
                  autoFocus
                  rows={4}
                  value={value}
                  placeholder={pending.opts.placeholder}
                  onChange={(e) => setValue(e.target.value)}
                />
              ) : (
                <input
                  autoFocus
                  value={value}
                  placeholder={pending.opts.placeholder}
                  onChange={(e) => setValue(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !promptRequired) settle(value)
                  }}
                />
              )}
            </label>
          </div>
        </Modal>
      )}
    </DialogContext.Provider>
  )
}

/** Hook konfirmasi imperatif: `const confirm = useConfirm(); if (await confirm({ message })) …` */
export function useConfirm() {
  const ctx = useContext(DialogContext)
  if (!ctx) throw new Error('useConfirm harus di dalam <DialogProvider>')
  return ctx.confirm
}

/** Hook input imperatif: `const prompt = usePrompt(); const val = await prompt({ label })` */
export function usePrompt() {
  const ctx = useContext(DialogContext)
  if (!ctx) throw new Error('usePrompt harus di dalam <DialogProvider>')
  return ctx.prompt
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast harus di dalam ToastProvider')
  return ctx
}
