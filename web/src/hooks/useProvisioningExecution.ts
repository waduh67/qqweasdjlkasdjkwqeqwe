import { useEffect, useState } from 'react'
import {
  getProvisioningExecution,
  type ExecutionStatus,
  type ExecutionView,
} from '@/api/provisioning'

const INITIAL_DELAY_MS = 500
const MAX_DELAY_MS = 5_000

export function isProvisioningExecutionActive(status: ExecutionStatus): boolean {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
    case 'VERIFYING':
    case 'ROLLING_BACK':
      return true
    case 'SUCCEEDED':
    case 'FAILED':
    case 'ROLLED_BACK':
    case 'MANUAL_RECONCILIATION':
    case 'CANCELLED':
      return false
  }
}

export function useProvisioningExecution(executionId: string | null) {
  const [execution, setExecution] = useState<ExecutionView | null>(null)
  const [history, setHistory] = useState<readonly ExecutionView[]>([])
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    if (!executionId) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let delay = INITIAL_DELAY_MS

    const poll = async (): Promise<void> => {
      try {
        const next = await getProvisioningExecution(executionId, controller.signal)
        if (controller.signal.aborted) return
        setExecution(next)
        setHistory((current) => [...current, next])
        setError(null)
        if (isProvisioningExecutionActive(next.status)) {
          timer = setTimeout(() => void poll(), delay)
          delay = Math.min(delay * 2, MAX_DELAY_MS)
        }
      } catch (cause) {
        if (cause instanceof DOMException && cause.name === 'AbortError') return
        setError(cause)
      }
    }

    void poll()
    return () => {
      controller.abort()
      if (timer !== undefined) clearTimeout(timer)
    }
  }, [executionId])

  return { execution, history, error }
}
