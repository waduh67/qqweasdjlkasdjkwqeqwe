import { MessageBar, MessageBarBody, Text } from '@fluentui/react-components'
import type { OltView } from '@/api/network'
import { StatusBadge } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { BladeHead, CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { cableAction, relocateAction } from './mapActions'

/**
 * Panel sebuah OLT saat markernya diklik: identitas perangkat (vendor/model/IP),
 * status, kesiapan SNMP, dan jumlah port PON — seragam dengan panel ODC/ODP/site.
 * Sengaja tanpa tombol hapus: OLT adalah perangkat inti dengan banyak hilir, jadi
 * penghapusan hanya dari halaman detail yang lebih sengaja lewat "Buka detail"
 * (di sana pun server menolak selama masih ada ODC menggantung).
 */
export function OltPanel({
  olt,
  canView,
  canRelocate,
  onRelocate,
  onDrawCable,
  onOpenDetail,
  onClose,
}: {
  olt: OltView
  canView: boolean
  canRelocate: boolean
  onRelocate: () => void
  /** Kosong = ujung awal kabel tak boleh dari sini (tak berizin / titiknya tak diketahui). */
  onDrawCable?: () => void
  onOpenDetail: () => void
  onClose: () => void
}) {
  const primary: CommandAction | undefined = canView
    ? { key: 'detail', label: 'Buka detail', icon: <IconMonitor size={15} />, onClick: onOpenDetail }
    : undefined
  const actions: CommandAction[] = []
  if (onDrawCable) actions.push(cableAction(onDrawCable))
  if (canRelocate) actions.push(relocateAction(onRelocate))

  return (
    <aside className="map-panel blade">
      <BladeHead title={olt.code} subtitle={`OLT · ${olt.name}`} onClose={onClose} />
      {(primary || actions.length > 0) && <CommandBar primary={primary} actions={actions} />}

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        {/* SNMP mati bukan sekadar keterangan — tanpa itu OLT ini tak terpantau sama
            sekali, jadi diangkat jadi peringatan alih-alih lencana abu di tengah baris. */}
        {!olt.pollable && (
          <MessageBar intent="warning">
            <MessageBarBody>SNMP belum diset — status ONU di bawah OLT ini tak akan pernah ter-poll.</MessageBarBody>
          </MessageBar>
        )}

        <dl className="essentials">
          <Ess label="Status">
            <StatusBadge status={olt.status} />
          </Ess>
          <Ess label="Vendor">{olt.vendor}</Ess>
          <Ess label="Model">{olt.model}</Ess>
          <Ess label="Port PON">{olt.ponPortCount}</Ess>
          <Ess label="Site">{olt.siteName}</Ess>
          <Ess label="IP manajemen">{olt.managementIp && <span className="tnum">{olt.managementIp}</span>}</Ess>
          <Ess label="SNMP">
            {olt.pollable ? (
              <>
                <StatusBadge status="ACTIVE" label="Siap" />
                <span className="muted"> · port {olt.snmpPort}</span>
              </>
            ) : (
              <span className="muted">Belum diset</span>
            )}
          </Ess>
        </dl>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Perangkat inti: kalau OLT ini modar, seluruh jalur di hilirnya ikut mati.
        </Text>
      </div>
    </aside>
  )
}
