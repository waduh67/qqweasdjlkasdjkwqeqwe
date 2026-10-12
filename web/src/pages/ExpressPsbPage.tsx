import { Navigate, useSearchParams } from 'react-router-dom'

/** Keep existing bookmarks opening the PSB panel over the customer list. */
export function ExpressPsbPage() {
  const [params] = useSearchParams()
  const next = new URLSearchParams(params)
  next.set('create', 'psb')
  return <Navigate to={{ pathname: '/customers', search: `?${next}` }} replace />
}
