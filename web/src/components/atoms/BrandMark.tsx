import type { SVGProps } from 'react'

export type BrandMarkProps = SVGProps<SVGSVGElement> & { size?: number }

/**
 * Lambang merek NetOps Console: satu feeder (cincin) memecah lewat splitter menjadi
 * tiga drop (simpul padat) — topologi PON yang persis dikelola aplikasi ini.
 *
 * Tiga keputusan bentuknya, supaya tak diubah tanpa sengaja:
 *
 *  1. **Tikungannya membulat, bukan siku.** Serat optik punya radius tekuk minimum;
 *     lambang yang menekuk 90° derajat menggambarkan sesuatu yang di lapangan berarti
 *     serat patah. Detail kecil yang membuat lambang ini benar, bukan sekadar
 *     "gambar jaringan".
 *  2. **Sumber = cincin, ujung = simpul padat.** Hierarki dibaca dari bentuk, bukan
 *     dari warna — jadi tetap terbaca saat dicetak hitam-putih atau di tile satu warna.
 *  3. **Grid 32 dengan stroke 2.4.** Ditera pada favicon 16px: di bawah ketebalan ini
 *     ketiga cabangnya melebur jadi satu gumpalan.
 *
 * Memakai `currentColor` supaya bisa duduk di dalam tile gradasi `.logo` (putih) maupun
 * di atas latar terang (aksen biru) tanpa berkas kedua. Bentuk kanonisnya digandakan di
 * `public/favicon.svg`, `public/logo-mark.svg`, dan `public/logo-netops.svg` — kalau
 * geometri di sini berubah, keempatnya harus ikut.
 */
export function BrandMark({ size = 20, ...props }: BrandMarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden
      focusable="false"
      {...props}
    >
      <g
        stroke="currentColor"
        strokeWidth={2.4}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="7.6" cy="16" r="2.9" />
        <path d="M11.7 16H22.8" />
        <path d="M16.8 16v-5a3 3 0 0 1 3-3h3" />
        <path d="M16.8 16v5a3 3 0 0 0 3 3h3" />
      </g>
      <g fill="currentColor">
        <circle cx="25.6" cy="8" r="2.6" />
        <circle cx="25.6" cy="16" r="2.6" />
        <circle cx="25.6" cy="24" r="2.6" />
      </g>
    </svg>
  )
}
