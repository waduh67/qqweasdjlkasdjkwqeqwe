import { createContext, useContext, useLayoutEffect, useRef, useState, type ReactNode, type MouseEvent } from 'react'
import { Tab, TabList } from '@fluentui/react-components'
import { Modal } from '@/components/molecules/Modal'
import { Button } from '@/components/atoms'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'

const ReviewLock = createContext<((locked: boolean) => void) | null>(null)

/** An uncertain submission must remain on its captured command, including through navigation. */
export function useResourceReviewLock(locked: boolean) {
  const setLocked = useContext(ReviewLock)
  useLayoutEffect(() => { setLocked?.(locked); return () => setLocked?.(false) }, [locked, setLocked])
}

/** A retained resource layer for details and review; form submission owns validation. */
export function ResourceForm({ title, children, footer, review, onBack, onClose, readOnly = false, editing = false, onReview, busy = false, reviewFooter, className, reviewAction = false, dirty = false, returnFocus }: {
  returnFocus?: HTMLElement | null;
  title: ReactNode; children: ReactNode; footer?: ReactNode; review?: ReactNode;
  onBack: () => void; onClose: () => void; readOnly?: boolean; editing?: boolean;
  onReview?: () => void; busy?: boolean; reviewFooter?: ReactNode; className?: string; reviewAction?: boolean; dirty?: boolean
}) {
  const content = useRef<HTMLDivElement>(null)
  const reviewContent = useRef<HTMLDivElement>(null)
  const [locked, setLocked] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const initialValues = useRef<string | null>(null)
  const values = () => JSON.stringify({
    fields: [...content.current?.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input,select,textarea') ?? []].map(input => [input.type, input.value, 'checked' in input ? input.checked : null]),
    choices: [...content.current?.querySelectorAll('[aria-pressed], [role=radio], [role=switch]') ?? []].map(choice => [choice.getAttribute('aria-pressed'), choice.getAttribute('aria-checked')]),
  })
  useLayoutEffect(() => { initialValues.current = values() }, [])
  const requestClose = () => {
    if (locked || busy) return
    if (dirty || (!readOnly && initialValues.current !== null && initialValues.current !== values())) setConfirmClose(true)
    else onClose()
  }
  const interceptCancel = (event: MouseEvent) => {
    const button = event.target instanceof HTMLElement ? event.target.closest('button') : null
    if (button && /^(Batal|Batalkan)(\s|$)/.test(button.textContent?.trim() ?? '')) {
      event.preventDefault(); event.stopPropagation(); requestClose()
    }
  }
  const withCancelGuard = (actions: ReactNode) => actions ? <div className="resource-action-contents" onClickCapture={interceptCancel}>{actions}</div> : undefined
  const reviewing = !!review
  useLayoutEffect(() => { if (reviewing) reviewContent.current?.focus() }, [reviewing])
  const reviewLabel = editing ? 'Tinjau + simpan' : 'Tinjau + buat'
  const toReview = () => {
    const invalid = [...content.current?.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input, select, textarea') ?? []]
      .find(input => input.willValidate && !input.validity.valid)
    if (invalid) { invalid.reportValidity(); return }
    if (onReview) onReview()
    else content.current?.querySelector('form')?.requestSubmit()
  }
  return <ReviewLock.Provider value={setLocked}>
    <Modal returnFocus={returnFocus} title={title} onClose={requestClose} layout="resource" className={className} footer={withCancelGuard(review ? reviewFooter : reviewAction ? <>
      <Button disabled={busy} onClick={onClose}>Batal</Button><Button variant="primary" disabled={busy} onClick={toReview}>{reviewLabel}</Button>
    </> : footer)}>
      {!readOnly && <TabList className="resource-form-tabs" selectedValue={review ? 'review' : 'basics'} onTabSelect={(_, data) => {
        if (locked || busy) return
        if (data.value === 'basics') onBack()
        else if (!review) toReview()
      }}>
        <Tab value="basics" disabled={locked || busy}>Dasar</Tab><Tab value="review" disabled={locked || busy}>{reviewLabel}</Tab>
      </TabList>}
      <div ref={content} className="resource-form-content">
        <div hidden={!!review} inert={review ? true : undefined}>{children}</div>
        {review && <div ref={reviewContent} tabIndex={-1} className="resource-review-content">{review}</div>}
      </div>
    </Modal>
    {confirmClose && <ConfirmDialog title="Buang perubahan?" message="Perubahan belum disimpan." confirmLabel="Buang perubahan" cancelLabel="Lanjutkan pengisian"
      onClose={() => setConfirmClose(false)} onConfirm={() => { setConfirmClose(false); onClose() }} />}
  </ReviewLock.Provider>
}
