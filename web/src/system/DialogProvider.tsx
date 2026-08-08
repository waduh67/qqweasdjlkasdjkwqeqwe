import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { Button, TextField, TextareaField } from '@/components/atoms'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { Modal } from '@/components/molecules/Modal'

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
              <Button variant="subtle" onClick={() => settle(null)}>
                {pending.opts.cancelLabel ?? 'Batal'}
              </Button>
              <Button variant="primary" onClick={() => settle(value)} disabled={promptRequired}>
                {pending.opts.confirmLabel ?? 'OK'}
              </Button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.6rem' }}>
            {pending.opts.message && <p style={{ margin: 0 }}>{pending.opts.message}</p>}
            {pending.opts.multiline ? (
              <TextareaField
                label={pending.opts.label as string | undefined}
                autoFocus
                rows={4}
                value={value}
                placeholder={pending.opts.placeholder}
                onChange={(_, data) => setValue(data.value)}
              />
            ) : (
              <TextField
                label={pending.opts.label as string | undefined}
                autoFocus
                value={value}
                placeholder={pending.opts.placeholder}
                onChange={(_, data) => setValue(data.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !promptRequired) settle(value)
                }}
              />
            )}
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
