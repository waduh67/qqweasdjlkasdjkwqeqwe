import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Download, Pencil, Plus, Printer, RefreshCw, ShieldOff } from 'lucide-react'
import { ApiError } from '../api/client'
import { listPlans, type PlanView } from '../api/catalog'
import { listNas, type NasView } from '../api/bng'
import {
  createHotspotSite,
  createVoucherBatch,
  isHotspotPlan,
  listHotspotSites,
  listVoucherBatches,
  listVouchers,
  revokeVoucher,
  updateHotspotSite,
  type CreateVoucherBatchResponse,
  type HotspotSiteView,
  type PortalMode,
  type VoucherBatchView,
  type VoucherStatus,
  type VoucherView,
} from '../api/hotspot'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, Segmented, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { IconWifi } from '@/components/atoms/icons'
import { CommandBar, PageHeader, SearchInput } from '@/components/molecules'
import { Blade, DataTable, type Column, type RowAction } from '@/components/organisms'
import { useConfirm, usePrompt, useToast } from '@/system'

const STATUS_LABEL: Record<VoucherStatus, string> = { AVAILABLE: 'Tersedia', ACTIVE: 'Aktif', EXPIRED: 'Kedaluwarsa', REVOKED: 'Dicabut' }
const PORTAL_MODE_LABEL: Record<PortalMode, string> = { OFF: 'Nonaktif', NAS_OWNED: 'Milik NAS', NETOPS_HOSTED: 'Dihost NetOps' }
type VoucherDraft = { siteId: string; planId: string; quantity: string; durationHours: string }
type SiteDraft = { id?: string; nasId: string; name: string; location: string; portalMode: PortalMode; defaultPlanId: string; displayName: string; logoUrl: string }
const EMPTY_VOUCHER_DRAFT: VoucherDraft = { siteId: '', planId: '', quantity: '10', durationHours: '24' }
const EMPTY_SITE_DRAFT: SiteDraft = { nasId: '', name: '', location: '', portalMode: 'NAS_OWNED', defaultPlanId: '', displayName: '', logoUrl: '' }
const formatDuration = (seconds: number) => seconds % 86400 === 0 ? `${seconds / 86400} hari` : seconds % 3600 === 0 ? `${seconds / 3600} jam` : `${Math.round(seconds / 60)} menit`
const formatDate = (value: string | null) => value ? new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
const toSiteDraft = (site: HotspotSiteView): SiteDraft => ({ id: site.id, nasId: site.nasId, name: site.name, location: site.location ?? '', portalMode: site.portalMode, defaultPlanId: site.defaultPlanId ?? '', displayName: site.branding.displayName ?? '', logoUrl: site.branding.logoUrl ?? '' })
const csvCell = (value: string) => `"${value.replaceAll('"', '""')}"`

function downloadVoucherExport(vouchers: VoucherView[], siteName: (id: string) => string, planName: (id: string) => string) {
  const rows = [
    ['Kode voucher', 'Lokasi', 'Paket', 'Durasi', 'Status', 'Diaktifkan', 'Berakhir'],
    ...vouchers.map((voucher) => [voucher.username, siteName(voucher.siteId), planName(voucher.planId), formatDuration(voucher.durationSeconds), STATUS_LABEL[voucher.status], formatDate(voucher.activatedAt), formatDate(voucher.expiresAt)]),
  ]
  const blob = new Blob([rows.map((row) => row.map(csvCell).join(',')).join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'voucher-hotspot.csv'
  link.click()
  URL.revokeObjectURL(url)
}

export function HotspotPage() {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const prompt = usePrompt()
  const canVoucherView = can('hotspot.voucher.view')
  const canVoucherManage = can('hotspot.voucher.manage')
  const canSiteView = can('hotspot.site.view')
  const canSiteManage = can('hotspot.site.manage')
  const [sites, setSites] = useState<HotspotSiteView[]>([])
  const [nas, setNas] = useState<NasView[]>([])
  const [plans, setPlans] = useState<PlanView[]>([])
  const [batches, setBatches] = useState<VoucherBatchView[]>([])
  const [vouchers, setVouchers] = useState<VoucherView[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [voucherDraft, setVoucherDraft] = useState<VoucherDraft | null>(null)
  const [siteDraft, setSiteDraft] = useState<SiteDraft | null>(null)
  const [credentials, setCredentials] = useState<CreateVoucherBatchResponse | null>(null)
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState<VoucherStatus | ''>('')

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [siteRows, planRows, nasRows, batchPage, voucherPage] = await Promise.all([
        canSiteView || canVoucherView ? listHotspotSites() : Promise.resolve([]),
        canSiteView || canVoucherView ? listPlans() : Promise.resolve([]),
        canSiteView ? listNas() : Promise.resolve([]),
        canVoucherView ? listVoucherBatches({ siteId: siteFilter || undefined }) : Promise.resolve({ content: [] as VoucherBatchView[] }),
        canVoucherView ? listVouchers({ siteId: siteFilter || undefined, status: statusFilter || undefined }) : Promise.resolve({ content: [] as VoucherView[] }),
      ])
      setSites(siteRows)
      setPlans(planRows.filter(isHotspotPlan))
      setNas(nasRows)
      setBatches(batchPage.content)
      setVouchers(voucherPage.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data hotspot')
    } finally {
      setLoading(false)
    }
  }, [canSiteView, canVoucherView, siteFilter, statusFilter, toast])

  useEffect(() => { if (canSiteView || canVoucherView) void reload() }, [canSiteView, canVoucherView, reload])

  const visibleVouchers = useMemo(() => query.trim() ? vouchers.filter((voucher) => voucher.username.toLowerCase().includes(query.trim().toLowerCase())) : vouchers, [query, vouchers])
  const siteName = useCallback((id: string) => sites.find((site) => site.id === id)?.name ?? 'Lokasi tidak dikenal', [sites])
  const planName = useCallback((id: string) => plans.find((plan) => plan.id === id)?.name ?? 'Paket tidak dikenal', [plans])
  const nasName = useCallback((id: string) => nas.find((item) => item.id === id)?.name ?? 'NAS tidak dikenal', [nas])
  const voucherCounts = useMemo(() => Object.fromEntries((Object.keys(STATUS_LABEL) as VoucherStatus[]).map((status) => [status, vouchers.filter((voucher) => voucher.status === status).length])) as Record<VoucherStatus, number>, [vouchers])
  const vouchersBySite = useMemo(() => sites.map((site) => ({ site, count: vouchers.filter((voucher) => voucher.siteId === site.id).length })), [sites, vouchers])
  const provisioningIssueCount = useMemo(() => batches.filter((batch) => batch.status !== 'ACTIVE').length, [batches])

  const exportVouchers = () => {
    if (visibleVouchers.length === 0) {
      toast.info('Tidak ada voucher untuk diekspor.')
      return
    }
    downloadVoucherExport(visibleVouchers, siteName, planName)
    toast.success(`${visibleVouchers.length} voucher diekspor.`)
  }

  const generate = async () => {
    if (!voucherDraft) return
    const quantity = Number(voucherDraft.quantity)
    const durationHours = Number(voucherDraft.durationHours)
    if (!voucherDraft.siteId || !voucherDraft.planId || !Number.isInteger(quantity) || quantity < 1 || quantity > 1000 || !Number.isFinite(durationHours) || durationHours <= 0) {
      toast.error('Pilih lokasi dan paket; isi jumlah 1–1000 serta durasi valid.')
      return
    }
    setSaving(true)
    try {
      const created = await createVoucherBatch({ siteId: voucherDraft.siteId, planId: voucherDraft.planId, quantity, durationSeconds: Math.round(durationHours * 3600) })
      setVoucherDraft(null)
      setCredentials(created)
      toast.success(`${created.credentials.length} voucher dibuat.`)
      await reload()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal membuat batch voucher')
    } finally {
      setSaving(false)
    }
  }

  const saveSite = async () => {
    if (!siteDraft) return
    if (!siteDraft.nasId || !siteDraft.name.trim()) {
      toast.error('Pilih NAS dan isi nama situs.')
      return
    }
    setSaving(true)
    const payload = {
      name: siteDraft.name.trim(),
      location: siteDraft.location.trim() || null,
      portalMode: siteDraft.portalMode,
      defaultPlanId: siteDraft.defaultPlanId || null,
      branding: { displayName: siteDraft.displayName.trim() || null, logoUrl: siteDraft.logoUrl.trim() || null },
    }
    try {
      if (siteDraft.id) {
        await updateHotspotSite(siteDraft.id, payload)
        toast.success('Situs hotspot diperbarui.')
      } else {
        await createHotspotSite({ ...payload, nasId: siteDraft.nasId })
        toast.success('Situs hotspot dibuat.')
      }
      setSiteDraft(null)
      await reload()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan situs hotspot')
    } finally {
      setSaving(false)
    }
  }

  const revoke = async (voucher: VoucherView) => {
    const reason = await prompt({ title: `Cabut voucher ${voucher.username}`, message: 'Voucher tidak dapat digunakan setelah dicabut.', label: 'Alasan pencabutan', confirmLabel: 'Cabut voucher', required: true, multiline: true })
    if (reason == null || !await confirm({ title: `Cabut voucher ${voucher.username}?`, message: 'Tindakan ini tidak dapat dibatalkan.', confirmLabel: 'Cabut voucher', danger: true })) return
    try {
      await revokeVoucher(voucher.id, reason)
      toast.success('Voucher berhasil dicabut.')
      await reload()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencabut voucher')
    }
  }

  const siteColumns: Column<HotspotSiteView>[] = [
    { key: 'name', header: 'Situs', sortValue: (site) => site.name, cell: (site) => <><Text weight="semibold">{site.name}</Text>{site.location && <> · <Text as="span" className="muted" size={200}>{site.location}</Text></>}</> },
    { key: 'nas', header: 'NAS', sortValue: (site) => nasName(site.nasId), cell: (site) => nasName(site.nasId) },
    { key: 'mode', header: 'Mode portal', sortValue: (site) => site.portalMode, cell: (site) => <Badge tone={site.portalMode === 'OFF' ? 'neutral' : site.portalMode === 'NAS_OWNED' ? 'accent' : 'good'}>{PORTAL_MODE_LABEL[site.portalMode]}</Badge> },
    { key: 'plan', header: 'Paket default', sortValue: (site) => site.defaultPlanId ? planName(site.defaultPlanId) : '', cell: (site) => site.defaultPlanId ? planName(site.defaultPlanId) : <Text className="muted">Belum dipilih</Text> },
    { key: 'branding', header: 'Branding', cell: (site) => site.branding.displayName ? site.branding.displayName : <Text className="muted">Default</Text> },
  ]
  const voucherColumns: Column<VoucherView>[] = [
    { key: 'username', header: 'Kode voucher', sortValue: (voucher) => voucher.username, cell: (voucher) => <Text weight="semibold">{voucher.username}</Text> },
    { key: 'site', header: 'Lokasi', sortValue: (voucher) => siteName(voucher.siteId), cell: (voucher) => siteName(voucher.siteId) },
    { key: 'plan', header: 'Paket', sortValue: (voucher) => planName(voucher.planId), cell: (voucher) => planName(voucher.planId) },
    { key: 'duration', header: 'Durasi', sortValue: (voucher) => voucher.durationSeconds, cell: (voucher) => formatDuration(voucher.durationSeconds) },
    { key: 'status', header: 'Status', sortValue: (voucher) => voucher.status, cell: (voucher) => <StatusBadge status={voucher.status} label={STATUS_LABEL[voucher.status]} /> },
    { key: 'expires', header: 'Berakhir', sortValue: (voucher) => voucher.expiresAt ?? '', cell: (voucher) => formatDate(voucher.expiresAt) },
  ]
  const batchColumns: Column<VoucherBatchView>[] = [
    { key: 'site', header: 'Lokasi', sortValue: (batch) => siteName(batch.siteId), cell: (batch) => siteName(batch.siteId) },
    { key: 'plan', header: 'Paket', sortValue: (batch) => planName(batch.planId), cell: (batch) => planName(batch.planId) },
    { key: 'duration', header: 'Durasi akses', sortValue: (batch) => batch.durationSeconds, cell: (batch) => formatDuration(batch.durationSeconds) },
    { key: 'status', header: 'Status batch', sortValue: (batch) => batch.status, cell: (batch) => <Badge tone="neutral">{batch.status}</Badge> },
  ]
  const siteActions = (site: HotspotSiteView): RowAction[] => canSiteManage ? [{ key: 'edit', label: 'Ubah situs', icon: <Pencil size={16} />, onClick: () => setSiteDraft(toSiteDraft(site)) }] : []
  const voucherActions = (voucher: VoucherView): RowAction[] => canVoucherManage && voucher.status !== 'REVOKED' ? [{ key: 'revoke', label: 'Cabut', icon: <ShieldOff size={16} />, onClick: () => void revoke(voucher) }] : []

  if (!canSiteView && !canVoucherView) return <div className="card"><Text as="h2" weight="semibold">Akses ditolak</Text><p className="muted">Anda tidak memiliki izin melihat hotspot atau voucher.</p></div>

  return <div className="stack" style={{ gap: '1rem' }}>
    <PageHeader title="Hotspot & Voucher" subtitle="Kelola situs hotspot, portal captive, dan voucher." />
    <CommandBar primary={canSiteManage ? { key: 'create-site', label: 'Tambah situs hotspot', icon: <Plus size={16} />, onClick: () => setSiteDraft({ ...EMPTY_SITE_DRAFT }) } : canVoucherManage ? { key: 'generate', label: 'Buat batch voucher', icon: <Plus size={16} />, onClick: () => setVoucherDraft({ ...EMPTY_VOUCHER_DRAFT }) } : undefined} actions={[...(canSiteManage && canVoucherManage ? [{ key: 'generate', label: 'Buat batch voucher', icon: <Plus size={16} />, onClick: () => setVoucherDraft({ ...EMPTY_VOUCHER_DRAFT }) }] : []), { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: () => void reload() }]} />
    {(!canSiteManage || !canVoucherManage) && <div className="card"><Text className="muted">Mode baca saja berlaku pada bagian yang tidak memiliki izin kelola.</Text></div>}

    {canVoucherView && <section className="stack" aria-labelledby="ringkasan-voucher"><div><Text as="h2" id="ringkasan-voucher" weight="semibold">Ringkasan operasional</Text><Text as="p" className="muted" size={200}>Ringkasan status dan lokasi mengikuti filter server yang aktif.</Text></div>{loading ? <Text className="muted">Memuat ringkasan voucher…</Text> : <><div className="stat-grid"><div className="stat"><span className="stat-label">Voucher tersedia</span><span className="stat-value">{voucherCounts.AVAILABLE}</span><span className="stat-note">Siap dibagikan</span></div><div className="stat accent-bar"><span className="stat-label">Voucher aktif</span><span className="stat-value">{voucherCounts.ACTIVE}</span><span className="stat-note">Sesi aktif tidak tersedia dari data lokal</span></div><div className="stat warn-bar"><span className="stat-label">Kedaluwarsa</span><span className="stat-value">{voucherCounts.EXPIRED}</span><span className="stat-note">Perlu evaluasi bila masih dibutuhkan</span></div><div className="stat crit-bar"><span className="stat-label">Dicabut</span><span className="stat-value">{voucherCounts.REVOKED}</span><span className="stat-note">Tidak dapat digunakan</span></div></div><div className="card pad-0"><div className="card-head"><Text as="h3" weight="semibold">Voucher per lokasi</Text><Badge tone={provisioningIssueCount === 0 ? 'good' : 'warning'}>{provisioningIssueCount === 0 ? 'Batch tersedia' : `${provisioningIssueCount} batch perlu diperiksa`}</Badge></div><div className="card-body">{vouchersBySite.length === 0 ? <EmptyState icon={<IconWifi size={34} />} title="Belum ada lokasi untuk diringkas" hint="Tambahkan situs dan batch voucher untuk melihat ringkasan per lokasi." /> : <div className="stack">{vouchersBySite.map(({ site, count }) => <div className="spread wrap" key={site.id}><div><Text weight="semibold">{site.name}</Text>{site.location && <Text as="p" className="muted" size={200}>{site.location}</Text>}</div><Badge tone="neutral">{count} voucher</Badge></div>)}</div>}</div></div></>}</section>}

    {canSiteView && <div className="card pad-0"><div className="card-head"><Text as="h2" weight="semibold">Situs hotspot</Text></div><div className="card-body">{loading ? <Text className="muted">Memuat situs hotspot…</Text> : sites.length === 0 ? <EmptyState icon={<IconWifi size={34} />} title="Belum ada situs hotspot" hint="Tambahkan situs hotspot untuk mulai mengatur portal dan voucher." /> : <DataTable rows={sites} columns={siteColumns} rowKey={(site) => site.id} rowActions={siteActions} />}</div></div>}

    {canVoucherView && <><div className="card pad-0"><div className="card-head"><Text as="h2" weight="semibold">Voucher</Text></div><div className="card-body stack"><Toolbar><SearchInput value={query} onChange={setQuery} placeholder="Cari kode voucher" aria-label="Cari kode voucher" /><select aria-label="Filter lokasi" value={siteFilter} onChange={(event) => setSiteFilter(event.target.value)}><option value="">Semua lokasi</option>{sites.map((site) => <option key={site.id} value={site.id}>{site.name}</option>)}</select><select aria-label="Filter status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as VoucherStatus | '')}><option value="">Semua status</option>{Object.entries(STATUS_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>{canVoucherManage && <Button icon={<Download size={16} />} onClick={exportVouchers} disabled={visibleVouchers.length === 0}>Ekspor CSV</Button>}</Toolbar>{loading ? <Text className="muted">Memuat voucher…</Text> : visibleVouchers.length === 0 ? <EmptyState icon={<IconWifi size={34} />} title="Belum ada voucher" hint="Buat batch voucher untuk menerbitkan akses hotspot sementara." /> : <DataTable rows={visibleVouchers} columns={voucherColumns} rowKey={(voucher) => voucher.id} rowActions={voucherActions} />}</div></div>
    <div className="card pad-0"><div className="card-head"><Text as="h2" weight="semibold">Batch terbaru</Text></div><div className="card-body">{loading ? <Text className="muted">Memuat batch…</Text> : batches.length === 0 ? <EmptyState icon={<IconWifi size={34} />} title="Belum ada batch" hint="Batch voucher tampil di sini." /> : <DataTable rows={batches} columns={batchColumns} rowKey={(batch) => batch.id} />}</div></div></>}

    <Blade open={siteDraft != null} title={siteDraft?.id ? 'Ubah situs hotspot' : 'Tambah situs hotspot'} subtitle="Atur NAS, portal, dan branding situs." onClose={() => setSiteDraft(null)} footer={<><Button variant="primary" onClick={() => void saveSite()} disabled={saving}>{saving ? 'Menyimpan…' : 'Simpan situs'}</Button><Button onClick={() => setSiteDraft(null)} disabled={saving}>Batal</Button></>} size="lg">{siteDraft && <div className="stack"><SelectField label="NAS" required value={siteDraft.nasId} disabled={Boolean(siteDraft.id)} onChange={(event) => setSiteDraft({ ...siteDraft, nasId: event.target.value })} hint={siteDraft.id ? 'NAS tidak dapat diubah.' : 'Satu NAS untuk satu situs hotspot.'}><option value="">Pilih NAS</option>{nas.map((item) => <option key={item.id} value={item.id}>{item.name}{item.address ? ` — ${item.address}` : ''}{!item.enabled ? ' (nonaktif)' : ''}</option>)}</SelectField><TextField label="Nama situs" required value={siteDraft.name} onChange={(_, data) => setSiteDraft({ ...siteDraft, name: data.value })} /><TextField label="Lokasi" value={siteDraft.location} onChange={(_, data) => setSiteDraft({ ...siteDraft, location: data.value })} hint="Opsional." /><div className="stack" style={{ gap: '0.4rem' }}><Text weight="semibold">Mode portal</Text><Segmented ariaLabel="Mode portal" value={siteDraft.portalMode} onChange={(portalMode) => setSiteDraft({ ...siteDraft, portalMode })} options={(Object.keys(PORTAL_MODE_LABEL) as PortalMode[]).map((value) => ({ value, label: PORTAL_MODE_LABEL[value] }))} /><Text className="muted" size={200}>{siteDraft.portalMode === 'OFF' ? 'Voucher baru tidak dapat diterbitkan.' : siteDraft.portalMode === 'NAS_OWNED' ? 'NAS menampilkan portal captive.' : 'NetOps menghosting portal captive.'}</Text></div><SelectField label="Paket hotspot default" value={siteDraft.defaultPlanId} onChange={(event) => setSiteDraft({ ...siteDraft, defaultPlanId: event.target.value })} hint="Opsional; hanya paket Hotspot aktif yang tersedia."><option value="">Belum dipilih</option>{plans.map((plan) => <option key={plan.id} value={plan.id}>{plan.name}</option>)}</SelectField><div className="hr" /><Text as="h3" weight="semibold">Branding portal</Text><TextField label="Nama tampilan" value={siteDraft.displayName} onChange={(_, data) => setSiteDraft({ ...siteDraft, displayName: data.value })} maxLength={100} hint="Opsional; nama yang tampil pada portal." /><TextField label="URL logo" type="url" value={siteDraft.logoUrl} onChange={(_, data) => setSiteDraft({ ...siteDraft, logoUrl: data.value })} maxLength={500} hint="Opsional; gunakan URL HTTPS yang dapat diakses pelanggan." /></div>}</Blade>

    <Blade open={voucherDraft != null} title="Buat batch voucher" subtitle="Kredensial hanya tampil sekali." onClose={() => setVoucherDraft(null)} footer={<><Button variant="primary" onClick={() => void generate()} disabled={saving}>{saving ? 'Membuat…' : 'Buat voucher'}</Button><Button onClick={() => setVoucherDraft(null)} disabled={saving}>Batal</Button></>}>{voucherDraft && <div className="stack"><SelectField label="Lokasi hotspot" required value={voucherDraft.siteId} onChange={(event) => setVoucherDraft({ ...voucherDraft, siteId: event.target.value })}><option value="">Pilih lokasi</option>{sites.map((site) => <option key={site.id} value={site.id}>{site.name}{site.location ? ` — ${site.location}` : ''}</option>)}</SelectField><SelectField label="Paket hotspot" required value={voucherDraft.planId} onChange={(event) => setVoucherDraft({ ...voucherDraft, planId: event.target.value })}><option value="">Pilih paket</option>{plans.map((plan) => <option key={plan.id} value={plan.id}>{plan.name}</option>)}</SelectField><TextField label="Jumlah voucher" required type="number" min={1} max={1000} value={voucherDraft.quantity} onChange={(_, data) => setVoucherDraft({ ...voucherDraft, quantity: data.value })} hint="Maksimal 1.000 voucher per batch." /><TextField label="Durasi akses (jam)" required type="number" min={0.0167} step="any" value={voucherDraft.durationHours} onChange={(_, data) => setVoucherDraft({ ...voucherDraft, durationHours: data.value })} hint="Masa aktif mulai dihitung saat voucher dipakai." /></div>}</Blade>

    <Blade open={credentials != null} title="Kredensial voucher siap" subtitle="Simpan atau cetak sekarang. Kata sandi tidak akan ditampilkan lagi setelah panel ditutup." onClose={() => setCredentials(null)} footer={canVoucherManage ? <Button variant="primary" icon={<Printer size={16} />} onClick={() => window.print()}>Cetak lembar kredensial</Button> : undefined}>{credentials && canVoucherManage && <div className="stack voucher-credential-sheet"><Text weight="semibold">{credentials.credentials.length} voucher untuk {siteName(credentials.batch.siteId)}</Text><Text className="muted" size={200}>Lembar ini memuat kata sandi sekali pakai. Cetak dan bagikan hanya kepada penerima yang berwenang.</Text><div className="card pad-0"><DataTable rows={credentials.credentials} rowKey={(credential) => credential.voucherId} columns={[{ key: 'username', header: 'Kode voucher', cell: (credential) => credential.username }, { key: 'password', header: 'Kata sandi', cell: (credential) => <code>{credential.password}</code> }]} /></div></div>}</Blade>
  </div>
}
