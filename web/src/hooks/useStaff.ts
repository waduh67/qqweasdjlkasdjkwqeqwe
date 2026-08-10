import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { PageResponse, User } from '@/api/types'

/**
 * Daftar pengguna AKTIF tenant untuk pemilih penanggung jawab yang bukan pekerjaan lapangan
 * (mis. penugasan tiket helpdesk). Berbeda dari `useTechnicians` yang sengaja menyempit ke
 * pemegang role "Teknisi": keluhan pelanggan dijawab siapa pun yang berjaga — admin,
 * penagihan, penyelia — dan menyaringnya ke teknisi hanya akan mengosongkan pemilihnya di
 * ISP yang orang kantornya merangkap operator.
 *
 * Best-effort: bila operator tak berizin melihat daftar user, hasilnya kosong dan pemakainya
 * tetap jalan (tombol penugasan tinggal tak menawarkan siapa-siapa) — bukan halaman gagal.
 */
export function useStaff(enabled = true) {
  const [staff, setStaff] = useState<User[]>([])

  useEffect(() => {
    if (!enabled) return
    void api
      .get<PageResponse<User>>('/api/users?size=200')
      .then((page) => setStaff(page.content.filter((u) => u.status === 'ACTIVE')))
      .catch(() => setStaff([]))
  }, [enabled])

  return staff
}
