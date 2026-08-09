import type { ReactNode } from 'react'

/**
 * Satu baris properti pada daftar "Essentials" gaya Azure Portal: pasangan
 * label:nilai di dalam `<dl className="essentials">`.
 *
 * Baris dengan nilai kosong SENGAJA tak dirender. Ringkasan sumber daya yang
 * dipenuhi baris "—" memaksa mata memindai hal yang tak ada; yang berguna justru
 * daftar pendek berisi apa yang memang diketahui. Pemanggil yang benar-benar butuh
 * placeholder cukup mengoper `value ?? '—'` secara eksplisit.
 */
export function Ess({ label, children }: { label: string; children: ReactNode }) {
  if (children == null || children === false || children === '') return null
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  )
}
