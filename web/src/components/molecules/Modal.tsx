import { createContext, useContext, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle } from '@fluentui/react-components'
import { useActivateModal } from '@fluentui/react-tabster'
import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'
import { ChevronLeft16Regular, ChevronRight12Regular } from '@fluentui/react-icons'

let modalSequence = 0
const activeModals = new Set<number>()

const ResourceAncestors = createContext<ReactNode[]>([])

/** Shared Fluent dialog: use the same focus manager as drawers and menus. */
export function Modal({ title, onClose, children, footer, wide, layout = 'dialog', className = '', returnFocus }: {
  returnFocus?: HTMLElement | null
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
  layout?: 'dialog' | 'resource'
  className?: string
}) {
  // Imperative dialogs have no DialogTrigger for Fluent to restore. Capture the
  // launcher before the surface moves focus; restore before disposal and after commit.
  const [launcher] = useState(() => returnFocus === undefined ? document.activeElement instanceof HTMLElement ? document.activeElement : null : returnFocus)
  const [modalId] = useState(() => ++modalSequence)
  useLayoutEffect(() => { activeModals.add(modalId); return () => { activeModals.delete(modalId) } }, [modalId])
  const requestClose = () => { if (modalId === Math.max(...activeModals)) onClose() }
  const surface = useRef<HTMLDivElement>(null)
  const activateModal = useActivateModal()
  const ancestors = useContext(ResourceAncestors)
  const [pageTitle] = useState(() => document.querySelector('main h1')?.textContent ?? '')
  const trail = useMemo(() => [...ancestors, title], [ancestors, title])
  useLayoutEffect(() => {
    // A loading surface can be replaced in the same commit. Settle focus after
    // all surfaces register, so cleanup cannot leave the new top layer hidden.
    queueMicrotask(() => {
      const current = surface.current
      if (current?.isConnected && modalId === Math.max(...activeModals)) {
        // Fluent defers activation until Tabster has registered the new surface.
        // Focus alone can arrive before that registration when loading is replaced.
        activateModal(current)
        if (!current.contains(document.activeElement)) current.focus({ preventScroll: true })
      }
    })
  }, [modalId, activateModal])
  useLayoutEffect(() => () => {
    const closingSurface = surface.current
    const restore = () => {
      if ([...activeModals].some(id => id > modalId)) return
      // Deactivate the closing modal before Fluent disposes its focus manager.
      // Deferring this until passive cleanup can leave the page aria-hidden.
      const anotherDialogHasFocus = () => {
        const focused = document.activeElement
        const dialog = focused instanceof HTMLElement ? focused.closest('[role="dialog"]') : null
        return dialog?.isConnected && dialog !== closingSurface && !(launcher && dialog.contains(launcher))
      }
      const focus = (target: HTMLElement | null) => {
        if (!target?.isConnected || target.matches(':disabled') || target.closest('[hidden], [inert]')) return false
        const previousTabIndex = target.getAttribute('tabindex')
        if (target.tabIndex < 0) target.setAttribute('tabindex', '-1')
        target.focus({ preventScroll: true })
        if (previousTabIndex === null) target.removeAttribute('tabindex')
        else target.setAttribute('tabindex', previousTabIndex)
        return document.activeElement === target
      }
      if (anotherDialogHasFocus()) return
      if (launcher !== document.body && focus(launcher)) return
      // A surviving parent dialog may have selected a new control while saving.
      const current = document.activeElement
      if (current instanceof HTMLElement) {
        const dialog = current.closest('[role="dialog"]')
        if (dialog?.isConnected && dialog !== closingSurface) return
      }
      const page = document.querySelector<HTMLElement>('main, #root') ?? [...document.body.children].find(
        (node): node is HTMLElement => node instanceof HTMLElement && !node.matches('script, style, link, [data-tabster-dummy], [hidden], [inert], [data-portal-node]') && !node.contains(closingSurface),
      ) ?? null
      focus(page)
    }
    restore()
    queueMicrotask(() => { if (!closingSurface?.isConnected) restore() })
  }, [launcher, modalId])
  return (
    <Dialog open onOpenChange={(_, data) => {
      if (data.open) return
      if (layout === 'resource') {
        if (data.type === 'backdropClick') return
        const target = data.event.target
        const targetDialog = target instanceof Element ? target.closest('[role="dialog"]') : null
        if (targetDialog && targetDialog !== surface.current) return
      }
      requestClose()
    }}>
      <DialogSurface onSubmit={event => event.stopPropagation()} onClick={event => event.stopPropagation()} backdrop={layout === 'resource' ? { className: 'resource-form-backdrop' } : undefined} ref={surface} data-resource-layer={layout === 'resource' ? ancestors.length : undefined} style={layout === 'resource' ? { '--resource-layer-depth': Math.min(ancestors.length, 5) } as CSSProperties : undefined} className={`console-dialog ${className}${wide ? ' console-dialog-wide' : ''}${layout === 'resource' ? ' resource-form-dialog' : ''}`}>
        <DialogBody>
          {layout === 'resource' && <nav className="resource-layer-trail" aria-label="Alur formulir">
            {ancestors.length > 0 && <Button variant="subtle" icon={<ChevronLeft16Regular />} aria-label="Kembali ke panel sebelumnya" onClick={requestClose} />}
            {[...(pageTitle ? [pageTitle] : []), ...ancestors].map((name, index) => <span key={index}>{name}<ChevronRight12Regular aria-hidden="true" /></span>)}
            <span aria-current="page">{title}</span>
          </nav>}
          <div className="console-dialog-heading">
            <DialogTitle>{title}</DialogTitle>
            <Button variant="subtle" icon={<IconClose size={18} />} onClick={requestClose} aria-label="Tutup" />
          </div>
          <DialogContent><ResourceAncestors.Provider value={layout === 'resource' ? trail : ancestors}>{children}</ResourceAncestors.Provider></DialogContent>
          {footer && <DialogActions>{footer}</DialogActions>}
        </DialogBody>
      </DialogSurface>
    </Dialog>
  )
}
