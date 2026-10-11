import { createContext, useContext, type ReactNode } from 'react'

export interface ResourceStepDefinition { key: string; label: string; validate?: () => string | null }
export const ResourceStepsContext = createContext<{ active: string; steps: ResourceStepDefinition[] } | null>(null)

/** Keep field values mounted while the operator moves between named steps. */
export function ResourceStep({ step, children }: { step: string; children: ReactNode }) {
  const context = useContext(ResourceStepsContext)
  const hidden = !!context && context.active !== step
  return <section data-resource-step={step} hidden={hidden} inert={hidden ? true : undefined}
    aria-label={context?.steps.find(item => item.key === step)?.label} tabIndex={-1} className="resource-form-step">{children}</section>
}
