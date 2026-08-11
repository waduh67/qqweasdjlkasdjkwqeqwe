import { useState } from 'react'
import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import { resetAccessLogin } from '@/api/bng'
import { rebootCpe, runCpePing } from '@/api/cpe'
import { onuStatusLabel, type CustomerTrace, type TraceHop } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import {
  IconChevronDown,
  IconCustomers,
  IconKey,
  IconPower,
  IconRoute,
  IconWorkOrder,
} from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import {
  RX_CRIT_DBM,
  RX_WARN_DBM,
  RX_WORD,
  VERDICT_COLOR,
  VERDICT_INTENT,
  traceVerdict,
} from '@/map/traceVerdict'
import { relocateAction } from './mapActions'

const HOP_LABEL: Record<string, string> = {
  CUSTOMER: 'ONT / Pelanggan',
  ONU: 'ONU',
  ODP: 'ODP (FAT)',
  ODC: 'ODC (FDT)',
  OLT: 'OLT',
  PON: 'PON',
  PON_PORT: 'PON',
  SITE: 'Site/POP',
  BRAS: 'BRAS',
}

/** "3 hari lalu" / "2 jam lalu" — cukup untuk menakar seberapa basi sebuah bacaan. */
function agoLabel(iso: string): string {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'barusan'
  if (minutes < 60) return `${minutes} menit lalu`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} jam lalu`
  return `${Math.round(hours / 24)} hari lalu`
}

/**
 * Panel telusur pelanggan: jalur fisik dari rumah pelanggan menaiki topologi
 * sampai BRAS — menjawab "kenapa pelanggan ini bermasalah dan apa tindakannya".
 *
 * Disusun seperti blade Azure Portal: kepala (nama + jenis sumber daya), command
 * bar datar berisi aksi, lalu badan berupa MessageBar vonis + daftar properti
 * "Essentials" dua kolom. Bentuk ini dipilih karena operator MEMINDAI properti,
 * bukan membaca kalimat bersambung titik-tengah. Rantai hop tetap dilipat: yang
 * menarik cuma saat ada yang salah, dan ringkasannya sudah ada di baris "Jalur".
 */
export function CustomerTracePanel({
  trace,
  canRelocate,
  canResetLogin,
  canRebootCpe,
  canDiagnose,
  canCreateWorkOrder,
  canOpenCustomer,
  onRelocate,
  onCreateWorkOrder,
  onOpenCustomer,
  onClose,
}: {
  trace: CustomerTrace
  canRelocate: boolean
  canResetLogin: boolean
  canRebootCpe: boolean
  canDiagnose: boolean
  canCreateWorkOrder: boolean
  canOpenCustomer: boolean
  onRelocate: () => void
  onCreateWorkOrder: () => void
  onOpenCustomer: () => void
  onClose: () => void
}) {
  const toast = useToast()
  const confirm = useConfirm()
  const [busy, setBusy] = useState<string | null>(null)
  const verdict = traceVerdict(trace)
  // Rantai hop terbuka sendiri saat ada masalah; saat sehat cukup remah-remah jalur.
  const [hopsOpen, setHopsOpen] = useState(verdict.tone !== 'good')

  const bras = trace.bras
  const rxLive = trace.liveRxPowerDbm
  // Warna Rx dihitung dari angkanya sendiri, bukan dari `opticalHealth` — health itu
  // turunan redaman SAAT INSTALASI dan sering UNKNOWN, jadi tak boleh mengaburkan
  // bacaan hidup yang justru paling dipercaya operator.
  const rxTone = rxLive == null ? 'neutral' : rxLive <= RX_CRIT_DBM ? 'critical' : rxLive <= RX_WARN_DBM ? 'warning' : 'good'

  const act = async (key: string, run: () => Promise<string>) => {
    setBusy(key)
    try {
      toast.success(await run())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Aksi gagal')
    } finally {
      setBusy(null)
    }
  }

  const doResetLogin = async () => {
    if (!bras) return
    const ok = await confirm({
      title: 'Reset Login',
      message: `Putus sesi PPPoE ${bras.username} agar router dial ulang? Pelanggan terputus beberapa detik.`,
      confirmLabel: 'Reset Login',
    })
    if (!ok) return
    await act('reset', async () => {
      await resetAccessLogin(bras.accessId)
      return 'Sesi diputus — router akan dial ulang'
    })
  }

  const doReboot = async () => {
    if (!trace.cpeDeviceId) return
    const ok = await confirm({
      title: 'Reboot ONT/router',
      message: `Reboot perangkat ${trace.customerName}? Layanan mati sekitar 1–2 menit.`,
      confirmLabel: 'Reboot',
      danger: true,
    })
    if (!ok) return
    await act('reboot', async () => {
      const res = await rebootCpe(trace.cpeDeviceId as string)
      return res.status === 'SUCCESS' ? 'Reboot dikirim ke perangkat' : (res.detail ?? 'Reboot gagal dikirim')
    })
  }

  const doPing = () =>
    act('ping', async () => {
      const res = await runCpePing(trace.cpeDeviceId as string)
      if (!res.ok) return res.message
      const avg = res.averageResponseMs
      return `Ping ${res.host}: ${res.successCount ?? 0} ok / ${res.failureCount ?? 0} gagal${
        avg != null ? ` · rata-rata ${avg} ms` : ''
      }`
    })

  const showResetLogin = canResetLogin && bras != null
  const showCpeActions = trace.cpeDeviceId != null

  // Command bar ala Azure: aksi utama dipatok kiri sebagai CTA biru, aksi perangkat
  // menyusul, lalu aksi navigasi dipisah garis vertikal. Label berubah saat sibuk —
  // tombol datar tak punya spinner, jadi teksnyalah yang menjadi tanda kerja jalan.
  const primaryAction: CommandAction | undefined = canCreateWorkOrder
    ? { key: 'wo', label: 'Buat WO', icon: <IconWorkOrder size={15} />, onClick: onCreateWorkOrder, disabled: busy != null }
    : undefined

  const actions: CommandAction[] = []
  if (showResetLogin)
    actions.push({
      key: 'reset',
      label: busy === 'reset' ? 'Memutus…' : 'Reset Login',
      icon: <IconKey size={15} />,
      onClick: () => void doResetLogin(),
      disabled: busy != null,
    })
  if (showCpeActions && canRebootCpe)
    actions.push({
      key: 'reboot',
      label: busy === 'reboot' ? 'Mengirim…' : 'Reboot ONT',
      icon: <IconPower size={15} />,
      onClick: () => void doReboot(),
      disabled: busy != null,
    })
  if (showCpeActions && canDiagnose)
    actions.push({
      key: 'ping',
      label: busy === 'ping' ? 'Ping…' : 'Ping',
      icon: <IconRoute size={15} />,
      onClick: () => void doPing(),
      disabled: busy != null,
    })
  if (canOpenCustomer)
    actions.push({
      key: 'detail',
      label: 'Detail pelanggan',
      icon: <IconCustomers size={15} />,
      onClick: onOpenCustomer,
      disabled: busy != null,
      dividerBefore: actions.length > 0,
    })
  if (canRelocate) actions.push({ ...relocateAction(onRelocate, !canOpenCustomer && actions.length > 0), disabled: busy != null })

  const onuStatus = trace.liveOnuStatus ?? trace.onuStatus
  // Kode ODP tak dibawa sebagai kolom tersendiri di trace — hop ODP-lah sumbernya.
  const odpHop = trace.hops.find((h) => h.kind === 'ODP')

  return (
    <aside className="map-panel blade">
      <BladeHead title={trace.customerName} subtitle={`Pelanggan · ${trace.customerCode}`} onClose={onClose} />

      {(primaryAction || actions.length > 0) && <CommandBar primary={primaryAction} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={VERDICT_INTENT[verdict.tone]}>
          <MessageBarBody>{verdict.text}</MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="Status ONU">
            {onuStatus ? (
              <StatusBadge status={onuStatus} label={onuStatusLabel(onuStatus)} />
            ) : (
              <span className="muted">Belum terpasang</span>
            )}
          </Ess>
          <Ess label="Serial ONU">{trace.onuSerialNumber && <span className="tnum">{trace.onuSerialNumber}</span>}</Ess>
          <Ess label="Titik ODP">
            {odpHop && (
              <>
                {odpHop.code}
                {trace.odpPortNumber != null && <span className="muted"> · port {trace.odpPortNumber}</span>}
              </>
            )}
          </Ess>
          <Ess label="Redaman Rx">
            {rxLive != null ? (
              <>
                <span className="tnum" style={{ color: VERDICT_COLOR[rxTone], fontWeight: 600 }}>
                  {rxLive.toFixed(1)} dBm
                </span>
                <span className="muted">
                  {' '}
                  {RX_WORD[rxTone]}
                  {trace.installRxPowerDbm != null && ` · saat pasang ${trace.installRxPowerDbm.toFixed(1)} dBm`}
                </span>
              </>
            ) : trace.installRxPowerDbm != null ? (
              <>
                <span className="tnum">{trace.installRxPowerDbm.toFixed(1)} dBm</span>
                <span className="muted"> saat pasang · belum ada bacaan hidup</span>
              </>
            ) : (
              /* Estimasi hanya berguna selagi tak ada ukuran nyata; menampilkannya
                 bersama Rx terukur cuma memancing "yang mana yang benar". */
              trace.estimatedLossDb != null && (
                <span className="muted">perkiraan rugi {trace.estimatedLossDb.toFixed(1)} dB · belum pernah terukur</span>
              )
            )}
          </Ess>
          <Ess label="Jarak serat">{trace.distanceMeters != null && `${trace.distanceMeters} m`}</Ess>
          <Ess label="Sesi PPPoE">
            {bras ? (
              <>
                <StatusBadge status={bras.online ? 'ONLINE' : 'OFFLINE'} label={bras.online ? 'Online' : 'Offline'} />
                {!bras.online && bras.lastSeenAt && <span className="muted"> · terakhir {agoLabel(bras.lastSeenAt)}</span>}
              </>
            ) : (
              <span className="muted">Belum ada akun</span>
            )}
          </Ess>
          <Ess label="Akun">{bras?.username}</Ess>
          <Ess label="Alamat IP">{bras?.framedIp && <span className="tnum">{bras.framedIp}</span>}</Ess>
          <Ess label="Paket">{bras?.rateProfileName}</Ess>
          <Ess label="BRAS">{bras?.nasName}</Ess>
          <Ess label="Router (ACS)">
            {trace.cpeDeviceId != null && (
              <StatusBadge
                status={trace.cpeOnline ? 'ONLINE' : 'OFFLINE'}
                label={trace.cpeOnline ? 'Melapor' : 'Tak melapor'}
              />
            )}
          </Ess>
        </dl>

        {trace.hops.length > 0 && (
          <div className="stack" style={{ gap: '0.5rem' }}>
            <button
              type="button"
              className="blade-disclosure"
              onClick={() => setHopsOpen((v) => !v)}
              aria-expanded={hopsOpen}
            >
              <span className="chev" aria-hidden>
                <IconChevronDown size={14} />
              </span>
              Telusur jalur ({trace.hops.length} hop)
            </button>
            {!hopsOpen && (
              <p className="muted" style={{ margin: 0, fontSize: '0.78rem', lineHeight: 1.6 }}>
                {/* Hop pelanggan tak ber-kode (cuma "Rumah pelanggan") — pakai namanya
                    agar remah-remah jalur tak diawali panah menggantung. */}
                {trace.hops.map((h) => h.code || h.name).join(' → ')}
              </p>
            )}
            {hopsOpen && (
              <ol className="timeline">
                {trace.hops.map((hop: TraceHop, i: number) => {
                  const hopColor =
                    hop.online == null ? undefined : hop.online ? 'var(--good-ink)' : 'var(--critical-ink)'
                  return (
                    <li key={`${hop.kind}-${hop.code}-${i}`}>
                      <span
                        className="tl-dot"
                        aria-hidden="true"
                        style={hopColor ? { background: hopColor } : undefined}
                      />
                      <div className="stack" style={{ gap: '0.1rem' }}>
                        <strong style={{ fontSize: '0.82rem', color: hopColor }}>
                          {[HOP_LABEL[hop.kind] ?? hop.kind, hop.code].filter(Boolean).join(' ')}
                        </strong>
                        <span className="muted" style={{ fontSize: '0.78rem' }}>
                          {hop.name}
                        </span>
                        {hop.detail && (
                          <span className="muted tnum" style={{ fontSize: '0.76rem' }}>
                            {hop.detail}
                          </span>
                        )}
                      </div>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        )}
      </div>
    </aside>
  )
}
