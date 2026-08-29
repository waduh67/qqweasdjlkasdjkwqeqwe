import { Text } from '@fluentui/react-components'
import type { AffectedCustomer } from '@/api/network'

/** Titik status ONU di daftar panel — dijaga seirama dengan [CUSTOMER_COLOR] di peta. */
const ONU_DOT: Record<string, string> = {
  ONLINE: '#34d399',
  LOS: '#ff3b5c',
  OFFLINE: '#ff5470',
  PENDING: '#8b95a7',
  DISMANTLED: '#8b95a7',
}

export function AffectedRow({ c }: { c: AffectedCustomer }) {
  return (
    <div className="spread" style={{ gap: '0.45rem', alignItems: 'center' }}>
      <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flexShrink: 0,
            background: ONU_DOT[c.onuStatus] ?? 'var(--muted)',
          }}
        />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name}</span>
      </span>
      <Text as="span" className="muted tnum" size={200} style={{ flexShrink: 0 }}>
        {c.odpCode}
      </Text>
    </div>
  )
}
