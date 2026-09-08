import { useEffect, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { RefreshCw } from 'lucide-react'
import { ApiError } from '@/api/client'
import { InvalidOltOnusResponseError, readOltOnus, type OltDeviceOnu, type OltOnusSnapshot } from '@/api/oltOnus'
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
  readonly render?: (row: OltDeviceOnu) => ReactNode
}

const deviceColumns: readonly DeviceColumn[] = [
  { key: 'ontId', header: 'ONT ID' },
  { key: 'name', header: 'Name' },
  { key: 'serialNumber', header: 'Serial number' },
  { key: 'state', header: 'State' },
  {
    key: 'runningState', header: 'Running state',
    render: (row) => row.runningState === null ? '—' : <StatusBadge status={row.runningState} label={row.runningState} />,
  },
  { key: 'configState', header: 'Config state' },
  { key: 'deviceType', header: 'Device type' },
  { key: 'rxPowerDbm', header: 'Receive power', render: (row) => row.rxPowerDbm === null ? '—' : row.rxPowerDbm + ' dBm' },
  { key: 'lastUpTime', header: 'Last up time' },
  { key: 'lastDownTime', header: 'Last down time' },
  { key: 'lastDownCause', header: 'Last down cause' },
]

const columns: Column<OltDeviceOnu>[] = deviceColumns.map((column) => ({
  key: column.key,
  header: column.header,
  cell: (row) => (
    <>
      <span className="olt-device-onus__label">{column.header}</span>
      <span className="olt-device-onus__value">{column.render ? column.render(row) : row[column.key] ?? '—'}</span>
    </>
  ),
}))

export function OltDeviceOnus({ oltId }: { oltId: string }) {
  return <DeviceSnapshot key={oltId} oltId={oltId} />
}

function DeviceSnapshot({ oltId }: { oltId: string }) {
  const [state, setState] = useState<ReadState>({ kind: 'loading' })
  const [readVersion, setReadVersion] = useState(0)
  const [query, setQuery] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    // Deferral avoids a duplicate device read during StrictMode effect replay.
    void Promise.resolve().then(async () => {
      if (controller.signal.aborted) return
      try {
        const snapshot = await readOltOnus(oltId, controller.signal)
        if (!controller.signal.aborted) setState({ kind: 'success', snapshot })
      } catch (error: unknown) {
        if (controller.signal.aborted) return
        const message = error instanceof ApiError || error instanceof InvalidOltOnusResponseError
          ? error.message
          : 'Tidak dapat membaca ONU dari OLT. Periksa koneksi lalu tekan Refresh.'
        setState({ kind: 'error', message })
      }
    })
    return () => controller.abort()
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
  const hasUnsupportedFields = snapshot.systemDescription === null || snapshot.onus
    .some((row) => deviceColumns.some((column) => row[column.key] === null))

  return (
    <>
      <div className="olt-device-onus__source stack">
        <Text size={300} weight="semibold">Sumber: {snapshot.oltCode} · {snapshot.vendor}</Text>
        <Text size={200}>{snapshot.systemDescription ?? '—'}</Text>
        <Text as="p" size={200}>Waktu baca aplikasi: <time dateTime={snapshot.readAt}>{snapshot.readAt}</time></Text>
        <Text as="p" size={200} className="muted">Last up/down time mengikuti jam OLT, ditampilkan apa adanya tanpa konversi zona waktu.</Text>
      </div>
      {(hasUnsupportedFields || snapshot.warnings.length > 0) && (
        <div className="workspace-callout warning stack" role="note" aria-label="Peringatan hasil baca">
          <Text size={300}>Field yang tidak tersedia atau belum didukung ditampilkan sebagai —. Hasil baca dapat parsial.</Text>
          {snapshot.warnings.length > 0 && <ul>{snapshot.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>}
        </div>
      )}
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
        <DataTable columns={columns} rows={rows} rowKey={(row) => row.index} />
      ) : (
        <EmptyState
          title="Tidak ditemukan pada hasil baca ini"
          hint={search ? 'Periksa kata pencarian atau tekan Refresh untuk membaca ulang.' : 'Hasil ini bukan kepastian bahwa ONU tidak ada pada perangkat. Periksa peringatan atau tekan Refresh untuk membaca ulang.'}
        />
      )}
    </>
  )
}
