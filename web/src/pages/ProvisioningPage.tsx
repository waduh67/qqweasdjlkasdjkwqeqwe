import { DiscoveredOnuInbox } from '@/components/organisms'
import { PageHeader } from '@/components/molecules'

/**
 * Halaman Provisioning ONU: kotak masuk auto-provisioning lintas semua OLT.
 *
 * Isinya diangkat ke {@link DiscoveredOnuInbox} agar bisa dipakai ulang sebagai tab
 * "ONU Baru" per-OLT di halaman detail OLT (pengelompokan ala kitabill). Di sini
 * kotak masuk tampil global (tanpa `oltId`) plus toggle auto-provisi zero-touch
 * tenant.
 */
export function ProvisioningPage() {
  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <PageHeader title="Provisioning ONU" />
      <DiscoveredOnuInbox showAutoProvision />
    </div>
  )
}
