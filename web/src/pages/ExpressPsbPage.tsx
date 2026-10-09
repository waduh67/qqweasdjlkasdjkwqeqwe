import { EmptyState } from '@/components/atoms'
import { IconPackage, IconPlus } from '@/components/atoms/icons'
import { PageHeader } from '@/components/molecules'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { ExpressPsbFields } from './onboarding/ExpressPsbFields'
import { ExpressPsbResultCard } from './onboarding/ExpressPsbResultCard'
import { useExpressPsb } from './onboarding/useExpressPsb'

export function ExpressPsbPage() {
  const model = useExpressPsb()
  if (!model.canManage) return <div className="card"><EmptyState title="Tak berizin" hint="Anda tidak memiliki izin membuat pelanggan." icon={<IconPlus size={32} />} /></div>
  return <div className="stack" style={{ gap: '1.25rem' }}>
    <PageHeader title="PSB Express" subtitle="Layanan aktif setelah teknisi menuntaskan Work Order PSB." />
    {model.result && <ExpressPsbResultCard result={model.result} reference={model.reference} onDismiss={() => model.setResult(null)} />}
    <WarehouseState {...model.workflow}>{() => null}</WarehouseState>
    {model.loading ? <div className="card">Memuat paket…</div> : model.plans.length === 0
      ? <div className="card"><EmptyState title="Belum ada paket aktif" icon={<IconPackage size={32} />} /></div>
      : <ExpressPsbFields {...model} />}
  </div>
}
