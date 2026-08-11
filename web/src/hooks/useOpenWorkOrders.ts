import { useCallback } from 'react'
import { searchOpenWorkOrders } from '@/api/workorder'
import { useCan } from '@/auth/useCan'

/**
 * Pemasok kandidat tiket untuk pemilih "kerja ini bagian dari tugas mana" — dipakai
 * meja splicing dan pindah core, dua tempat yang sama-sama mencatat kerja lapangan.
 *
 * Sumbernya ditentukan izin pemakainya: dispatcher/operator (`workorder.order.view`)
 * mencari ke seluruh papan, teknisi lapangan hanya melihat tugasnya sendiri. Yang tak
 * punya keduanya ([canPick] false) tetap boleh bekerja — cuma tanpa penunjuk tiket,
 * sebab tiket memang opsional di server.
 */
export function useOpenWorkOrders() {
  const { can } = useCan()
  const searchesAll = can('workorder.order.view')
  const canPick = searchesAll || can('workorder.order.field')

  const fetchWorkOrders = useCallback(
    (term: string) => searchOpenWorkOrders(term, !searchesAll),
    [searchesAll],
  )

  return { canPick, searchesAll, fetchWorkOrders }
}
