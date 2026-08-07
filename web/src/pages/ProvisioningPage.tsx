import { DiscoveredOnuInbox } from '../components/DiscoveredOnuInbox'

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
      <div>
        <h1 className="page-title">Provisioning ONU</h1>
        <p className="page-sub">
          Perangkat yang dilaporkan OLT tapi belum terdaftar. Sistem menebak pelanggan, ODP, dan port
          dari topologi — tinggal periksa lalu tuntaskan.
        </p>
      </div>
      <DiscoveredOnuInbox showAutoProvision />
    </div>
  )
}
