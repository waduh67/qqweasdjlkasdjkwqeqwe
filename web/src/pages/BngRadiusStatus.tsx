import { Button, Spinner } from '@/components/atoms'
import type { RadiusEndpointView } from '@/api/bng'

export type BngRadiusState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; endpoint: RadiusEndpointView }

export function BngRadiusStatus({ state, onRetry }: { state: BngRadiusState; onRetry: () => void }) {
  if (state.status === 'loading') return <div className="row" role="status"><span aria-hidden="true"><Spinner /></span> Memuat server RADIUS…</div>
  if (state.status === 'error') return <div className="network-error" role="alert"><p>{state.message}</p><Button onClick={onRetry}>Coba lagi</Button></div>
  if (!state.endpoint.configured) return <p role="status">Server RADIUS belum diatur. Hubungi administrator platform.</p>
  return null
}
