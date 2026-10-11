import { Link } from 'react-router-dom'
import { Button, EmptyState } from '@/components/atoms'
import { IconPlus } from '@/components/atoms/icons'
import { PageHeader } from '@/components/molecules'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { ExpressPsbFields } from './onboarding/ExpressPsbFields'
import { ExpressPsbResultCard } from './onboarding/ExpressPsbResultCard'
import { useExpressPsb } from './onboarding/useExpressPsb'

export function ExpressPsbPage() {
  const model = useExpressPsb()
  if (!model.canManage) return <div role="alert"><EmptyState title="Akses PSB dibatasi" hint="PSB memerlukan izin membuat pelanggan, mengubah langganan, mengelola akun jaringan, dan membuat work order." icon={<IconPlus size={32} />} /></div>
  return <div className="stack" style={{ gap: '1.25rem' }}>
    <PageHeader title="PSB Ekspres" subtitle="Daftarkan pelanggan, layanan, dan pekerjaan pemasangan." />
    {model.result && <ExpressPsbResultCard result={model.result} reference={model.reference} onDismiss={() => model.setResult(null)} />}
    <WarehouseState {...model.workflow}>{() => null}</WarehouseState>
    {model.loading ? <div role="status">Memuat paket internet…</div> : model.loadError
      ? <div role="alert" className="stack"><p>{model.loadError}</p><div><Button onClick={model.reloadPlans}>Coba lagi</Button></div></div>
      : model.plans.length === 0
      ? <div role="status" className="stack">
          <div><strong>Belum ada paket aktif</strong><p>Tambahkan paket internet untuk mulai mendaftarkan pelanggan.</p></div>
          <div className="row">
            {model.canViewPlans && <Link to="/catalog">Kelola paket</Link>}
            <Button onClick={model.reloadPlans}>Segarkan</Button>
          </div>
        </div>
      : <ExpressPsbFields {...model} />}
  </div>
}
