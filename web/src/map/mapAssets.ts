import type { AccessNodeKind } from '@/components/organisms'
import { JOINT_BOX_COLOR, ODF_COLOR, OLT_COLOR } from './mapStyle'

/**
 * Tetapan perilaku peta: jenis aset yang bisa ditaruh, ambang gerak-isyarat, dan
 * peta izin/endpoint per jenis simpul.
 *
 * Angka-angka di sini menentukan rasa memakai peta di jari orang (berapa lama
 * tekan-lama, seberapa jauh tangan boleh goyang), dan tabelnya menentukan siapa
 * boleh menaruh/menghapus apa. Keduanya dibaca saat menimbang perilaku — bukan
 * saat membaca alur render — jadi tempatnya di sebelah alat peta, bukan di
 * tengah komponen halaman.
 */


/** Perangkat titik yang bisa ditaruh langsung di peta (punya koordinat sendiri). */
export type AssetKind = 'SITE' | 'OLT' | 'ODF' | 'ODC' | 'ODP' | 'JOINT_BOX'

/**
 * Ambang gerak-isyarat menu "tambah di sini". 500 ms mengikuti tekan-lama bawaan
 * peramban seluler (jadi terasa sama dengan yang sudah dikenal jari operator), dan
 * 10 px memberi ruang goyang tangan tanpa menelan geseran peta yang sesungguhnya.
 */
/**
 * Radius cek kapasitas. 300 m adalah jarak yang masih masuk akal ditarik drop
 * atau dikupas dari selubung yang lewat; lebih jauh dari itu jawabannya "bisa,
 * tapi perlu tiang & kabel baru" — dan itu bukan lagi keputusan yang boleh
 * diambil sales sambil berdiri di depan rumah orang.
 */
export const SURVEY_RADIUS_M = 300

export const LONG_PRESS_MS = 500
export const HOLD_DRIFT_PX = 10

/** Jeda buang-klik sesudah menu terbuka — cukup untuk klik susulan dari jari yang sama. */
export const CLICK_SWALLOW_MS = 500

/** Perkiraan ukuran kartu menu (lihat `.map-menu`), dipakai menahannya di dalam kanvas. */
export const MENU_WIDTH_PX = 224
export const MENU_HEIGHT_PX = 248

/** Endapan ketikan pencarian: satu kueri per jeda mengetik, bukan per huruf. */
export const SEARCH_DEBOUNCE_MS = 300

export const ASSET_META: Record<AssetKind, { label: string; createPerm: string; deletePerm: string; endpoint: string }> = {
  SITE: { label: 'Site/POP', createPerm: 'network.site.create', deletePerm: 'network.site.delete', endpoint: '/api/sites' },
  OLT: { label: 'OLT', createPerm: 'network.olt.create', deletePerm: 'network.olt.delete', endpoint: '/api/olts' },
  ODF: { label: 'ODF (rak POP)', createPerm: 'network.odf.create', deletePerm: 'network.odf.delete', endpoint: '/api/odfs' },
  ODC: { label: 'ODC', createPerm: 'network.odc.create', deletePerm: 'network.odc.delete', endpoint: '/api/odcs' },
  ODP: { label: 'ODP', createPerm: 'network.odp.create', deletePerm: 'network.odp.delete', endpoint: '/api/odps' },
  JOINT_BOX: {
    label: 'Joint box',
    createPerm: 'network.jointbox.create',
    deletePerm: 'network.jointbox.delete',
    endpoint: '/api/joint-boxes',
  },
}

/** Nama jenis simpul untuk judul blade detail — "JOINT_BOX" bukan bahasa manusia. */
export const NODE_KIND_LABEL: Record<AccessNodeKind, string> = {
  odc: 'ODC',
  odp: 'ODP',
  joint_box: 'Joint box',
}

/**
 * Layer titik yang koordinatnya bisa DIGESER langsung di peta. Kunci = id layer
 * lingkaran (sekaligus source-layer MVT), nilai = endpoint pindah-lokasi + izinnya
 * + warna pin sementara (senada warna markernya di peta). Semua endpoint menerima
 * body `{ longitude, latitude }` (`PUT /api/{plural}/{id}/location`), termasuk
 * pelanggan yang kabel drop-nya ikut menempel ulang di sisi server.
 */
export const MOVABLE_NODES: Record<string, { plural: string; perm: string; label: string; color: string }> = {
  customer: { plural: 'customers', perm: 'customer.customer.update', label: 'Pelanggan', color: '#34d399' },
  odp: { plural: 'odps', perm: 'network.odp.update', label: 'ODP', color: '#fbbf24' },
  // `joint-boxes` (bertanda hubung) — bukan sekadar layer + "s": itulah bentuk jamak
  // yang dipakai controllernya, dan URL yang meleset satu huruf gagal tanpa suara.
  joint_box: { plural: 'joint-boxes', perm: 'network.jointbox.update', label: 'Joint box', color: JOINT_BOX_COLOR },
  odc: { plural: 'odcs', perm: 'network.odc.update', label: 'ODC', color: '#22d3ee' },
  odf: { plural: 'odfs', perm: 'network.odf.update', label: 'ODF', color: ODF_COLOR },
  olt: { plural: 'olts', perm: 'network.olt.update', label: 'OLT', color: OLT_COLOR },
  site: { plural: 'sites', perm: 'network.site.update', label: 'Site', color: '#b47cff' },
}
