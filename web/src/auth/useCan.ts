import { useAuth } from './useAuth'

/**
 * Cermin dari `@authz.can(...)` di sisi server: platform admin melewati semua
 * pengecekan, selain itu izin harus ada pada profil.
 *
 * Ini murni untuk pengalaman pengguna (menyembunyikan menu/tombol) — penegakan
 * yang sesungguhnya tetap di server.
 */
export function useCan() {
  const { user } = useAuth()

  const can = (permission: string): boolean => {
    if (!user) return false
    return user.platformAdmin || user.permissions.includes(permission)
  }

  const canAny = (...permissions: string[]): boolean => permissions.some(can)

  return { can, canAny, isPlatformAdmin: user?.platformAdmin ?? false }
}
