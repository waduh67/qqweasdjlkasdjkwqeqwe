import type { CommandAction } from '@/components/molecules'
import { IconCrosshair, IconRoute, IconTrash } from '@/components/atoms/icons'

/**
 * Aksi yang MUNCUL DI HAMPIR SEMUA panel aset peta.
 *
 * Dirakit di satu tempat karena yang membedakan panel ODP dari panel ODC adalah
 * isinya, bukan tombolnya: kalau tiap panel menulis sendiri labelnya, satu blade
 * berbunyi "Pindahkan" dan blade sebelahnya "Ubah lokasi" untuk perbuatan yang
 * sama persis — dan orang yang memakainya sepanjang hari harus membaca ulang
 * setiap kali berpindah aset.
 */

/**
 * Aksi "pindahkan lokasi" — sama persis di setiap panel aset, jadi dirakit sekali.
 * Labelnya dipendekkan jadi "Pindahkan": blade cuma selebar 28rem dan konteks
 * "lokasi" sudah jelas dari petanya sendiri.
 */
export function relocateAction(onClick: () => void, dividerBefore = false): CommandAction {
  return { key: 'relocate', label: 'Pindahkan', icon: <IconCrosshair size={15} />, onClick, dividerBefore }
}

/**
 * Aksi "Tarik kabel" di panel perangkat: ujung awalnya perangkat ini, tinggal klik
 * titik belok lalu perangkat tujuan. Ditaruh paling depan karena menarik kabel jauh
 * lebih sering dilakukan dari sebuah simpul ketimbang memindahkannya.
 */
export function cableAction(onClick: () => void): CommandAction {
  return { key: 'cable', label: 'Tarik kabel', icon: <IconRoute size={15} />, onClick }
}

/** Aksi hapus aset. Datar seperti "Hapus" di command bar halaman tabel, bukan tombol merah. */
export function deleteAction(label: string, onClick: () => void, disabled = false): CommandAction {
  return { key: 'delete', label, icon: <IconTrash size={15} />, onClick, disabled }
}
