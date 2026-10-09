import { createContext, useContext, type ReactNode } from 'react'
import { readWorkflow } from '@/api/warehouse/reference'
import { useAuth } from '@/auth/useAuth'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

type WorkflowResult = ReturnType<typeof useWarehouseQuery<Awaited<ReturnType<typeof readWorkflow>>>>
const WarehouseWorkflowContext = createContext<WorkflowResult | null>(null)

export function WarehouseWorkflowProvider({ children }: { readonly children: ReactNode }) {
  const { user } = useAuth()
  const scope = `${user?.id}:${user?.tenantId}`
  return <ScopedWorkflow key={scope} scope={scope}>{children}</ScopedWorkflow>
}

function ScopedWorkflow({ children, scope }: { readonly children: ReactNode; readonly scope: string }) {
  const result = useWarehouseQuery(readWorkflow, scope)
  return <WarehouseWorkflowContext.Provider value={result}>{children}</WarehouseWorkflowContext.Provider>
}

export function useWarehouseWorkflow() {
  const result = useContext(WarehouseWorkflowContext)
  if (!result) throw new Error('Warehouse workflow requires its tenant provider.')
  return result
}
