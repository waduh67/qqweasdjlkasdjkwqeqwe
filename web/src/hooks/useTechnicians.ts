import { useCallback, useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { PageResponse, Role, User } from '@/api/types'

/**
 * Daftar teknisi (pemegang role "Teknisi") untuk pemilih penugasan & filter, dipakai
 * bersama papan dispatch dan halaman detail work order. Best-effort: bila operator tak
 * berizin melihat user/role, daftarnya kosong dan pemakainya tetap jalan. `fetchTechnicians`
 * menyaring lokal agar antarmuka combobox seragam dengan pencarian sisi-server lain.
 *
 * `enabled=false` melewati pemuatan (mis. teknisi lapangan yang tak bisa menugaskan) agar
 * tak menembak `/api/users` yang pasti 403.
 */
export function useTechnicians(enabled = true) {
  const [technicians, setTechnicians] = useState<User[]>([])

  useEffect(() => {
    if (!enabled) return
    // Disaring ke pemegang role "Teknisi" (bukan semua user aktif) agar penugasan hanya
    // jatuh ke petugas lapangan; bila role belum ada / tak berizin lihat roles, jatuh
    // balik ke semua user aktif supaya pemilih tetap terisi.
    void Promise.all([
      api.get<PageResponse<User>>('/api/users?size=200'),
      api.get<Role[]>('/api/roles').catch(() => [] as Role[]),
    ])
      .then(([users, roles]) => {
        const active = users.content.filter((u) => u.status === 'ACTIVE')
        const technicianRole = roles.find((r) => r.name === 'Teknisi')
        setTechnicians(
          technicianRole ? active.filter((u) => u.roleIds.includes(technicianRole.id)) : active,
        )
      })
      .catch(() => setTechnicians([]))
  }, [enabled])

  const fetchTechnicians = useCallback(
    async (term: string): Promise<User[]> => {
      const t = term.toLowerCase()
      return t ? technicians.filter((u) => u.name.toLowerCase().includes(t)) : technicians
    },
    [technicians],
  )

  return { technicians, fetchTechnicians }
}
