import { type ReactNode } from 'react'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseWorkflow } from './WarehouseWorkflowContext'

export function WorkflowSurface({ reference, legacy }: { reference: ReactNode; legacy: ReactNode }) {
  const result = useWarehouseWorkflow()
  return <WarehouseState {...result}>{data => data.workflow === 'REFERENCE' ? reference : legacy}</WarehouseState>
}
