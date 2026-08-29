import { useEffect, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { PageResponse } from '../api/types'
import type { AlarmView, MonitoringDashboard } from '../api/monitoring'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { StatusBadge } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { IconCustomers, IconInventory, IconMap, IconMonitor, type IconProps } from '@/components/atoms/icons'
import type { ComponentType } from 'react'

/**
 * Ringkasan operasional, bukan lagi placeholder.
 *
 * Setiap kartu hanya dimuat bila penggunanya berizin — dashboard seorang
 * teknisi area akan berbeda dari admin tenant, dan itu memang seharusnya. Angka
 * yang paling menuntut perhatian (alarm kritis, collector membisu) ditaruh paling
 * atas dengan penanda status, bukan sekadar deret bilangan.
 */
export function DashboardPage() {
  const { user } = useAuth()
  const { can } = useCan()
  const [monitoring, setMonitoring] = useState<MonitoringDashboard | null>(null)
  const [counts, setCounts] = useState<{ olts?: number; odps?: number; customers?: number }>({})

  useEffect(() => {
    if (can('monitoring.dashboard.view')) {
      void api.get<MonitoringDashboard>('/api/monitoring/dashboard').then(setMonitoring).catch(() => {})
    }
    // size=1 hanya untuk membaca totalElements — murah.
    const load = async (path: string, key: 'olts' | 'odps' | 'customers') =>
      api
        .get<PageResponse<unknown>>(`${path}?size=1`)
        .then((page) => setCounts((c) => ({ ...c, [key]: page.totalElements })))
        .catch(() => {})
    if (can('network.olt.view')) void load('/api/olts', 'olts')
    if (can('network.odp.view')) void load('/api/odps', 'odps')
    if (can('customer.customer.view')) void load('/api/customers', 'customers')
  }, [can])

  const hour = new Date().getHours()
  const greeting = hour < 11 ? 'Selamat pagi' : hour < 15 ? 'Selamat siang' : hour < 19 ? 'Selamat sore' : 'Selamat malam'

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <PageHeader
        title={<>{greeting}, {user?.name?.split(' ')[0]}</>}
        subtitle={<>Ringkasan operasi jaringan pada tenant {user?.tenantSlug}.</>}
      />

      {monitoring && (
        <div className="stat-grid">
          <Stat
            label="Alarm aktif"
            value={monitoring.alarms.active}
            note={`${monitoring.alarms.bySeverity.CRITICAL ?? 0} kritis · ${monitoring.alarms.bySeverity.WARNING ?? 0} peringatan`}
            accent={monitoring.alarms.active > 0 ? 'crit' : undefined}
          />
          <Stat
            label="Collector membisu"
            value={monitoring.collectorsSilent}
            note={`dari ${monitoring.collectors} collector`}
            accent={monitoring.collectorsSilent > 0 ? 'warn' : undefined}
          />
          <Stat label="Metrik 24 jam" value={monitoring.metricsLast24h} note="pembacaan ONU tersimpan" />
        </div>
      )}

      <div className="stat-grid">
        {counts.olts != null && <Stat label="OLT" value={counts.olts} />}
        {counts.odps != null && <Stat label="ODP" value={counts.odps} />}
        {counts.customers != null && <Stat label="Pelanggan" value={counts.customers} />}
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        {monitoring && (
          <div className="card pad-0 grow" style={{ minWidth: 320 }}>
            <div className="card-head">
              <Text as="h3" weight="semibold">Alarm terbaru</Text>
              <Link to="/monitoring">
                <Text size={200}>Lihat semua →</Text>
              </Link>
            </div>
            {monitoring.recentAlarms.length === 0 ? (
              <div className="card-body muted">Tidak ada alarm. Jaringan tenang.</div>
            ) : (
              <Table aria-label="Alarm terbaru">
                <TableHeader>
                  <TableRow>
                    <TableHeaderCell aria-label="Tingkat alarm" />
                    <TableHeaderCell>Alarm</TableHeaderCell>
                    <TableHeaderCell style={{ textAlign: 'right' }}>Durasi</TableHeaderCell>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {monitoring.recentAlarms.slice(0, 6).map((alarm: AlarmView) => (
                    <TableRow key={alarm.id}>
                      <TableCell style={{ width: '1%' }}><StatusBadge status={alarm.severity} /></TableCell>
                      <TableCell>
                        <Text as="span" size={300} style={{ display: 'block' }}>{alarm.entityLabel}</Text>
                        <Text as="span" className="muted" size={200} style={{ display: 'block' }}>
                          {alarm.kindDescription}
                        </Text>
                      </TableCell>
                      <TableCell className="muted" style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <Text size={200}>
                          {alarm.openMinutes < 60
                            ? `${alarm.openMinutes} mnt`
                            : `${Math.floor(alarm.openMinutes / 60)} jam`}
                        </Text>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        )}

        <div className="card grow" style={{ minWidth: 260 }}>
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Pintasan</Text>
          <div className="stack" style={{ gap: '0.5rem' }}>
            <QuickLink to="/map" icon={IconMap} label="Peta jaringan" hint="Lihat ODP & pelanggan di peta" show={can('gis.map.view')} />
            <QuickLink to="/inventory" icon={IconInventory} label="Inventory" hint="Kelola OLT, ODC, ODP, kabel" show={can('network.odp.view')} />
            <QuickLink to="/customers" icon={IconCustomers} label="Pelanggan" hint="Pasang ONU, telusur jalur" show={can('customer.customer.view')} />
            <QuickLink to="/monitoring" icon={IconMonitor} label="Monitoring" hint="Collector, alarm, redaman" show={can('monitoring.dashboard.view')} />
          </div>
        </div>
      </div>
    </div>
  )
}

function Stat({
  label,
  value,
  note,
  accent,
}: {
  label: string
  value: number
  note?: string
  accent?: 'crit' | 'warn'
}) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}

function QuickLink({
  to,
  icon: Icon,
  label,
  hint,
  show,
}: {
  to: string
  icon: ComponentType<IconProps>
  label: string
  hint: string
  show: boolean
}) {
  if (!show) return null
  return (
    <Link
      to={to}
      className="row"
      style={{ gap: '0.7rem', padding: '0.55rem 0.6rem', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }}
    >
      <span className="avatar" aria-hidden style={{ borderRadius: 8 }}>
        <Icon size={17} />
      </span>
      <span>
        <Text as="span" weight="semibold" size={300} style={{ display: 'block' }}>{label}</Text>
        <Text as="span" className="muted" size={200} style={{ display: 'block' }}>
          {hint}
        </Text>
      </span>
    </Link>
  )
}
