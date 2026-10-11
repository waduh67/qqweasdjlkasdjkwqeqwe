import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Tab, TabList } from '@fluentui/react-components'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/atoms'
import { ResourceForm } from './ResourceForm'

export interface ResourceStep {
  key: string
  label: string
  content: ReactNode
  validate?: () => string | null
}

/** Retain every step and the underlying list; only the final review can save. */
export function ResourceWizard({ title, steps, summary, editing = false, dirty, onClose, onSave, children }: {
  title: string; steps: ResourceStep[]; summary: ReactNode; editing?: boolean; dirty: boolean
  onClose: () => void; onSave: () => Promise<void>; children?: ReactNode
}) {
  const id = useId(), content = useRef<HTMLFormElement>(null)
  const [selected, setSelected] = useState(steps[0].key)
  const [busy, setBusy] = useState(false), saving = useRef(false)
  const [error, setError] = useState('')
  const focusTarget = useRef<HTMLElement | null>(null)
  const previousStep = useRef<string | null>(null)
  const step = selected === 'review' || steps.some(item => item.key === selected) ? selected : steps[0].key
  const index = steps.findIndex(item => item.key === step)
  const reviewing = step === 'review'
  const reviewLabel = editing ? 'Tinjau + simpan' : 'Tinjau + buat'

  useLayoutEffect(() => {
    const moved = previousStep.current !== step
    previousStep.current = step
    if (focusTarget.current) { focusTarget.current.focus(); focusTarget.current = null }
    else if (moved && !reviewing) content.current?.querySelector<HTMLElement>(`[data-step="${step}"]`)?.focus()
  }, [step, error, reviewing])

  function go(next: string) {
    if (saving.current) return
    const target = next === 'review' ? steps.length : steps.findIndex(item => item.key === next)
    if (target < 0) return
    if (target > index && !reviewing) {
      for (const item of steps.slice(0, target)) {
        const invalid = [...content.current?.querySelectorAll<HTMLInputElement>(`[data-step="${item.key}"] input, [data-step="${item.key}"] select, [data-step="${item.key}"] textarea`) ?? []]
          .find(field => field.willValidate && !field.validity.valid)
        const message = item.validate?.() ?? (invalid ? 'Periksa isian pada langkah ini.' : null)
        if (message) {
          focusTarget.current = invalid ?? content.current?.querySelector<HTMLElement>(`[data-step="${item.key}"] input`) ?? null
          setError(message); setSelected(item.key)
          focusTarget.current?.focus()
          return
        }
      }
    }
    setError(''); setSelected(next)
  }

  async function save() {
    if (!reviewing || saving.current) return
    saving.current = true; setBusy(true); setError('')
    try { await onSave() }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Gagal menyimpan. Coba lagi.') }
    finally { saving.current = false; setBusy(false) }
  }

  const previous = () => go(reviewing ? steps.at(-1)!.key : steps[index - 1].key)
  const next = () => go(steps[index + 1]?.key ?? 'review')
  const footer = <>
    <Button disabled={busy} onClick={onClose}>Batal</Button>
    {(reviewing || index > 0) && <Button disabled={busy} icon={<ChevronLeft size={16} />} onClick={previous}>Sebelumnya</Button>}
    {reviewing
      ? <Button variant="primary" disabled={busy} onClick={() => void save()}>{busy ? 'Menyimpan…' : editing ? 'Simpan perubahan' : 'Buat'}</Button>
      : <Button variant="primary" onClick={next} icon={<ChevronRight size={16} />}>{index === steps.length - 1 ? reviewLabel : `Berikutnya: ${steps[index + 1].label}`}</Button>}
  </>
  return <ResourceForm title={title} dirty={dirty} busy={busy} onClose={onClose} onBack={previous}
    className="resource-wizard" footer={footer} reviewFooter={footer}
    navigation={<TabList className="resource-form-tabs" aria-label="Langkah pengisian" selectedValue={step} onTabSelect={(_, data) => go(String(data.value))}>
      {[...steps, { key: 'review', label: reviewLabel }].map(item => <Tab key={item.key} value={item.key} disabled={busy}
        id={`${id}-tab-${item.key}`} aria-controls={`${id}-panel-${item.key}`}>{item.label}</Tab>)}
    </TabList>}
    review={reviewing ? <section id={`${id}-panel-review`} role="tabpanel" aria-labelledby={`${id}-tab-review`} className="network-review">
      {error && <p role="alert" className="error">{error}</p>}{summary}
    </section> : undefined}>
    <form ref={content} noValidate onChange={() => { if (error) setError('') }} onSubmit={event => { event.preventDefault(); next() }}>
      {error && !reviewing && <p role="alert" className="error">{error}</p>}
      {steps.map(item => <section key={item.key} data-step={item.key} id={`${id}-panel-${item.key}`} role="tabpanel"
        aria-labelledby={`${id}-tab-${item.key}`} tabIndex={-1} hidden={step !== item.key} inert={step !== item.key ? true : undefined}
        className="network-form-step">{item.content}</section>)}
    </form>
    {children}
  </ResourceForm>
}
