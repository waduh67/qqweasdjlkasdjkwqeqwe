import type { ReactNode } from 'react'
import { Accordion, AccordionHeader, AccordionItem, AccordionPanel } from '@fluentui/react-components'

/** Optional details use the same keyboard-accessible Fluent chevron throughout the app. */
export function Disclosure({ title, children, className = '', open = false }: {
  title: ReactNode; children: ReactNode; className?: string; open?: boolean
}) {
  return <Accordion collapsible defaultOpenItems={open ? ['details'] : []} className={`resource-disclosure ${className}`}>
    <AccordionItem value="details">
      <AccordionHeader size="small">{title}</AccordionHeader>
      <AccordionPanel>{children}</AccordionPanel>
    </AccordionItem>
  </Accordion>
}
