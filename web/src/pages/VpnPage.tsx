import { CommandBar } from '@/components/molecules/CommandBar'
import { Blade } from '@/components/organisms/Blade'
import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Table, TableBody, TableCell, TableRow, Text } from '@fluentui/react-components'
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
import { SearchInput } from '@/components/molecules'
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

  const [creating, setCreating] = useState(false)
  const creation = useCreationReview(creating)
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
    if (creation.beforeSave()) return
    setBusy(true)
    void run(async () => {
      const account = await generateAccount({ label: label.trim() || null })
      setFresh(account)
      setLabel(''); setCreating(false)
    }, 'Akun VPN dibuat').finally(() => { setBusy(false); creation.finish() })
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

      <CommandBar primary={canManage ? { key: 'create', label: 'Buat akun VPN', icon: <IconPlus size={16} />, onClick: () => setCreating(true) } : undefined}
        actions={[{ key: 'refresh', label: 'Segarkan', onClick: () => void reload() }]} />
      <Blade open={creating} title="Buat akun VPN" onClose={() => !busy && setCreating(false)}
        creation={{ ...creation, busy, prepare: generate, summary: <CreationSummary rows={[["Label", label || 'Otomatis'], ["Server dan alamat VPN", 'Otomatis']]} /> }}
        footer={<Button variant="primary" disabled={busy} onClick={generate}>{busy ? 'Membuat…' : 'Buat akun VPN'}</Button>}>
        <div className="stack"><TextField label="Label" value={label} onChange={(_, data) => setLabel(data.value)} placeholder="Router Bandung" />
          <p>Server dan alamat VPN dipilih otomatis. Kredensial ditampilkan setelah akun dibuat.</p></div>
      </Blade>

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

  const [creating, setCreating] = useState(false)
  const creation = useCreationReview(creating || editing !== null, editing !== null)
  const apply = (action: () => Promise<VpnAccountView>, ok: string, after?: () => void) => {
    setBusy(true)
    action()
      .then((updated) => {
        setAcct(updated)
        after?.()
        toast.success(ok)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Operasi gagal'))
      .finally(() => { setBusy(false); creation.finish() })
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
    if (port === null || creation.beforeSave()) return
    apply(
      () =>
        addAccountForward(acct.id, {
          devicePort: port,
          protocol: addDraft.protocol,
          label: addDraft.label.trim() || null,
        }),
      'Penerusan port ditambahkan',
      () => { setAddDraft({ devicePort: '8291', protocol: 'TCP', label: '' }); setCreating(false) },
    )
  }

  const saveEdit = () => {
    if (!editing) return
    const port = parsePort(editing.devicePort)
    if (port === null || creation.beforeSave()) return
    apply(
      () =>
        retargetAccountForward(acct.id, editing.id, {
          devicePort: port,
          protocol: editing.protocol,
          label: editing.label.trim() || null,
        }),
      'Penerusan port diperbarui',
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

  const value = editing ?? addDraft
  const update = (patch: Partial<typeof addDraft>) => editing ? setEditing({ ...editing, ...patch }) : setAddDraft({ ...addDraft, ...patch })
  const save = editing ? saveEdit : add
  const closeEditor = () => { setCreating(false); setEditing(null) }

  return <Blade open layout="resource" title={`Port remote · ${acct.label}`} onClose={onClose}>
    {(creating || editing) && <Blade open title={editing ? 'Ubah penerusan port' : 'Tambah penerusan port'} onClose={closeEditor}
    creation={{ ...creation, busy, prepare: save, summary: <CreationSummary rows={[
      ['Akun VPN', acct.label], ['Nama layanan', value.label || 'Otomatis'], ['Port perangkat', value.devicePort], ['Protokol', value.protocol],
    ]} /> }} footer={<><Button disabled={busy} onClick={closeEditor}>Batal</Button><Button variant="primary" disabled={busy} onClick={save}>Simpan</Button></>}>
    <div className="stack">
      {!editing && <SelectField label="Layanan" value={addDraft.devicePort} onChange={(_, data) => applyPreset(data.value)}>
        {!SERVICE_PRESETS.some(p => String(p.devicePort) === addDraft.devicePort) && <option value={addDraft.devicePort}>Lainnya</option>}
        {SERVICE_PRESETS.map(p => <option key={p.devicePort} value={p.devicePort}>{p.label} ({p.devicePort})</option>)}
      </SelectField>}
      <TextField label="Port di perangkat" type="number" required min={1} max={65535} value={value.devicePort} onChange={(_, data) => update({ devicePort: data.value })} />
      <SelectField label="Protokol" value={value.protocol} onChange={(_, data) => update({ protocol: data.value as VpnForwardProtocol })}><option value="TCP">TCP</option><option value="UDP">UDP</option></SelectField>
      <TextField label="Nama layanan" value={value.label} onChange={(_, data) => update({ label: data.value })} placeholder="Otomatis dari port" />
    </div>
  </Blade>}
    <CommandBar primary={{ key: 'create', label: 'Tambah penerusan port', icon: <IconPlus size={16} />, onClick: () => setCreating(true), disabled: busy || full }} />
    <DataTable rows={acct.forwards} rowKey={row => row.id} columns={[
      { key: 'name', header: 'Layanan', cell: row => row.label },
      { key: 'address', header: 'Alamat publik', cell: row => row.address },
      { key: 'port', header: 'Port perangkat', cell: row => row.devicePort },
      { key: 'protocol', header: 'Protokol', cell: row => row.protocol },
    ]} rowActions={row => [
      { key: 'copy', label: 'Salin alamat', onClick: () => copy(row.address) },
      { key: 'edit', label: 'Ubah', disabled: busy, onClick: () => setEditing({ id: row.id, devicePort: String(row.devicePort), protocol: row.protocol, label: row.label }) },
      { key: 'remove', label: 'Cabut', disabled: busy, onClick: () => remove(row) },
    ]} />
    {full && <p>Maksimal {MAX_FORWARDS} penerusan port per akun.</p>}
  </Blade>
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

  const [creating, setCreating] = useState(false)
  const creation = useCreationReview(creating || editing !== null, editing !== null)
  const apply = (action: () => Promise<VpnAccountView>, ok: string, after?: () => void) => {
    setBusy(true)
    action()
      .then((updated) => {
        setAcct(updated)
        after?.()
        toast.success(ok)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Operasi gagal'))
      .finally(() => { setBusy(false); creation.finish() })
  }

  const add = () => {
    if (!isCidrLike(draft.cidr) || creation.beforeSave()) return
    apply(
      () => addAccountRoute(acct.id, { cidr: draft.cidr.trim(), label: draft.label.trim() || null }),
      'Blok didaftarkan. Rute akan aktif dalam satu menit.',
      () => { setDraft({ cidr: '', label: '' }); setCreating(false) },
    )
  }

  const saveEdit = () => {
    if (!editing || creation.beforeSave()) return
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

  const value = editing ?? draft
  const cidr = editing ? acct.routes.find(row => row.id === editing.id)?.cidr ?? '' : draft.cidr
  const save = editing ? saveEdit : add
  const closeEditor = () => { setCreating(false); setEditing(null) }

  return <Blade open layout="resource" title={`Blok pelanggan · ${acct.label}`} onClose={onClose}>
    {(creating || editing) && <Blade open title={editing ? 'Ubah blok pelanggan' : 'Tambah blok pelanggan'} onClose={closeEditor}
    creation={{ ...creation, busy, prepare: save, summary: <CreationSummary rows={[
      ['Akun VPN', acct.label], ['CIDR', cidr], ['Nama blok', value.label],
    ]} /> }} footer={<><Button disabled={busy} onClick={closeEditor}>Batal</Button><Button variant="primary" disabled={busy || !isCidrLike(cidr)} onClick={save}>Simpan</Button></>}>
    <div className="stack"><TextField label="CIDR" required disabled={!!editing} value={cidr} onChange={(_, data) => setDraft({ ...draft, cidr: data.value })} placeholder="10.20.0.0/24" />
      <TextField label="Nama blok" value={value.label} onChange={(_, data) => editing ? setEditing({ ...editing, label: data.value }) : setDraft({ ...draft, label: data.value })} />
    </div>
  </Blade>}
    <CommandBar primary={{ key: 'create', label: 'Tambah blok pelanggan', icon: <IconPlus size={16} />, onClick: () => setCreating(true), disabled: busy || full }} />
    <DataTable rows={acct.routes} rowKey={row => row.id} columns={[
      { key: 'name', header: 'Nama', cell: row => row.label },
      { key: 'cidr', header: 'CIDR', cell: row => row.cidr },
    ]} rowActions={row => [
      { key: 'edit', label: 'Ubah', disabled: busy, onClick: () => setEditing({ id: row.id, label: row.label }) },
      { key: 'remove', label: 'Cabut', disabled: busy, onClick: () => remove(row) },
    ]} />
    {full && <p>Maksimal {MAX_ROUTES} blok per akun.</p>}
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
  </Blade>
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
        {online ? 'Online' : 'Offline'}
      </span>
      <Text as="span" size={300} className="muted" > · {sub}</Text>
    </div>
  )
}
