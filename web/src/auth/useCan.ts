import { useCallback, useMemo } from 'react'
import { useAuth } from './useAuth'

/**
 * Cermin dari `@authz.can(...)` di sisi server: platform admin melewati semua
 * pengecekan, selain itu izin harus ada pada profil.
 *
 * Ini murni untuk pengalaman pengguna (menyembunyikan menu/tombol) — penegakan
 * yang sesungguhnya tetap di server.
 *
 * `can`/`canAny` di-memo pada objek `user` agar referensinya stabil antar-render.
 * Tanpa ini, komponen yang menaruh `can` di dependency `useEffect` (mis. Dashboard)
 * akan memicu loop refetch tak berujung: fungsi baru tiap render → efek jalan lagi
 * → set state → render lagi.
 */
export function useCan() {
  const { user } = useAuth()

  const can = useCallback(
    (permission: string): boolean => {
      if (!user) return false
      return user.platformAdmin || user.permissions.includes(permission)
    },
    [user],
  )

  const canAny = useCallback((...permissions: string[]): boolean => permissions.some(can), [can])

  return useMemo(
    () => ({ can, canAny, isPlatformAdmin: user?.platformAdmin ?? false }),
    [can, canAny, user],
  )
}
