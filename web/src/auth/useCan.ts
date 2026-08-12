import { useCallback, useMemo } from 'react'
import { useAuth } from './useAuth'

/**
 * Izin yang tetap hidup meski konsol terkunci baca-saja. Daftarnya kecil dan disengaja:
 * tanpa `billing.subscription.renew`, tenant yang menunggak tak punya jalan untuk melunasi —
 * kuncinya jadi jebakan, bukan dorongan. Cermin `ALWAYS_ALLOWED` di `AccessChecker`.
 */
const ALWAYS_ALLOWED = ['billing.subscription.renew']

/**
 * Cermin dari `@authz.can(...)` di sisi server: platform admin melewati semua
 * pengecekan, selain itu izin harus ada pada profil — dan saat langganan aplikasi
 * menunggak, semua izin TULIS ikut mati (konvensi: `*.view` = baca, sisanya tulis).
 *
 * Ini murni untuk pengalaman pengguna (menyembunyikan menu/tombol) — penegakan
 * yang sesungguhnya tetap di server, yang membalas 402 `SUBSCRIPTION_LOCKED`. Menonaktifkan
 * tombolnya di sini yang membuat bedanya terasa: pengguna melihat konsolnya membeku sebagai
 * keadaan, bukan sebagai deretan aksi yang gagal satu per satu.
 *
 * `can`/`canAny` di-memo pada objek `user` agar referensinya stabil antar-render.
 * Tanpa ini, komponen yang menaruh `can` di dependency `useEffect` (mis. Dashboard)
 * akan memicu loop refetch tak berujung: fungsi baru tiap render → efek jalan lagi
 * → set state → render lagi.
 */
export function useCan() {
  const { user, readOnly } = useAuth()

  const can = useCallback(
    (permission: string): boolean => {
      if (!user) return false
      if (!user.platformAdmin && !user.permissions.includes(permission)) return false
      if (readOnly && isWrite(permission) && !ALWAYS_ALLOWED.includes(permission)) return false
      return true
    },
    [user, readOnly],
  )

  const canAny = useCallback((...permissions: string[]): boolean => permissions.some(can), [can])

  return useMemo(
    () => ({ can, canAny, isPlatformAdmin: user?.platformAdmin ?? false }),
    [can, canAny, user],
  )
}

/** Izin baca selalu berakhiran `.view`; sisanya mengubah sesuatu. Lihat `PermissionCatalog.kt`. */
function isWrite(permission: string): boolean {
  return !permission.endsWith('.view')
}
