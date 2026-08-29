import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { Download, DoorOpen, KeyRound, Network, Power, PowerOff, Trash2 } from 'lucide-react'
import { ApiError } from '../api/client'
import {
  addAccountForward,
  addAccountRoute,
  deleteAccount,
  disableAccount,
  downloadAccountOvpn,
  downloadAccountRouterOs,
  enableAccount,
  generateAccount,
  listAccounts,
  removeAccountForward,
  removeAccountRoute,
  renameAccountRoute,
  retargetAccountForward,
  rotateAccountPassword,
  type VpnAccountView,
  type VpnForwardProtocol,
  type VpnPortForwardView,
  type VpnRoutedSubnetView,
} from '../api/vpn'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { Modal, SearchInput } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconPlus } from '@/components/atoms/icons'
import { blokFirewallScript, isCidrLike, ovpnInterfaceName } from '@/utils/blokPelanggan'

/**
 * Akun VPN (tenant). Alur unggulan satu klik: tekan Generate → sistem meng-AUTO-ASSIGN akun
 * ke server VPN platform yang tersedia dan menampilkan kredensial siap tempel ke Mikrotik
 * (host:port, protokol, tipe keamanan, username, password). Tenant tak pernah memilih/melihat
 * server — itu urusan admin platform. Password hanya tampil sekali (saat generate/rotasi) atau
 * lewat unduh .ovpn/RouterOS.
 */

/** Hook pemuat daftar bersama untuk endpoint yang mengembalikan array polos. */
function useResource<T>(fetcher: () => Promise<T[]>) {
  const toast = useToast()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      setItems(await fetcher())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data')
    } finally {
      setLoading(false)
    }
  }, [fetcher, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      return false
    }
  }

  return { items, loading, reload, run }
}

/** Unduh Blob teks sebagai berkas di browser. Konten butuh header Bearer → diambil dulu, lalu object URL. */
async function saveBlob(fetchBlob: () => Promise<Blob>, filename: string, onError: (msg: string) => void) {
  try {
    const blob = await fetchBlob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (err) {
    onError(err instanceof ApiError ? err.message : 'Gagal mengunduh berkas')
  }
}

function fmtWhen(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('id-ID', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Layanan yang lazim di-remote pada perangkat pelanggan. Dipakai untuk MENGISI-OTOMATIS port +
 * protokol (dan menebak label) — kolomnya tetap boleh diketik manual, karena banyak teknisi
 * memindah port bawaan demi keamanan. Cermin daftar `WELL_KNOWN` di sisi server.
 */
const SERVICE_PRESETS: { label: string; devicePort: number; protocol: VpnForwardProtocol }[] = [
  { label: 'Winbox', devicePort: 8291, protocol: 'TCP' },
  { label: 'API', devicePort: 8728, protocol: 'TCP' },
  { label: 'API-SSL', devicePort: 8729, protocol: 'TCP' },
  { label: 'SSH', devicePort: 22, protocol: 'TCP' },
  { label: 'Telnet', devicePort: 23, protocol: 'TCP' },
  { label: 'WebFig', devicePort: 80, protocol: 'TCP' },
  { label: 'WebFig HTTPS', devicePort: 443, protocol: 'TCP' },
  { label: 'SNMP', devicePort: 161, protocol: 'UDP' },
  { label: 'Bandwidth test', devicePort: 2000, protocol: 'TCP' },
]

/** Batas pintu per akun — sama dengan `VpnPortForward.MAX_PER_PEER` di server. */
const MAX_FORWARDS = 10

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'ENABLED', label: 'Aktif' },
  { value: 'DISABLED', label: 'Nonaktif' },
]

export function VpnPage() {
  const { can } = useCan()
  const canManage = can('vpn.peer.manage')
  const canConfig = can('vpn.config.view')
  const toast = useToast()
  const confirm = useConfirm()
  const { items: accounts, loading, reload, run } = useResource(listAccounts)

  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)
  // Kredensial (dengan password) hanya tampil sekali — setelah generate/rotasi.
  const [fresh, setFresh] = useState<VpnAccountView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  // Akun yang sedang dibuka panel port remote-nya.
  const [forwardsOf, setForwardsOf] = useState<VpnAccountView | null>(null)
  // Akun yang sedang dibuka panel blok pelanggan di belakangnya.
  const [routesOf, setRoutesOf] = useState<VpnAccountView | null>(null)

  const generate = () => {
    setBusy(true)
    void run(async () => {
      const account = await generateAccount({ label: label.trim() || null })
      setFresh(account)
      setLabel('')
    }, 'Akun VPN dibuat — salin kredensial di bawah').finally(() => setBusy(false))
  }

  const toggle = (a: VpnAccountView) =>
    void run(
      () => (a.status === 'ENABLED' ? disableAccount(a.id) : enableAccount(a.id)),
      a.status === 'ENABLED' ? 'Akun dinonaktifkan' : 'Akun diaktifkan',
    )

  const rotate = (a: VpnAccountView) => {
    void (async () => {
      if (
        !(await confirm({
          title: `Rotasi password akun “${a.label}”`,
          message: `Password lama untuk akun “${a.label}” langsung tidak berlaku. Perbarui konfigurasi RouterOS.`,
          confirmLabel: 'Rotasi password',
        }))
      )
        return
      void run(async () => {
        setFresh(await rotateAccountPassword(a.id))
      }, 'Password dirotasi — salin yang baru di bawah')
    })()
  }

  const remove = (a: VpnAccountView) => {
    void (async () => {
      if (
        !(await confirm({
          title: `Hapus akun “${a.label}”`,
          message: `Hapus akun “${a.label}”? Koneksi RouterOS dengan akun ini akan terputus.`,
          confirmLabel: 'Hapus akun',
          danger: true,
        }))
      )
        return
      void run(() => deleteAccount(a.id), 'Akun dihapus')
    })()
  }

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return accounts.filter((a) => {
      if (statusFilter && a.status !== statusFilter) return false
      if (!q) return true
      // Blok ikut dicari: saat menelusuri IP pelanggan yang bermasalah, yang diketahui operator
      // justru alamat kolamnya — bukan label akun VPN yang menaunginya.
      return [
        a.label,
        a.serverName,
        a.host,
        a.username,
        a.overlayIp,
        a.winboxAddress,
        ...a.routes.map((r) => r.cidr),
      ].some((v) => v?.toLowerCase().includes(q))
    })
  }, [accounts, query, statusFilter])

  const rowActions = (a: VpnAccountView): RowAction[] => {
    const list: RowAction[] = []
    if (canConfig) {
      list.push({
        key: 'routeros',
        label: 'Unduh RouterOS',
        icon: <Download size={16} />,
        onClick: () => void saveBlob(() => downloadAccountRouterOs(a.id), `${a.username}.rsc`, toast.error),
      })
      list.push({
        key: 'ovpn',
        label: 'Unduh .ovpn',
        icon: <Download size={16} />,
        onClick: () => void saveBlob(() => downloadAccountOvpn(a.id), `${a.username}.ovpn`, toast.error),
      })
    }
    if (canManage) {
      list.push({
        key: 'forwards',
        label: 'Port remote',
        icon: <DoorOpen size={16} />,
        onClick: () => setForwardsOf(a),
      })
      list.push({
        key: 'routes',
        label: 'Blok pelanggan',
        icon: <Network size={16} />,
        onClick: () => setRoutesOf(a),
      })
      list.push({
        key: 'toggle',
        label: a.status === 'ENABLED' ? 'Nonaktifkan' : 'Aktifkan',
        icon: a.status === 'ENABLED' ? <PowerOff size={16} /> : <Power size={16} />,
        onClick: () => toggle(a),
      })
      list.push({ key: 'rotate', label: 'Rotasi password', icon: <KeyRound size={16} />, onClick: () => rotate(a) })
      list.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => remove(a) })
    }
    return list
  }

  const columns: Column<VpnAccountView>[] = [
    {
      key: 'label',
      header: 'Nama',
      sortValue: (a) => a.label,
      cell: (a) => a.label,
      inlineActions: canConfig || canManage ? rowActions : undefined,
    },
    {
      key: 'server',
      header: 'Server',
      sortValue: (a) => a.serverName,
      cell: (a) => a.serverName,
    },
    {
      key: 'host',
      header: 'Host',
      sortValue: (a) => a.host,
      cell: (a) => <span className="tnum">{a.host}</span>,
    },
    {
      key: 'port',
      header: 'Port',
      sortValue: (a) => a.port,
      cell: (a) => <span className="tnum">{a.port}</span>,
    },
    {
      key: 'protocol',
      header: 'Protokol',
      sortValue: (a) => a.protocol,
      cell: (a) => a.protocol,
    },
    {
      key: 'username',
      header: 'Username',
      sortValue: (a) => a.username,
      cell: (a) => a.username,
    },
    {
      key: 'overlayIp',
      header: 'IP overlay',
      sortValue: (a) => a.overlayIp,
      cell: (a) => <span className="tnum">{a.overlayIp}</span>,
    },
    {
      key: 'blocks',
      header: 'Blok pelanggan',
      sortValue: (a) => a.routes.length,
      cell: (a) => a.routes.length === 0
        ? '—'
        : `${a.routes[0].cidr}${a.routes.length > 1 ? ` +${a.routes.length - 1}` : ''}`,
    },
    {
      key: 'remoteAddress',
      header: 'Alamat remote',
      sortValue: (a) => a.winboxAddress ?? '',
      cell: (a) => <span className="tnum">{a.winboxAddress ?? '—'}</span>,
    },
    {
      key: 'forwards',
      header: 'Pintu remote',
      sortValue: (a) => a.forwards.length,
      cell: (a) => a.forwards.length === 0
        ? '—'
        : `${a.forwards[0].label}${a.forwards.length > 1 ? ` +${a.forwards.length - 1}` : ''}`,
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (a) => a.status,
      cell: (a) => (
        <StatusBadge
          status={a.status === 'ENABLED' ? 'ACTIVE' : 'DISABLED'}
          label={a.status === 'ENABLED' ? 'aktif' : 'nonaktif'}
        />
      ),
    },
    {
      key: 'connection',
      header: 'Koneksi',
      sortValue: (a) => (a.online ? 1 : 0),
      cell: (a) => <LiveIndicator online={a.online} lastHandshakeAt={a.lastHandshakeAt} />,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader title="Akun VPN" />

      {canManage && (
        <div className="card stack" style={{ gap: '0.75rem' }}>
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <TextField
              label="Label (opsional)"
              value={label}
              onChange={(_, data) => setLabel(data.value)}
              onKeyDown={(e) => e.key === 'Enter' && !busy && generate()}
              placeholder="mis. Mikrotik Bekasi"
              style={{ flex: 1 }}
            />
            <Button variant="primary" onClick={generate} disabled={busy}>
              <IconPlus size={15} /> {busy ? 'Membuat…' : 'Generate akun'}
            </Button>
          </div>
        </div>
      )}

      {fresh && <CredentialCard account={fresh} onDismiss={() => setFresh(null)} />}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari label, server, username, atau IP…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(a) => a.id}
        loading={loading}
        initialSort={{ key: 'label', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada akun yang cocok' : 'Belum ada akun VPN'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : undefined
            }
          />
        }
      />

      {forwardsOf && (
        <PortForwardModal
          account={forwardsOf}
          onClose={() => {
            setForwardsOf(null)
            void reload()
          }}
        />
      )}

      {routesOf && (
        <RoutedSubnetModal
          account={routesOf}
          onClose={() => {
            setRoutesOf(null)
            void reload()
          }}
        />
      )}
    </div>
  )
}

/* ---------- Panel port remote: pintu-pintu dari internet ke perangkat ---------- */

/**
 * Satu akun boleh punya beberapa pintu: `hub:portPublik` → `perangkat:portPerangkat`. Port
 * publik dipilih sistem dan PERMANEN (alamat yang sudah dipegang teknisi tak boleh bergeser);
 * yang bisa diubah cuma sasarannya di perangkat — inilah yang menyelamatkan perangkat dengan
 * Winbox/API yang portnya sudah dipindah dari bawaan.
 */
function PortForwardModal({ account, onClose }: { account: VpnAccountView; onClose: () => void }) {
  const toast = useToast()
  const confirm = useConfirm()
  const [acct, setAcct] = useState(account)
  const [busy, setBusy] = useState(false)
  const [addDraft, setAddDraft] = useState({ devicePort: '8291', protocol: 'TCP' as VpnForwardProtocol, label: '' })
  const [editing, setEditing] = useState<{ id: string; devicePort: string; protocol: VpnForwardProtocol; label: string } | null>(
    null,
  )

  const apply = (action: () => Promise<VpnAccountView>, ok: string, after?: () => void) => {
    setBusy(true)
    action()
      .then((updated) => {
        setAcct(updated)
        after?.()
        toast.success(ok)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Operasi gagal'))
      .finally(() => setBusy(false))
  }

  const parsePort = (raw: string): number | null => {
    const port = Number(raw)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      toast.error('Port perangkat harus angka 1–65535')
      return null
    }
    return port
  }

  const add = () => {
    const port = parsePort(addDraft.devicePort)
    if (port === null) return
    apply(
      () =>
        addAccountForward(acct.id, {
          devicePort: port,
          protocol: addDraft.protocol,
          label: addDraft.label.trim() || null,
        }),
      'Pintu ditambahkan',
      () => setAddDraft({ devicePort: '8291', protocol: 'TCP', label: '' }),
    )
  }

  const saveEdit = () => {
    if (!editing) return
    const port = parsePort(editing.devicePort)
    if (port === null) return
    apply(
      () =>
        retargetAccountForward(acct.id, editing.id, {
          devicePort: port,
          protocol: editing.protocol,
          label: editing.label.trim() || null,
        }),
      'Pintu diarahkan ulang',
      () => setEditing(null),
    )
  }

  const remove = (f: VpnPortForwardView) => {
    void (async () => {
      if (
        !(await confirm({
          title: `Cabut penerusan “${f.label}”`,
          message: `Cabut penerusan “${f.label}” (${f.address})? Endpoint ini langsung tidak dapat digunakan dan port publiknya dapat dialokasikan ke akun lain.`,
          confirmLabel: 'Cabut penerusan',
          danger: true,
        }))
      )
        return
      apply(() => removeAccountForward(acct.id, f.id), 'Pintu dicabut')
    })()
  }

  const copy = (value: string) => void navigator.clipboard?.writeText(value).then(() => toast.success('Alamat disalin'))

  /** Preset mengisi port + protokol; label dibiarkan kosong supaya server yang menebaknya. */
  const applyPreset = (value: string) => {
    const preset = SERVICE_PRESETS.find((p) => String(p.devicePort) === value)
    if (preset) setAddDraft({ devicePort: String(preset.devicePort), protocol: preset.protocol, label: '' })
  }

  const full = acct.forwards.length >= MAX_FORWARDS

  return (
    <Modal title={`Port remote “${acct.label}”`} onClose={onClose} wide>
      <Table ><TableHeader><TableRow ><TableHeaderCell >Layanan</TableHeaderCell>
      <TableHeaderCell >Alamat publik</TableHeaderCell>
      <TableHeaderCell >Port di perangkat</TableHeaderCell>
      <TableHeaderCell style={{ width: '9rem' }} /></TableRow></TableHeader>
      <TableBody>{acct.forwards.length === 0 && (
        <TableRow ><TableCell colSpan={4} className="muted">
          Belum ada pintu — perangkat hanya terjangkau dari dalam tunnel.
        </TableCell></TableRow>
      )}
      {acct.forwards.map((f) =>
        editing?.id === f.id ? (
          <TableRow key={f.id}><TableCell ><TextField
            value={editing.label}
            onChange={(_, data) => setEditing({ ...editing, label: data.value })}
            placeholder="otomatis"
          /></TableCell>
          <TableCell className="tnum muted">{f.address}</TableCell>
          <TableCell ><div className="row" style={{ gap: '0.35rem' }}>
            <TextField
              value={editing.devicePort}
              onChange={(_, data) => setEditing({ ...editing, devicePort: data.value })}
              style={{ width: '6rem' }}
            />
            <SelectField
              value={editing.protocol}
              onChange={(_, data) => setEditing({ ...editing, protocol: data.value as VpnForwardProtocol })}
            >
              <option value="TCP">TCP</option>
              <option value="UDP">UDP</option>
            </SelectField>
          </div></TableCell>
          <TableCell ><div className="row" style={{ gap: '0.35rem' }}>
            <Button variant="primary" size="small" onClick={saveEdit} disabled={busy}>
              Simpan
            </Button>
            <Button variant="subtle" size="small" onClick={() => setEditing(null)} disabled={busy}>
              Batal
            </Button>
          </div></TableCell></TableRow>
        ) : (
          <TableRow key={f.id}><TableCell ><strong>{f.label}</strong></TableCell>
          <TableCell ><span className="tnum">{f.address}</span>{' '}
          <Button variant="subtle" size="small" onClick={() => copy(f.address)}>
            Salin
          </Button></TableCell>
          <TableCell className="tnum">{f.devicePort} <span className="muted">{f.protocol}</span></TableCell>
          <TableCell ><div className="row" style={{ gap: '0.35rem' }}>
            <Button
              variant="subtle"
              size="small"
              disabled={busy}
              onClick={() =>
                setEditing({
                  id: f.id,
                  devicePort: String(f.devicePort),
                  protocol: f.protocol,
                  label: f.label,
                })
              }
            >
              Ubah
            </Button>
            <Button variant="subtle" size="small" disabled={busy} onClick={() => remove(f)}>
              Cabut
            </Button>
          </div></TableCell></TableRow>
        ),
      )}</TableBody></Table>

      <div className="stack" style={{ gap: '0.4rem', marginTop: '1rem' }}>
        <Text as="strong" size={300}  >Tambah pintu</Text>
        <div className="row" style={{ alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <SelectField label="Layanan" value={addDraft.devicePort} onChange={(_, data) => applyPreset(data.value)}>
            {!SERVICE_PRESETS.some((p) => String(p.devicePort) === addDraft.devicePort) && (
              <option value={addDraft.devicePort}>Lainnya</option>
            )}
            {SERVICE_PRESETS.map((p) => (
              <option key={p.devicePort} value={p.devicePort}>
                {p.label} ({p.devicePort})
              </option>
            ))}
          </SelectField>
          <TextField
            label="Port di perangkat"
            value={addDraft.devicePort}
            onChange={(_, data) => setAddDraft({ ...addDraft, devicePort: data.value })}
            style={{ width: '9rem' }}
          />
          <SelectField
            label="Protokol"
            value={addDraft.protocol}
            onChange={(_, data) => setAddDraft({ ...addDraft, protocol: data.value as VpnForwardProtocol })}
          >
            <option value="TCP">TCP</option>
            <option value="UDP">UDP</option>
          </SelectField>
          <TextField
            label="Nama (opsional)"
            value={addDraft.label}
            onChange={(_, data) => setAddDraft({ ...addDraft, label: data.value })}
            placeholder="otomatis dari port"
            style={{ flex: 1, minWidth: '10rem' }}
          />
          <Button variant="primary" onClick={add} disabled={busy || full}>
            <IconPlus size={15} /> Tambah
          </Button>
        </div>
        {full && (
          <Text as="span" size={300} className="muted">
            Sudah {MAX_FORWARDS} pintu — cabut salah satu dulu.
          </Text>
        )}
      </div>
    </Modal>
  )
}

/* ---------- Panel blok pelanggan: jalan dari server ke perangkat DI BELAKANG tunnel ---------- */

/** Batas blok per akun — sama dengan `VpnPeerRoute.MAX_PER_PEER` di server. */
const MAX_ROUTES = 8

/**
 * Penerusan port membuka jalan ke PERANGKATNYA; blok membuka jalan ke semua yang hidup DI
 * BELAKANGNYA — kolam PPPoE pelanggan. Ini yang membuat perintah ke ONT (reboot, ganti SSID,
 * ambil status) berangkat saat itu juga alih-alih menunggu ONT menyapa sendiri tiap 5 menit,
 * karena server akhirnya punya rute balik ke alamat pelanggan.
 *
 * Bloknya harus didaftarkan, bukan ditebak: hub perlu tahu blok mana milik peer yang mana
 * (dua ISP bisa sama-sama memakai 10.20.0.0/16), dan salah tebak berarti trafik satu tenant
 * dikirim ke router tenant lain.
 */
function RoutedSubnetModal({ account, onClose }: { account: VpnAccountView; onClose: () => void }) {
  const toast = useToast()
  const confirm = useConfirm()
  const [acct, setAcct] = useState(account)
  const [busy, setBusy] = useState(false)
  const [draft, setDraft] = useState({ cidr: '', label: '' })
  const [editing, setEditing] = useState<{ id: string; label: string } | null>(null)

  const apply = (action: () => Promise<VpnAccountView>, ok: string, after?: () => void) => {
    setBusy(true)
    action()
      .then((updated) => {
        setAcct(updated)
        after?.()
        toast.success(ok)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Operasi gagal'))
      .finally(() => setBusy(false))
  }

  const add = () =>
    apply(
      () => addAccountRoute(acct.id, { cidr: draft.cidr.trim(), label: draft.label.trim() || null }),
      'Blok didaftarkan — hub memasang rutenya paling lama ~1 menit',
      () => setDraft({ cidr: '', label: '' }),
    )

  const saveEdit = () => {
    if (!editing) return
    apply(() => renameAccountRoute(acct.id, editing.id, editing.label.trim()), 'Nama blok diubah', () =>
      setEditing(null),
    )
  }

  const remove = (r: VpnRoutedSubnetView) => {
    void (async () => {
      if (
        !(await confirm({
          title: `Cabut CIDR “${r.cidr}”`,
          message: `Cabut CIDR “${r.cidr}” dari akun “${acct.label}”? Server tidak lagi dapat menghubungi perangkat di CIDR ini.`,
          confirmLabel: 'Cabut CIDR',
          danger: true,
        }))
      )
        return
      apply(() => removeAccountRoute(acct.id, r.id), 'Blok dicabut')
    })()
  }

  // Terisi dari nama bawaan perintah pasang akun, tapi tetap boleh diketik: teknisi yang
  // membuat ovpn-client-nya lewat Winbox biasanya kebagian nama bawaan `ovpn-out1`.
  const [iface, setIface] = useState(ovpnInterfaceName(account.username))
  const script = blokFirewallScript(
    iface.trim() || ovpnInterfaceName(acct.username),
    acct.routes.map((r) => r.cidr),
  )
  const copyScript = () =>
    void navigator.clipboard?.writeText(script).then(() => toast.success('Aturan firewall disalin'))

  const full = acct.routes.length >= MAX_ROUTES

  return (
    <Modal title={`Blok pelanggan “${acct.label}”`} onClose={onClose} wide>
      <p className="muted" style={{ margin: '0 0 0.75rem',  }}>
        Daftarkan CIDR pelanggan agar server dapat menghubungi perangkat di belakang peer. Satu CIDR hanya boleh
        terdaftar pada satu akun per server.
      </p>

      <Table ><TableHeader><TableRow ><TableHeaderCell >Nama</TableHeaderCell>
      <TableHeaderCell >Blok</TableHeaderCell>
      <TableHeaderCell style={{ width: '9rem' }} /></TableRow></TableHeader>
      <TableBody>{acct.routes.length === 0 && (
        <TableRow ><TableCell colSpan={3} className="muted">
          Belum ada blok — server hanya bisa menghubungi perangkatnya, bukan pelanggan di belakangnya.
        </TableCell></TableRow>
      )}
      {acct.routes.map((r) =>
        editing?.id === r.id ? (
          <TableRow key={r.id}><TableCell ><TextField
            value={editing.label}
            onChange={(_, data) => setEditing({ ...editing, label: data.value })}
            placeholder="mis. Kolam PPPoE"
          /></TableCell>
          <TableCell className="tnum muted">{r.cidr}</TableCell>
          <TableCell ><div className="row" style={{ gap: '0.35rem' }}>
            <Button variant="primary" size="small" onClick={saveEdit} disabled={busy || !editing.label.trim()}>
              Simpan
            </Button>
            <Button variant="subtle" size="small" onClick={() => setEditing(null)} disabled={busy}>
              Batal
            </Button>
          </div></TableCell></TableRow>
        ) : (
          <TableRow key={r.id}><TableCell ><strong>{r.label}</strong></TableCell>
          <TableCell className="tnum">{r.cidr}</TableCell>
          <TableCell ><div className="row" style={{ gap: '0.35rem' }}>
            <Button
              variant="subtle"
              size="small"
              disabled={busy}
              onClick={() => setEditing({ id: r.id, label: r.label })}
            >
              Ubah nama
            </Button>
            <Button variant="subtle" size="small" disabled={busy} onClick={() => remove(r)}>
              Cabut
            </Button>
          </div></TableCell></TableRow>
        ),
      )}</TableBody></Table>

      <div className="stack" style={{ gap: '0.4rem', margin: '1rem 0' }}>
        <Text as="strong" size={300}  >Tambah blok</Text>
        <div className="row" style={{ alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <TextField
            label="Blok (CIDR)"
            value={draft.cidr}
            onChange={(_, data) => setDraft({ ...draft, cidr: data.value })}
            onKeyDown={(e) => e.key === 'Enter' && !busy && !full && isCidrLike(draft.cidr) && add()}
            placeholder="10.20.0.0/16"
            style={{ width: '12rem' }}
          />
          <TextField
            label="Nama (opsional)"
            value={draft.label}
            onChange={(_, data) => setDraft({ ...draft, label: data.value })}
            placeholder="otomatis dari blok"
            style={{ flex: 1, minWidth: '10rem' }}
          />
          <Button variant="primary" onClick={add} disabled={busy || full || !isCidrLike(draft.cidr)}>
            <IconPlus size={15} /> Tambah
          </Button>
        </div>
        {full && (
          <Text as="span" size={300} className="muted">
            Sudah {MAX_ROUTES} blok — cabut salah satu dulu.
          </Text>
        )}
      </div>

      {script && (
        <>
          <div className="row" style={{ alignItems: 'flex-end', marginBottom: '0.5rem' }}>
            <TextField
              label="Nama interface OVPN di perangkat"
              value={iface}
              onChange={(_, data) => setIface(data.value)}
              placeholder={ovpnInterfaceName(acct.username)}
              style={{ width: '18rem' }}
            />
          </div>
          <CommandBlock
            title={
              <>
                Izinkan di <strong>RouterOS</strong>:
              </>
            }
            command={script}
            copyLabel="Salin aturan"
            onCopy={copyScript}
            hint="Tempel di terminal RouterOS. Aman diulang; aturan lama “ftth-blok” diganti dan ditempatkan sebelum aturan drop."
          />
        </>
      )}
    </Modal>
  )
}

/* ---------- Kartu kredensial sekali-tampil ---------- */

function CredentialCard({ account, onDismiss }: { account: VpnAccountView; onDismiss: () => void }) {
  const toast = useToast()
  const copy = (value: string, what: string) =>
    void navigator.clipboard?.writeText(value).then(() => toast.success(`${what} disalin`))

  const rows: Array<{ label: string; value: string; copy?: boolean }> = [
    { label: 'Server', value: account.serverName },
    { label: 'Host / IP', value: account.host, copy: true },
    { label: 'Port', value: String(account.port), copy: true },
    { label: 'Tipe keamanan', value: account.securityType },
    { label: 'Username', value: account.username, copy: true },
    { label: 'Password', value: account.password ?? '—', copy: !!account.password },
    { label: 'IP overlay', value: account.overlayIp },
    { label: 'Winbox (remote)', value: account.winboxAddress ?? '—', copy: !!account.winboxAddress },
  ]

  return (
    <div
      className="card"
      style={{ borderColor: 'var(--warning)', background: 'color-mix(in srgb, var(--warning) 8%, var(--surface))' }}
    >
      <div className="row" style={{ gap: '0.5rem', marginBottom: '0.5rem' }}>
        <IconAlert size={17} style={{ color: 'var(--warning-ink)' }} />
        <strong>Kredensial akun “{account.label}”</strong>
      </div>
      <p className="muted" style={{ margin: '0 0 0.6rem',  }}>
        <strong>Password hanya ditampilkan sekali.</strong> Simpan sekarang atau unduh konfigurasi.
      </p>
      <Table style={{ marginBottom: '0.6rem' }}><TableBody>{rows.map((r) => (
        <TableRow key={r.label}><TableCell className="muted" style={{ width: '9rem' }}>{r.label}</TableCell>
        <TableCell ><code style={{ wordBreak: 'break-all' }}>{r.value}</code></TableCell>
        <TableCell style={{ width: '4rem' }}>{r.copy && (
          <Button variant="subtle" size="small" onClick={() => copy(r.value, r.label)}>
            Salin
          </Button>
        )}</TableCell></TableRow>
      ))}</TableBody></Table>
      {account.routerOsCommand && (
        <CommandBlock
            title={
              <>
                RouterOS <strong>v7</strong>
              </>
            }
          command={account.routerOsCommand}
          copyLabel="Salin perintah"
          onCopy={() => copy(account.routerOsCommand!, 'Perintah RouterOS v7')}
          hint={
            account.supportsV6
              ? 'RouterOS v7 (UDP/TCP, AES-256-GCM).'
              : `RouterOS v6 tidak kompatibel dengan ${account.protocol}. Gunakan server TCP untuk perangkat v6.`
          }
        />
      )}
      {account.supportsV6 && account.routerOsCommandV6 && (
        <CommandBlock
            title={
              <>
                RouterOS <strong>v6</strong>
              </>
            }
          command={account.routerOsCommandV6}
          copyLabel="Salin perintah v6"
          onCopy={() => copy(account.routerOsCommandV6!, 'Perintah RouterOS v6')}
            hint={`TCP, AES-256-CBC. Jika status berhenti di "connecting...", buka port ${account.port}/tcp di firewall atau NSG VPS.`}
        />
      )}
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <Button
          size="small"
          onClick={() => void saveBlob(() => downloadAccountRouterOs(account.id), `${account.username}.rsc`, toast.error)}
        >
          Unduh RouterOS
        </Button>
        <Button
          variant="subtle"
          size="small"
          onClick={() => void saveBlob(() => downloadAccountOvpn(account.id), `${account.username}.ovpn`, toast.error)}
        >
          Unduh .ovpn
        </Button>
        {account.supportsV6 && (
          <>
            <Button
              size="small"
              onClick={() =>
                void saveBlob(
                  () => downloadAccountRouterOs(account.id, 'V6'),
                  `${account.username}-v6.rsc`,
                  toast.error,
                )
              }
            >
              Unduh RouterOS v6
            </Button>
            <Button
              variant="subtle"
              size="small"
              onClick={() =>
                void saveBlob(() => downloadAccountOvpn(account.id, 'V6'), `${account.username}-v6.ovpn`, toast.error)
              }
            >
              Unduh .ovpn v6
            </Button>
          </>
        )}
        <Button variant="subtle" size="small" onClick={onDismiss}>
          Selesai
        </Button>
      </div>
    </div>
  )
}

/* ---------- Blok perintah RouterOS satu-baris (v7/v6), dengan salin + catatan ---------- */

function CommandBlock({
  title,
  command,
  copyLabel,
  onCopy,
  hint,
}: {
  title: ReactNode
  command: string
  copyLabel: string
  onCopy: () => void
  hint: string
}) {
  return (
    <div className="stack" style={{ gap: '0.35rem', marginBottom: '0.7rem' }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Text as="span" size={300} className="muted" >{title}</Text>
        <Button variant="subtle" size="small" onClick={onCopy}>
          {copyLabel}
        </Button>
      </div>
      <pre
        style={{
          margin: 0,
          padding: '0.6rem 0.7rem',
          background: 'var(--surface)',
          border: '1px solid var(--border)',
          borderRadius: '6px',
          overflowX: 'auto',
          
        }}
      >
        <code>{command}</code>
      </pre>
      <Text as="span" size={300} className="muted" >{hint}</Text>
    </div>
  )
}

/* ---------- Indikator liveness peer (online nyata dari hub, bukan status administratif) ---------- */

function LiveIndicator({ online, lastHandshakeAt }: { online: boolean; lastHandshakeAt: string | null }) {
  const sub = online
    ? `sejak ${fmtWhen(lastHandshakeAt)}`
    : lastHandshakeAt
      ? `terakhir ${fmtWhen(lastHandshakeAt)}`
      : 'belum pernah terhubung'
  return (
    <div className="stack" style={{ gap: '0.15rem' }}>
      <span
        className="badge"
        style={{ color: online ? 'var(--good-ink)' : 'var(--muted)',  }}
      >
        {online ? '● online' : '○ offline'}
      </span>
      <Text as="span" size={300} className="muted" >{sub}</Text>
    </div>
  )
}
