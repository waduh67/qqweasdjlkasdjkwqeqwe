import { useEffect, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { PageResponse } from '../api/types'
import type { AlarmView, MonitoringDashboard } from '../api/monitoring'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Button, StatusBadge } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { IconCustomers, IconInventory, IconMap, IconMonitor, IconWorkOrder, type IconProps } from '@/components/atoms/icons'
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
  const [errors, setErrors] = useState<string[]>([])
  const [freshness, setFreshness] = useState(0)
  const [monitoring, setMonitoring] = useState<MonitoringDashboard | null>(null)
  const [counts, setCounts] = useState<{ olts?: number; odps?: number; customers?: number }>({})

  useEffect(() => {
    let active = true
    setErrors([])
    setMonitoring(null)
    setCounts({})
    if (can('monitoring.dashboard.view')) {
      void api.get<MonitoringDashboard>('/api/monitoring/dashboard').then(data => { if (active) setMonitoring(data) }).catch(() => { if (active) setErrors(previous => [...previous, 'Monitoring']) })
    }
    // size=1 hanya untuk membaca totalElements — murah.
    const load = async (path: string, key: 'olts' | 'odps' | 'customers') =>
      api
        .get<PageResponse<unknown>>(`${path}?size=1`)
        .then((page) => { if (active) setCounts((c) => ({ ...c, [key]: page.totalElements })) })
        .catch(() => { if (active) setErrors(previous => [...previous, key === 'customers' ? 'Pelanggan' : key === 'olts' ? 'OLT' : 'ODP']) })
    if (can('network.olt.view')) void load('/api/olts', 'olts')
    if (can('network.odp.view')) void load('/api/odps', 'odps')
    if (can('customer.customer.view')) void load('/api/customers', 'customers')
    return () => { active = false }
  }, [can, freshness])

  const hour = new Date().getHours()
  const greeting = hour < 11 ? 'Selamat pagi' : hour < 15 ? 'Selamat siang' : hour < 19 ? 'Selamat sore' : 'Selamat malam'

  return (
    <div className="stack tenant-dashboard">
      <PageHeader
        title="Dashboard operasional"
        subtitle={<>{greeting}, {user?.name?.split(' ')[0]}. Berikut kondisi layanan {user?.tenantSlug}.</>}
      />

      {errors.length > 0 && <div className="card load-error" role="alert"><div><strong>Sebagian ringkasan belum tersedia</strong><p>Gagal memuat: {errors.join(', ')}.</p></div><Button onClick={() => setFreshness(value => value + 1)}>Coba lagi</Button></div>}
      {monitoring && monitoring.collectors === 0 && <div className="card form-note"><strong>Monitoring belum menerima data</strong><p>Hubungkan collector untuk mulai memantau perangkat dan alarm jaringan.</p><Link to="/monitoring" className="text-action">Buka monitoring</Link></div>}
      {monitoring && monitoring.collectors > 0 && (
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

      <div className="stack dashboard-sections">
        {monitoring && (
          <div className="card pad-0">
            <div className="card-head">
              <Text as="h3" weight="semibold">Alarm terbaru</Text>
              <Link to="/monitoring">
                <Text size={200}>Lihat semua →</Text>
              </Link>
            </div>
            {monitoring.recentAlarms.length === 0 ? (
              <div className="card-body muted">Belum ada alarm tercatat.</div>
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
                        <Text as="span" size={300} >{alarm.entityLabel}</Text>
                        <Text as="span" className="muted" size={200} >
                          {' · '}{alarm.kindDescription}
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

        <div className="card">
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Pekerjaan sehari-hari</Text>
          <div className="dashboard-actions">
            <QuickLink to="/my-work-orders" icon={IconWorkOrder} label="Tugas Saya" hint="Jadwal dan pekerjaan lapangan" show={can('workorder.order.field')} />
            <QuickLink to="/warehouse" icon={IconInventory} label="Gudang" hint="Stok, penerimaan, dan pengeluaran barang" show={can('inventory.item.view')} />
            <QuickLink to="/map" icon={IconMap} label="Peta jaringan" hint="Lihat ODP & pelanggan di peta" show={can('gis.map.view')} />
            <QuickLink to="/inventory" icon={IconInventory} label="Aset jaringan" hint="Kelola OLT, ODC, ODP, kabel" show={can('network.odp.view')} />
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
      className="dashboard-quick-link"
    >
      <span className="avatar" aria-hidden style={{ borderRadius: 8 }}>
        <Icon size={17} />
      </span>
      <span className="dashboard-quick-link-text">
        <Text as="span" weight="semibold" size={300} >{label}</Text>
        <Text as="span" className="muted" size={200} >
          {hint}
        </Text>
      </span>
    </Link>
  )
}
