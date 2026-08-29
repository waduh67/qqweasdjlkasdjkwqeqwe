import { MessageBar, MessageBarBody, Text, typographyStyles } from '@fluentui/react-components'
import type { CableCutView } from '@/api/network'
import { BladeHead, Ess } from '@/components/molecules'
import { TYPE_LABEL } from '@/map/cableFormat'
import { AffectedRow } from './AffectedRow'

const CUT_ROOT_LABEL: Record<string, string> = {
  ODC: 'ODC + seluruh hilirnya',
  ODP: 'ODP sasaran',
  CUSTOMER: 'satu pelanggan',
}

/**
 * Panel simulasi "kalau kabel ini putus, siapa yang kena". Kabel drop menjatuhkan
 * satu pelanggan, distribusi satu ODP, feeder satu ODC beserta segenap subpohonnya
 * — dampaknya ditentukan simpul di ujung hilir kabel, yang ditandai di sini.
 *
 * Satu selubung sering dikupas di beberapa kotak sekaligus, dan kotak-kotak itu
 * cuma terbaca lewat catatan splicing. Karena itu panelnya ikut menyebut angkanya
 * datang dari mana: yang belum didata adalah taksiran gambar, dan itu dikatakan
 * apa adanya alih-alih dibiarkan tampak sama meyakinkan.
 */
export function CableCutPanel({ cut, onClose }: { cut: CableCutView; onClose: () => void }) {
  const withPhone = cut.customers.filter((c) => c.phone).length
  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Simulasi putus"
        subtitle={`Kabel ${TYPE_LABEL[cut.cableType]} · ${CUT_ROOT_LABEL[cut.severedRootKind] ?? cut.severedRootKind}`}
        onClose={onClose}
      />

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent="warning">
          <MessageBarBody>Kalau ruas ini putus, {cut.customerCount} pelanggan kehilangan layanan.</MessageBarBody>
        </MessageBar>

        <dl className="essentials">
          <Ess label="Dasar hitungan">
            {cut.fromSplicing ? (
              <span className="badge" style={{ color: 'var(--good-ink)', borderColor: 'var(--good-ink)' }}>
                catatan splicing
              </span>
            ) : (
              <span className="dim">gambar kabel</span>
            )}
          </Ess>
          <Ess label="ODC terdampak">{cut.odcCount > 0 && cut.odcCount}</Ess>
          <Ess label="ODP terdampak">{cut.odpCount > 0 && cut.odpCount}</Ess>
          <Ess label="Pelanggan">{cut.customerCount}</Ess>
          <Ess label="Sudah mati">
            {cut.downCount > 0 && <Text as="span" weight="semibold" style={{ color: 'var(--critical-ink)' }}>{cut.downCount}</Text>}
          </Ess>
          <Ess label="Siap broadcast">{withPhone > 0 && `${withPhone} nomor`}</Ess>
        </dl>

        {cut.customers.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Pelanggan terdampak ({cut.customers.length})</p>
            <div className="stack" style={{ gap: '0.3rem', maxHeight: 280, overflowY: 'auto' }}>
              {cut.customers.map((c) => (
                <AffectedRow key={c.customerId} c={c} />
              ))}
            </div>
          </div>
        )}

        {cut.warnings.map((w) => (
          <Text as="p" key={w} className="dim" size={100} style={{ ...typographyStyles.caption2, margin: 0 }}>
            {w}
          </Text>
        ))}
      </div>
    </aside>
  )
}
