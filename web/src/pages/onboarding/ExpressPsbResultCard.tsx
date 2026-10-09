import { Text } from '@fluentui/react-components'
import { Link } from 'react-router-dom'
import type { ExpressPsbResult } from '@/api/onboarding'
import { Badge, Button } from '@/components/atoms'

export function ExpressPsbResultCard({ result, reference, onDismiss }: { readonly result: ExpressPsbResult & { readonly secretUsed: string }; readonly reference: boolean; readonly onDismiss: () => void }) {
  return (
    <div className="card stack" style={{ gap: '0.6rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>
          <Badge tone="good">PSB dibuat</Badge> {result.workOrderCode}
        </Text>
        <Button variant="subtle" onClick={onDismiss}>Tutup</Button>
      </div>
      <dl className="kv" style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '0.3rem 0.8rem' }}>
        <dt className="muted">Username</dt>
        <dd style={{ margin: 0 }}><code>{result.username}</code></dd>
        <dt className="muted">Password</dt>
        <dd style={{ margin: 0 }}><code>{result.secretUsed}</code> <span className="muted">(hanya tampil sekali)</span></dd>
        <dt className="muted">Work Order</dt>
        <dd style={{ margin: 0 }}>{result.workOrderCode}</dd>
      </dl>
      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        {reference ? 'Langkah berikutnya: tugaskan teknisi untuk pemasangan. Layanan aktif setelah teknisi menyelesaikan pekerjaan.' : 'Layanan aktif setelah teknisi menuntaskan Work Order PSB.'}
      </Text>
      <Link to={'/work-orders/' + result.workOrderId}>{reference ? 'Tugaskan teknisi' : 'Buka Work Order'}</Link>
    </div>
  )
}
