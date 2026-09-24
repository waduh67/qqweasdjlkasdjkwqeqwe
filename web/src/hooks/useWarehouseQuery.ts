import { useEffect, useState } from 'react'

export type WarehouseResult<T> = { status: 'loading' } | { status: 'error'; error: unknown } | { status: 'ready'; data: T }

/** The loader is memoized by its caller. A previous filter's result is never rendered as current. */
export function useWarehouseQuery<T>(loader: () => Promise<T>) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{ loader: typeof loader; attempt: number; value: WarehouseResult<T> } | null>(null)
  useEffect(() => {
    let active = true
    void loader().then(data => {
      if (active) setResult({ loader, attempt, value: { status: 'ready', data } })
    }, error => {
      if (active) setResult({ loader, attempt, value: { status: 'error', error } })
    })
    return () => { active = false }
  }, [loader, attempt])
  const state: WarehouseResult<T> = result?.loader === loader && result.attempt === attempt ? result.value : { status: 'loading' }
  return { state, reload: () => setAttempt(previous => previous + 1) }
}
