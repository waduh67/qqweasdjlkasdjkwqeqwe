import { useEffect, useState } from 'react'
import type { FieldTechnician } from '@/api/field'
import { useAuth } from '@/auth/useAuth'

export function useFieldTechnicians(current: readonly FieldTechnician[] | undefined) {
  const { user } = useAuth()
  const [saved, setSaved] = useState<{ readonly actor: string | undefined; readonly members: readonly FieldTechnician[] } | null>(null)
  useEffect(() => { if (current) setSaved({ actor: user?.id, members: current }) }, [current, user?.id])
  return current ?? (saved?.actor === user?.id ? saved?.members ?? [] : [])
}
