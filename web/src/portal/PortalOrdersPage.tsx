import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Badge, EmptyState, IconWorkOrder, Spinner } from '@/components/atoms'
import { getPortalOrders, type PortalOrder, type PortalOrderStatus } from './portalApi'
import { PortalApiError } from './portalClient'

const ORDER_STATUS: Record<PortalOrderStatus, { label: string; tone: 'neutral' | 'accent' | 'warning' | 'good' | 'critical' }> = {
  RECEIVED: { label: 'Diterima', tone: 'accent' },
  REVIEWING: { label: 'Sedang ditinjau', tone: 'warning' },
  SCHEDULED: { label: 'Terjadwal', tone: 'accent' },
  IN_PROGRESS: { label: 'Sedang dikerjakan', tone: 'warning' },
  WAITING_CUSTOMER: { label: 'Menunggu tindakan Anda', tone: 'warning' },
  COMPLETED: { label: 'Selesai', tone: 'good' },
  CANCELLED: { label: 'Dibatalkan', tone: 'neutral' },
  REQUIRES_ATTENTION: { label: 'Perlu perhatian', tone: 'critical' },
}

const dateTime = (value: string) => new Date(value).toLocaleString('id-ID')

export function PortalOrdersPage() {
  const [orders, setOrders] = useState<readonly PortalOrder[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setError(null)
      setOrders(await getPortalOrders())
    } catch (caught) {
      setOrders(null)
      setError(caught instanceof PortalApiError ? caught.message : 'Pesanan belum dapat dimuat')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  if (orders === null && error === null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
        <Spinner />
      </div>
    )
  }

  if (error) {
    return (
      <div className="stack" style={{ gap: '1rem' }}>
        <div>
          <Text as="h1" className="page-title" size={700} weight="semibold">Pesanan saya</Text>
          <Text as="p" className="page-sub" size={300}>Ikuti status pemasangan atau perubahan layanan.</Text>
        </div>
        <div className="card stack" style={{ gap: '0.75rem' }} role="alert">
          <Text as="strong" className="error">Pesanan belum dapat dimuat</Text>
          <Text as="p" className="muted" style={{ margin: 0 }}>{error}</Text>
          <button type="button" className="ghost small" onClick={() => void load()}>Coba lagi</button>
        </div>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div>
        <Text as="h1" className="page-title" size={700} weight="semibold">Pesanan saya</Text>
        <Text as="p" className="page-sub" size={300}>Ikuti status pemasangan atau perubahan layanan tanpa melihat data operasional internal.</Text>
      </div>

      {orders?.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Belum ada pesanan"
            hint="Pesanan pemasangan atau perubahan layanan akan muncul di sini."
            icon={<IconWorkOrder size={32} />}
          />
        </div>
      ) : (
        orders?.map((order) => <PortalOrderCard key={order.id} order={order} />)
      )}
    </div>
  )
}

function PortalOrderCard({ order }: { order: PortalOrder }) {
  const presentation = ORDER_STATUS[order.status]
  const address = [order.serviceAddress.address, order.serviceAddress.city, order.serviceAddress.postalCode]
    .filter(Boolean)
    .join(', ')

  return (
    <article className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread wrap" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <div className="stack" style={{ gap: '0.15rem' }}>
          <Text as="h2" size={400} weight="semibold">Pesanan layanan</Text>
          <Text as="span" className="muted" size={200}>Alamat layanan: {address}</Text>
        </div>
        <Badge tone={presentation.tone}>{presentation.label}</Badge>
      </div>
      <div className="hr" />
      <div className="stack" style={{ gap: '0.35rem' }}>
        {order.lines.map((line) => (
          <Text as="span" key={`${line.catalogItemId}:${line.description}`} size={300}>
            {line.quantity}× {line.description}
          </Text>
        ))}
      </div>
      {order.appointment && (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Jadwal: {dateTime(order.appointment.startsAt)} - {dateTime(order.appointment.endsAt)}
        </Text>
      )}
    </article>
  )
}
