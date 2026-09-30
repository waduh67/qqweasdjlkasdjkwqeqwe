import { useEffect, useRef, useState, type ReactNode } from 'react'

/** Call beforeSave only after the resource's own validation and before its API write. */
export function useCreationReview(open: boolean, editing = false) {
  const [reviewing, setReviewing] = useState(false)
  const [busy, setBusy] = useState(false)
  const active = useRef(false)
  useEffect(() => { if (!open) { setReviewing(false); setBusy(false); active.current = false } }, [open])
  return {
    reviewing, editing, busy,
    back: () => setReviewing(false),
    beforeSave: () => {
      if (active.current) return true
      if (!reviewing) { setReviewing(true); return true }
      active.current = true; setBusy(true); return false
    },
    finish: () => { active.current = false; setBusy(false) },
  }
}

export type CreationFlow = ReturnType<typeof useCreationReview> & {
  summary: ReactNode; prepare: () => void; busy?: boolean
}

export function CreationSummary({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="resource-summary">{rows.map(([label, value]) => <div key={label}>
    <dt>{label}</dt><dd>{value || '—'}</dd>
  </div>)}</dl>
}
