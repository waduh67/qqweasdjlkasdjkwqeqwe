import { createContext, useContext, useLayoutEffect, useRef, useState, type ReactNode, type MouseEvent } from 'react'
import { Tab, TabList } from '@fluentui/react-components'
import { Modal } from '@/components/molecules/Modal'
import { Button } from '@/components/atoms'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { ResourceStepsContext, type ResourceStepDefinition } from './ResourceStep'

const ReviewLock = createContext<((locked: boolean) => void) | null>(null)

/** An uncertain submission must remain on its captured command, including through navigation. */
export function useResourceReviewLock(locked: boolean) {
  const setLocked = useContext(ReviewLock)
  useLayoutEffect(() => { setLocked?.(locked); return () => setLocked?.(false) }, [locked, setLocked])
}

/** A retained resource layer for details and review; form submission owns validation. */
export function ResourceForm({ title, children, footer, review, onBack, onClose, readOnly = false, editing = false, onReview, busy = false, loading = false, reviewFooter, className, reviewAction = false, dirty = false, returnFocus, navigation, steps: configuredSteps, reviewLabel: customReviewLabel }: {
  returnFocus?: HTMLElement | null;
  title: ReactNode; children: ReactNode; footer?: ReactNode; review?: ReactNode; navigation?: ReactNode; steps?: ResourceStepDefinition[];
  onBack: () => void; onClose: () => void; readOnly?: boolean; editing?: boolean;
  onReview?: () => void; busy?: boolean; loading?: boolean; reviewFooter?: ReactNode; className?: string; reviewAction?: boolean; dirty?: boolean | (() => boolean); reviewLabel?: string
}) {
  const steps = readOnly ? undefined : configuredSteps
  const content = useRef<HTMLDivElement>(null)
  const reviewContent = useRef<HTMLDivElement>(null)
  const [locked, setLocked] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const [validationError, setValidationError] = useState('')
  const [selectedStep, setSelectedStep] = useState(steps?.[0]?.key ?? 'basics')
  const activeStep = steps?.some(item => item.key === selectedStep) ? selectedStep : steps?.[0]?.key ?? 'basics'
  const stepIndex = steps?.findIndex(item => item.key === activeStep) ?? 0
  const focusTarget = useRef<HTMLElement | null>(null)
  const preparing = useRef(false)
  useLayoutEffect(() => {
    if (focusTarget.current) {
      focusTarget.current.focus()
      if ('reportValidity' in focusTarget.current) (focusTarget.current as HTMLInputElement).reportValidity()
      focusTarget.current = null
    }
  })
  // Validation is performed before changing steps, so hidden required fields can
  // be revealed and focused before the browser reports an error.
  useLayoutEffect(() => {
    if (!steps?.length) return
    const forms = [...content.current?.querySelectorAll('form') ?? []]
    const previous = forms.map(form => form.noValidate)
    forms.forEach(form => { form.noValidate = true })
    return () => forms.forEach((form, index) => { form.noValidate = previous[index] })
  }, [steps?.length, loading])
  const initialValues = useRef<string | null>(null)
  const values = () => JSON.stringify({
    fields: [...content.current?.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input,select,textarea') ?? []].map(input => [input.type, input.value, 'checked' in input ? input.checked : null]),
    choices: [...content.current?.querySelectorAll('[aria-pressed], [role=radio], [role=switch]') ?? []].map(choice => [choice.getAttribute('aria-pressed'), choice.getAttribute('aria-checked')]),
  })
  useLayoutEffect(() => {
    if (!loading && initialValues.current === null) initialValues.current = values()
  }, [loading])
  const requestClose = () => {
    if (locked || busy) return
    // A form with lazy controls can compare its domain draft instead of DOM fields.
    const changed = typeof dirty === 'function' ? dirty() : dirty || (!readOnly && initialValues.current !== null && initialValues.current !== values())
    if (changed) setConfirmClose(true)
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
  const reviewLabel = customReviewLabel ?? (editing ? 'Tinjau + simpan' : 'Tinjau + buat')
  const validate = (before?: number) => {
    type NativeField = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
    const fields = [...content.current?.querySelectorAll<HTMLElement>('input, select, textarea, [data-resource-required="true"]') ?? []]
    const invalid = fields.find(field => {
      const section = field.closest<HTMLElement>('[data-resource-step]')?.dataset.resourceStep
      if (before !== undefined && section && steps && steps.findIndex(item => item.key === section) >= before) return false
      if ('willValidate' in field) return (field as NativeField).willValidate && !(field as NativeField).validity.valid
      return !!steps?.length && field.dataset.resourceSelected === 'false'
    })
    if (!invalid) {
      for (const step of steps?.slice(0, before ?? steps.length) ?? []) {
        const message = step.validate?.()
        if (message) {
          setValidationError(message)
          const section = [...content.current?.querySelectorAll<HTMLElement>('[data-resource-step]') ?? []].find(node => node.dataset.resourceStep === step.key)
          focusTarget.current = section?.querySelector<HTMLElement>('input, select, textarea') ?? section ?? null
          setSelectedStep(step.key)
          return false
        }
      }
      setValidationError(''); return true
    }
    const native = 'willValidate' in invalid
    const target = native ? invalid : invalid.querySelector<HTMLElement>('[role="combobox"]')
    setValidationError(native ? '' : `Pilih ${invalid.dataset.resourceLabel?.toLowerCase() ?? 'isian yang wajib diisi'}.`)
    const section = invalid.closest<HTMLElement>('[data-resource-step]')?.dataset.resourceStep
    if (section && section !== activeStep) { focusTarget.current = target; setSelectedStep(section) }
    else { target?.focus(); if (native) (invalid as NativeField).reportValidity() }
    return false
  }
  const toReview = () => {
    if (loading || busy || locked || !validate()) return
    preparing.current = true
    try {
      if (onReview) onReview()
      else content.current?.querySelector('form')?.requestSubmit()
    } finally { preparing.current = false }
  }
  const go = (key: string) => {
    if (loading || busy || locked) return
    if (key === 'review') { if (!reviewing) toReview(); return }
    const target = steps?.findIndex(item => item.key === key) ?? 0
    if (!reviewing && target > stepIndex && !validate(target)) return
    if (reviewing) onBack()
    setSelectedStep(key)
  }
  const next = () => steps?.[stepIndex + 1] ? go(steps[stepIndex + 1].key) : toReview()
  const stepFooter = steps?.length ? <>
    <Button disabled={busy || locked} onClick={requestClose}>Batal</Button>
    {stepIndex > 0 && <Button disabled={busy || locked || loading} onClick={() => go(steps[stepIndex - 1].key)}>Sebelumnya</Button>}
    <Button variant="primary" disabled={busy || locked || loading} onClick={next}>{steps[stepIndex + 1] ? `Berikutnya: ${steps[stepIndex + 1].label}` : reviewLabel}</Button>
  </> : undefined
  return <ReviewLock.Provider value={setLocked}>
    <Modal returnFocus={returnFocus} title={title} onClose={requestClose} layout="resource" className={className} footer={withCancelGuard(review ? reviewFooter : stepFooter ?? (reviewAction ? <>
      <Button disabled={busy} onClick={onClose}>Batal</Button><Button variant="primary" disabled={busy || loading} onClick={toReview}>{reviewLabel}</Button>
    </> : footer))}>
      {navigation ?? (!readOnly && <TabList className="resource-form-tabs" aria-label="Langkah pengisian" selectedValue={review ? 'review' : activeStep} onTabSelect={(_, data) => {
        if (locked || busy || loading) return
        if (steps?.length) { go(String(data.value)); return }
        if (data.value === 'basics') onBack()
        else if (!review) toReview()
      }}>
        {(steps?.length ? steps : [{ key: 'basics', label: 'Dasar' }]).map(step => <Tab key={step.key} value={step.key} disabled={locked || busy || loading}>{step.label}</Tab>)}<Tab value="review" disabled={locked || busy || loading}>{reviewLabel}</Tab>
      </TabList>)}
      <div ref={content} className="resource-form-content" aria-busy={loading || undefined}>
        {validationError && !reviewing && <p className="error resource-step-error" role="alert">{validationError}</p>}
        <ResourceStepsContext.Provider value={steps?.length ? { active: activeStep, steps } : null}>
          <div hidden={!!review} inert={review ? true : undefined} onSubmitCapture={event => {
            if (steps?.length && !reviewing && !preparing.current && content.current?.contains(event.target as Node)) { event.preventDefault(); event.stopPropagation(); next() }
          }}>{children}</div>
        </ResourceStepsContext.Provider>
        {review && <div ref={reviewContent} tabIndex={-1} className="resource-review-content">{review}</div>}
      </div>
    </Modal>
    {confirmClose && <ConfirmDialog title="Buang perubahan?" message="Perubahan belum disimpan." confirmLabel="Buang perubahan" cancelLabel="Lanjutkan pengisian"
      onClose={() => setConfirmClose(false)} onConfirm={() => { setConfirmClose(false); onClose() }} />}
  </ReviewLock.Provider>
}
