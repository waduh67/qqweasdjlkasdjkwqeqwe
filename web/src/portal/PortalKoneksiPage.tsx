import { usePortalData } from './PortalLayout'
import { Loading, Unavailable, fmtUptime } from './portalFormat'
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
        <h1 className="page-title" style={{ margin: 0 }}>Koneksi</h1>
        <p className="page-sub" style={{ margin: 0 }}>Keadaan sambungan internet dan perangkat di rumahmu.</p>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Sesi internet</strong>
        {!s ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada sesi PPPoE.</p>
        ) : (
          <dl className="essentials wide">
            <Ess label="Status">
              <StatusBadge status={s.online ? 'ONLINE' : 'OFFLINE'} />
            </Ess>
            <Ess label="Username">
              <span className="tnum">{s.username}</span>
            </Ess>
            <Ess label="Menyala selama">{fmtUptime(s.uptimeSeconds)}</Ess>
            <Ess label="Alamat IP">
              <span className="tnum">{s.framedIp ?? '—'}</span>
            </Ess>
            <Ess label="Paket">{s.planName ?? '—'}</Ess>
          </dl>
        )}
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Perangkat</strong>
        {connection.devices.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada perangkat terpantau.</p>
        ) : (
          connection.devices.map((d) => (
            <div key={d.deviceId} className="spread" style={{ alignItems: 'center' }}>
              <div className="stack" style={{ gap: 2 }}>
                <span style={{ fontWeight: 600 }}>
                  {[d.manufacturer, d.model].filter(Boolean).join(' ') || d.serialNumber}
                </span>
                <span className="muted tnum" style={{ fontSize: '0.8rem' }}>{d.serialNumber}</span>
              </div>
              <StatusBadge status={d.online ? 'ONLINE' : 'OFFLINE'} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}
