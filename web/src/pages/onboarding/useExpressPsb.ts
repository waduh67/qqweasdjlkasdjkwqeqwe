import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '@/api/client'
import type { Area, PageResponse, Role, User } from '@/api/types'
import { listNas, type NasView } from '@/api/bng'
import { listPlans, type PlanView, type ServiceType } from '@/api/catalog'
import { onboardPsb, type ExpressPsbResult } from '@/api/onboarding'
import { useCan } from '@/auth/useCan'
import { useToast } from '@/system'
import { useWarehouseWorkflow } from '@/pages/warehouse/WarehouseWorkflowContext'

/** Alfabet secret (tanpa 0/O/1/l/I) — cermin konvensi generator server agar mudah dibaca operator. */
const SECRET_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'

/** Password acak 12 karakter untuk PPPoE/Hotspot; operator boleh mengubahnya. */
function randomSecret(len = 12): string {
  const bytes = new Uint32Array(len)
  crypto.getRandomValues(bytes)
  let out = ''
  for (let i = 0; i < len; i++) out += SECRET_ALPHABET[bytes[i] % SECRET_ALPHABET.length]
  return out
}

export type PsbDraft = {
  readonly code: string; readonly name: string; readonly phone: string; readonly email: string;
  readonly address: string; readonly areaId: string; readonly longitude: string; readonly latitude: string;
  readonly monthlyFeeOverride: string; readonly username: string; readonly title: string; readonly description: string;
  readonly scheduledAt: string; readonly assignees: string[]; readonly framedIp: string;
}
const EMPTY: PsbDraft = {
  code: '',
  name: '',
  phone: '',
  email: '',
  address: '',
  areaId: '',
  longitude: '',
  latitude: '',
  monthlyFeeOverride: '',
  username: '',
  title: '',
  description: '',
  scheduledAt: '',
  assignees: [],
  framedIp: '',
}

type Draft = PsbDraft

const toInstant = (local: string): string | null => (local ? new Date(local).toISOString() : null)

export function useExpressPsb() {
  const { can } = useCan()
  const toast = useToast()
  const workflow = useWarehouseWorkflow()
  const workflowMode = workflow.state.status === 'ready' ? workflow.state.data.workflow : null
  const reference = workflowMode === 'REFERENCE'

  const [plans, setPlans] = useState<PlanView[]>([])
  const [nasList, setNasList] = useState<NasView[]>([])
  const [areas, setAreas] = useState<Area[]>([])
  const [technicians, setTechnicians] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loadAttempt, setLoadAttempt] = useState(0)

  const [draft, setDraft] = useState<Draft>({ ...EMPTY })
  // Password dipisah dari draft agar tombol Generate/Lihat sederhana.
  const [secret, setSecret] = useState(randomSecret)
  const [showSecret, setShowSecret] = useState(false)
  const [planId, setPlanId] = useState('')
  const [nasId, setNasId] = useState('')
  const [authType, setAuthType] = useState<ServiceType>('PPPOE')
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<(ExpressPsbResult & { secretUsed: string }) | null>(null)

  const canManage = ['customer.customer.create', 'customer.subscription.update', 'bng.access.manage', 'workorder.order.create'].every(can)
  const canViewPlans = can('catalog.plan.view')

  useEffect(() => {
    if (!canManage) return
    let active = true
    setLoading(true)
    setLoadError(null)
    void Promise.all([
      listPlans(),
      listNas().catch(() => []),
    ])
      .then(([p, n]) => {
        if (!active) return
        const available = p.filter((x) => x.active)
        setPlans(available)
        setNasList(n.filter((x) => x.enabled))
        if (available.length > 0) {
          setPlanId(available[0].id)
          setAuthType(available[0].serviceTypes[0] ?? 'PPPOE')
        }
      })
      .catch((error) => {
        if (active) setLoadError(error instanceof ApiError && error.status === 403
          ? 'Anda tidak memiliki akses ke paket internet.'
          : 'Paket internet belum berhasil dimuat.')
      })
      .finally(() => { if (active) setLoading(false) })

    // Area best-effort — dipakai memilih area pelanggan sekaligus auto-pilih BRAS dari cakupannya.
    void api
      .get<Area[]>('/api/areas')
      .then(rows => { if (active) setAreas(rows) })
      .catch(() => { if (active) setAreas([]) })
    return () => { active = false }
  }, [canManage, loadAttempt])

  useEffect(() => {
    if (workflowMode !== 'LEGACY') return
    let active = true
    void Promise.all([
      api.get<PageResponse<User>>('/api/users?size=200').then(page => page.content).catch(() => []),
      api.get<Role[]>('/api/roles').catch(() => []),
    ]).then(([users, roles]) => {
      if (!active) return
      const available = users.filter(user => user.status === 'ACTIVE')
      const role = roles.find(row => row.name === 'Teknisi')
      setTechnicians(role ? available.filter(user => user.roleIds.includes(role.id)) : available)
    })
    return () => { active = false }
  }, [workflowMode])

  // Peta area → BRAS (dari cakupan tiap BRAS). Dasar auto-pilih BRAS saat area dipilih.
  const nasByArea = useMemo(() => {
    const map = new Map<string, string>()
    nasList.forEach((n) => n.areaIds.forEach((areaId) => map.set(areaId, n.id)))
    return map
  }, [nasList])

  const selectedPlan = useMemo(() => plans.find((p) => p.id === planId), [plans, planId])
  const availableTypes: ServiceType[] = selectedPlan?.serviceTypes ?? []
  const macBased = authType === 'DHCP' || authType === 'STATIC'

  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))

  const changePlan = (id: string) => {
    setPlanId(id)
    const p = plans.find((x) => x.id === id)
    if (p && !p.serviceTypes.includes(authType)) setAuthType(p.serviceTypes[0] ?? 'PPPOE')
  }

  // Pilih area → auto-isi BRAS dari cakupan area itu (operator tetap boleh menimpanya di bawah).
  const changeArea = (areaId: string) => {
    set({ areaId })
    const auto = nasByArea.get(areaId)
    if (auto) setNasId(auto)
  }

  const invalid =
    !draft.name.trim() ||
    !draft.address.trim() ||
    !draft.longitude.trim() ||
    !draft.latitude.trim() ||
    Number.isNaN(Number(draft.longitude)) ||
    Number.isNaN(Number(draft.latitude)) ||
    !planId ||
    (macBased && !draft.username.trim()) ||
    (authType === 'STATIC' && !draft.framedIp.trim()) ||
    (!macBased && !secret.trim())

  const submit = async () => {
    if (!canManage || saving || loading || loadError || workflowMode === null) return false
    if (invalid) {
      toast.error('Lengkapi data pelanggan dan layanan.')
      return false
    }
    setSaving(true)
    try {
      const res = await onboardPsb({
        code: draft.code.trim() || undefined,
        name: draft.name.trim(),
        phone: draft.phone.trim() || null,
        email: draft.email.trim() || null,
        address: draft.address.trim(),
        areaId: draft.areaId || null,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
        planId,
        monthlyFeeOverride: draft.monthlyFeeOverride.trim() ? Number(draft.monthlyFeeOverride) : null,
        // MAC-based → username = MAC, tanpa secret; PPPoE/Hotspot → username opsional (server generate) + secret.
        username: macBased ? draft.username.trim() : draft.username.trim() || null,
        secret: macBased ? null : secret,
        serviceType: authType,
        nasId: nasId || null,
        framedIp: macBased ? draft.framedIp.trim() || null : null,
        title: draft.title.trim() || null,
        description: draft.description.trim() || null,
        scheduledAt: toInstant(draft.scheduledAt),
        assignees: reference ? [] : draft.assignees,
      })
      setResult({ ...res, secretUsed: macBased ? res.username : secret })
      toast.success(`PSB ${res.workOrderCode} dibuat untuk ${draft.name.trim()}`)
      // Reset untuk entri berikutnya; paket/BRAS/teknisi dipertahankan agar batch cepat.
      setDraft({ ...EMPTY })
      setSecret(randomSecret())
      setShowSecret(false)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal membuat PSB ekspres')
      return false
    } finally {
      setSaving(false)
    }
  }

  return { canManage, canViewPlans, workflow, workflowMode, reference, plans, nasList, areas, technicians, loading, loadError,
    reloadPlans: () => setLoadAttempt(attempt => attempt + 1), draft,
    secret, showSecret, planId, nasId, authType, saving, result, nasByArea, availableTypes, macBased, invalid,
    set, changePlan, changeArea, setAuthType, setNasId, setSecret, setShowSecret, randomSecret, submit, setResult, resetDraft: () => { setDraft({ ...EMPTY }); setSecret(randomSecret()); setShowSecret(false) } }
}
