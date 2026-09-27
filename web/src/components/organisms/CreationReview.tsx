import { useEffect, useState, type ReactNode } from 'react'

/** Call beforeSave only after the resource's own validation and before its API write. */
export function useCreationReview(open: boolean, editing = false) {
  const [reviewing, setReviewing] = useState(false)
  useEffect(() => { if (!open) setReviewing(false) }, [open])
  return {
    reviewing, editing,
    back: () => setReviewing(false),
    beforeSave: () => { if (reviewing) return false; setReviewing(true); return true },
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
