import { createContext, useContext, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Tab, TabList } from '@fluentui/react-components'
import { Modal } from '@/components/molecules/Modal'
import { Button } from '@/components/atoms'

const ReviewLock = createContext<((locked: boolean) => void) | null>(null)

/** An uncertain submission must remain on its captured command, including through navigation. */
export function useResourceReviewLock(locked: boolean) {
  const setLocked = useContext(ReviewLock)
  useLayoutEffect(() => { setLocked?.(locked); return () => setLocked?.(false) }, [locked, setLocked])
}

/** The same full-page surface for details and review; form submission owns validation. */
export function ResourceForm({ title, children, footer, review, onBack, onClose, readOnly = false, editing = false, onReview, busy = false, reviewFooter, className, reviewAction = false }: {
  title: ReactNode; children: ReactNode; footer?: ReactNode; review?: ReactNode;
  onBack: () => void; onClose: () => void; readOnly?: boolean; editing?: boolean;
  onReview?: () => void; busy?: boolean; reviewFooter?: ReactNode; className?: string; reviewAction?: boolean
}) {
  const content = useRef<HTMLDivElement>(null)
  const [locked, setLocked] = useState(false)
  const reviewLabel = editing ? 'Tinjau + simpan' : 'Tinjau + buat'
  const toReview = () => {
    const invalid = content.current?.querySelector<HTMLInputElement>('input:invalid, select:invalid, textarea:invalid')
    if (invalid) { invalid.reportValidity(); return }
    if (onReview) onReview()
    else content.current?.querySelector('form')?.requestSubmit()
  }
  return <ReviewLock.Provider value={setLocked}>
    <Modal title={title} onClose={() => { if (!locked && !busy) onClose() }} layout="resource" className={className} footer={review ? reviewFooter : reviewAction ? <>
      <Button variant="primary" disabled={busy} onClick={toReview}>{reviewLabel}</Button><Button disabled={busy} onClick={onClose}>Batal</Button>
    </> : footer}>
      {!readOnly && <TabList className="resource-form-tabs" selectedValue={review ? 'review' : 'basics'} onTabSelect={(_, data) => {
        if (locked || busy) return
        if (data.value === 'basics') onBack()
        else if (!review) toReview()
      }}>
        <Tab value="basics" disabled={locked || busy}>Dasar</Tab><Tab value="review" disabled={locked || busy}>{reviewLabel}</Tab>
      </TabList>}
      <div ref={content} className="resource-form-content">
        <div hidden={!!review} inert={review ? true : undefined}>{children}</div>
        {review}
      </div>
    </Modal>
  </ReviewLock.Provider>
}
