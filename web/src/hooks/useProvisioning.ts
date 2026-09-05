import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import {
  applyProvisioningPlan,
  listServiceIntents,
  previewProvisioning,
  type ExecutionView,
  type PlanPreview,
  type ProvisioningMode,
  type RevisionedResource,
  type ServiceIntentView,
} from '@/api/provisioning'

type ApplyAttempt = {
  readonly planId: string
  readonly revision: number
  readonly idempotencyKey: string
}

export function useProvisioningDraft<T>(initialDraft: T) {
  const [draft, setDraft] = useState(initialDraft)
  const [preview, setPreview] = useState<PlanPreview | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [previewing, setPreviewing] = useState(false)
  const controller = useRef<AbortController | null>(null)

  useEffect(() => () => controller.current?.abort(), [])

  const updateDraft = useCallback((nextDraft: T) => {
    controller.current?.abort()
    controller.current = null
    setPreviewing(false)
    setDraft(nextDraft)
    setPreview(null)
    setError(null)
  }, [])

  const previewPlan = useCallback(async (
    planId: string,
    mode: Exclude<ProvisioningMode, 'PRODUCTION_AUTO_APPLY'>,
  ) => {
    controller.current?.abort()
    const nextController = new AbortController()
    controller.current = nextController
    setError(null)
    setPreviewing(true)
    try {
      const nextPreview = await previewProvisioning(planId, mode, nextController.signal)
      if (controller.current === nextController && !nextController.signal.aborted) setPreview(nextPreview)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (controller.current === nextController) setError(cause)
    } finally {
      if (controller.current === nextController) {
        controller.current = null
        setPreviewing(false)
      }
    }
  }, [])

  return {
    draft,
    setDraft: updateDraft,
    preview,
    serverPlanRevision: preview?.plan.revision ?? null,
    error,
    previewing,
    previewPlan,
  }
}

export function useProvisioningApply() {
  const [execution, setExecution] = useState<ExecutionView | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [applying, setApplying] = useState(false)
  const attempt = useRef<ApplyAttempt | null>(null)

  const run = useCallback(async (current: ApplyAttempt) => {
    setApplying(true)
    setError(null)
    try {
      setExecution(await applyProvisioningPlan(current.planId, current.revision, current.idempotencyKey))
    } catch (cause) {
      setError(cause)
    } finally {
      setApplying(false)
    }
  }, [])

  const apply = useCallback(async (planId: string, revision: number) => {
    const nextAttempt = { planId, revision, idempotencyKey: crypto.randomUUID() }
    attempt.current = nextAttempt
    await run(nextAttempt)
  }, [run])

  const canRetry = error !== null && (!(error instanceof ApiError) || error.status >= 500)
  const retry = useCallback(async () => {
    if (!canRetry || !attempt.current) return
    await run(attempt.current)
  }, [canRetry, run])

  return { execution, error, applying, canRetry, apply, retry, track: setExecution }
}

export function useProvisioningIntents() {
  const [intents, setIntents] = useState<readonly RevisionedResource<ServiceIntentView>[]>([])
  const [error, setError] = useState<unknown>(null)
  const controller = useRef<AbortController | null>(null)

  const reload = useCallback(async () => {
    controller.current?.abort()
    const nextController = new AbortController()
    controller.current = nextController
    setError(null)
    try {
      const nextIntents = await listServiceIntents(nextController.signal)
      if (!nextController.signal.aborted) setIntents(nextIntents)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      setError(cause)
    }
  }, [])

  useEffect(() => () => controller.current?.abort(), [])
  return { intents, error, reload }
}
