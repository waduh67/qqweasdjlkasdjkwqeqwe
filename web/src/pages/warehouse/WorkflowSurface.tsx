import { type ReactNode } from 'react'
import { useAuth } from '@/auth/useAuth'
import { readWorkflow } from '@/api/warehouse/reference'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'

export function WorkflowSurface({ reference, legacy }: { reference: ReactNode; legacy: ReactNode }) {
  const { user } = useAuth()
  const result = useWarehouseQuery(readWorkflow, `${user?.id}:${user?.tenantId}`)
  return <WarehouseState {...result}>{data => data.workflow === 'REFERENCE' ? reference : legacy}</WarehouseState>
}
