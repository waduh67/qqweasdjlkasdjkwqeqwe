import { usePortalData } from './PortalLayout'
import { Loading, Unavailable, fmtUptime } from './portalFormat'
import { Text } from '@fluentui/react-components'
import { StatusBadge } from '@/components/atoms'
import { Ess } from '@/components/molecules'

/** Keadaan sambungan: satu sesi PPPoE yang berlaku + perangkat yang terpantau di rumah. */
export function PortalKoneksiPage() {
  const { connection, ready } = usePortalData()
  if (!connection) return ready ? <Unavailable what="Data koneksi" /> : <Loading />
  const s = connection.session

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="stack" style={{ gap: '0.15rem' }}>
        <Text as="h1" className="page-title" size={700} weight="semibold" style={{ margin: 0 }}>Koneksi</Text>
        <Text as="p" className="page-sub" size={400} style={{ margin: 0 }}>Keadaan sambungan internet dan perangkat di rumahmu.</Text>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Sesi internet</Text>
        {!s ? (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Belum ada sesi PPPoE.</Text>
        ) : (
          <dl className="essentials wide">
            <Ess label="Status">
              <StatusBadge status={s.online ? 'ONLINE' : 'OFFLINE'} />
            </Ess>
            <Ess label="Username">
              <Text as="span" className="tnum">{s.username}</Text>
            </Ess>
            <Ess label="Menyala selama">{fmtUptime(s.uptimeSeconds)}</Ess>
            <Ess label="Alamat IP">
              <Text as="span" className="tnum">{s.framedIp ?? '—'}</Text>
            </Ess>
            <Ess label="Paket">{s.planName ?? '—'}</Ess>
          </dl>
        )}
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Perangkat</Text>
        {connection.devices.length === 0 ? (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Tak ada perangkat terpantau.</Text>
        ) : (
          connection.devices.map((d) => (
            <div key={d.deviceId} className="spread" style={{ alignItems: 'center' }}>
              <div className="stack" style={{ gap: 2 }}>
                <Text as="span" weight="semibold">{[d.manufacturer, d.model].filter(Boolean).join(' ') || d.serialNumber}</Text>
                <Text as="span" className="muted tnum" size={200}>{d.serialNumber}</Text>
              </div>
              <StatusBadge status={d.online ? 'ONLINE' : 'OFFLINE'} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}
