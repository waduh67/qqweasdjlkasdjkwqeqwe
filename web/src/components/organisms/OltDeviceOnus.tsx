import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { RefreshCw } from 'lucide-react'
import { ApiError } from '@/api/client'
import { getCachedOltOnus, InvalidOltOnusResponseError, readCachedOltOnus, type OltDeviceOnu, type OltOnusSnapshot } from '@/api/oltOnus'
import { Badge, Button, EmptyState, Spinner, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { DataTable, type Column } from './DataTable'
import './OltDeviceOnus.css'

type ReadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'success'; readonly snapshot: OltOnusSnapshot }
  | { readonly kind: 'error'; readonly message: string }

type DeviceColumn = {
  readonly key: Exclude<keyof OltDeviceOnu, 'index'>
  readonly header: string
  readonly width: string
  readonly render?: (row: OltDeviceOnu) => ReactNode
}

const deviceColumns: readonly DeviceColumn[] = [
  { key: 'ontId', header: 'ONT ID', width: '5.5rem' },
  { key: 'name', header: 'Name', width: '10rem' },
  { key: 'serialNumber', header: 'Serial number', width: '10rem' },
  { key: 'state', header: 'State', width: '7rem' },
  {
    key: 'runningState', header: 'Running state', width: '8.5rem',
    render: (row) => row.runningState === null ? '—' : <StatusBadge status={row.runningState} label={row.runningState} />,
  },
  { key: 'configState', header: 'Config state', width: '8rem' },
  { key: 'deviceType', header: 'Device type', width: '8rem' },
  { key: 'rxPowerDbm', header: 'Receive power', width: '8rem', render: (row) => row.rxPowerDbm === null ? '—' : row.rxPowerDbm + ' dBm' },
  { key: 'lastUpTime', header: 'Last up time', width: '12rem' },
  { key: 'lastDownTime', header: 'Last down time', width: '12rem' },
  { key: 'lastDownCause', header: 'Last down cause', width: '11rem' },
]

export function OltDeviceOnus({ oltId }: { oltId: string }) {
  return <DeviceSnapshot key={oltId} oltId={oltId} />
}

function DeviceSnapshot({ oltId }: { oltId: string }) {
  const [state, setState] = useState<ReadState>(() => {
    const snapshot = getCachedOltOnus(oltId)
    return snapshot ? { kind: 'success', snapshot } : { kind: 'loading' }
  })
  const [readVersion, setReadVersion] = useState(0)
  const [query, setQuery] = useState('')

  useEffect(() => {
    let cancelled = false
    // Deferral avoids a duplicate device read during StrictMode effect replay.
    void Promise.resolve().then(async () => {
      if (cancelled) return
      try {
        const snapshot = await readCachedOltOnus(oltId, readVersion > 0)
        if (!cancelled) setState({ kind: 'success', snapshot })
      } catch (error: unknown) {
        if (cancelled) return
        const message = error instanceof ApiError || error instanceof InvalidOltOnusResponseError
          ? error.message
          : 'Tidak dapat membaca ONU dari OLT. Periksa koneksi lalu tekan Refresh.'
        setState({ kind: 'error', message })
      }
    })
    return () => { cancelled = true }
  }, [oltId, readVersion])

  const refresh = () => {
    setState({ kind: 'loading' })
    setReadVersion((version) => version + 1)
  }

  return (
    <section className="olt-device-onus stack" aria-label="ONU di OLT">
      <div className="olt-device-onus__heading">
        <div className="olt-device-onus__summary stack">
          <Text as="h2" size={400} weight="semibold">ONU di OLT</Text>
          <Text as="p" size={300} className="muted">Hasil baca perangkat, terlepas dari data pelanggan.</Text>
        </div>
        <div className="row wrap">
          <Badge>Hanya baca</Badge>
          <Button icon={<RefreshCw size={16} />} disabled={state.kind === 'loading'} onClick={refresh}>Refresh</Button>
        </div>
      </div>
      <ReadResult state={state} query={query} onQueryChange={setQuery} />
    </section>
  )
}

function ReadResult({ state, query, onQueryChange }: {
  state: ReadState
  query: string
  onQueryChange: (value: string) => void
}) {
  switch (state.kind) {
    case 'loading':
      return <div className="row" aria-live="polite"><Spinner /><Text>Membaca ONU dari OLT…</Text></div>
    case 'error':
      return (
        <div className="workspace-callout critical stack" role="alert">
          <Text weight="semibold">Gagal membaca ONU di OLT</Text>
          <Text>{state.message}</Text>
          <Text size={200}>Tidak ada snapshot yang ditampilkan. Tekan Refresh untuk membaca ulang.</Text>
        </div>
      )
    case 'success':
      return <SnapshotResults snapshot={state.snapshot} query={query} onQueryChange={onQueryChange} />
  }
}

function SnapshotResults({ snapshot, query, onQueryChange }: {
  snapshot: OltOnusSnapshot
  query: string
  onQueryChange: (value: string) => void
}) {
  const search = query.trim().toLowerCase()
  const rows = snapshot.onus.filter((row) => [row.serialNumber, row.name, row.ontId]
    .some((value) => value?.toLowerCase().includes(search)))
  const visibleColumns = deviceColumns.filter((column) => snapshot.onus.some((row) => {
    const value = row[column.key]
    return value !== null && (typeof value !== 'string' || value.trim() !== '')
  }))
  const columns: Column<OltDeviceOnu>[] = visibleColumns.map((column) => ({
    key: column.key,
    header: column.header,
    cell: (row) => (
      <>
        <span className="olt-device-onus__label">{column.header}</span>
        <span className="olt-device-onus__value">{column.render ? column.render(row) : row[column.key] ?? '—'}</span>
      </>
    ),
  }))
  const tableStyle: CSSProperties & { '--olt-device-onus-columns': string } = {
    '--olt-device-onus-columns': visibleColumns.map((column) => column.width).join(' '),
  }

  return (
    <>
      <div className="olt-device-onus__source stack">
        <Text size={300} weight="semibold">Sumber: {snapshot.oltCode} · {snapshot.vendor}</Text>
        <Text size={200}>{snapshot.systemDescription ?? '—'}</Text>
        <Text as="p" size={200}>Waktu baca aplikasi: <time dateTime={snapshot.readAt}>{snapshot.readAt}</time></Text>
        <Text as="p" size={200} className="muted">Cache 15 menit. Refresh membaca ulang dari OLT.</Text>
        <Text as="p" size={200} className="muted">Last up/down time mengikuti jam OLT, ditampilkan apa adanya tanpa konversi zona waktu.</Text>
      </div>
      <Toolbar>
        <TextField
          type="search"
          label="Cari serial, nama, atau ONT ID"
          value={query}
          onChange={(_, data) => onQueryChange(data.value)}
        />
        <Text size={200} className="muted" role="status">{rows.length} dari {snapshot.onus.length} ONU pada hasil baca ini</Text>
      </Toolbar>
      {rows.length > 0 ? (
        <div className="olt-device-onus__table" style={tableStyle}>
          <DataTable columns={columns} rows={rows} rowKey={(row) => row.index} />
        </div>
      ) : (
        <EmptyState
          title="Tidak ditemukan pada hasil baca ini"
          hint={search ? 'Periksa kata pencarian atau tekan Refresh untuk membaca ulang.' : 'Hasil ini bukan kepastian bahwa ONU tidak ada pada perangkat. Tekan Refresh untuk membaca ulang.'}
        />
      )}
    </>
  )
}
