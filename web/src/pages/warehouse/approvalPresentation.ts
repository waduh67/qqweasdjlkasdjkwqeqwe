import type { ApprovalDetails, ApprovalDocument, POLICY_OPERATIONS } from '@/api/warehouse/approvalReads'

export const approvalOperationLabels: Record<typeof POLICY_OPERATIONS[number], string> = { RECEIPT: 'Penerimaan', ISSUE: 'Pengeluaran', ISSUE_EXCEPTION: 'Pengecualian pengeluaran', OPENING_BALANCE: 'Saldo awal', ADJUSTMENT: 'Selisih transfer', LOSS: 'Kehilangan', SCRAP: 'Penghapusan barang', COUNT_VARIANCE: 'Selisih stock opname', TITLE_REACQUISITION: 'Perubahan kepemilikan' }
export const approvalKindLabels: Record<ApprovalDocument['kind'], string> = { RECEIPT: 'Penerimaan', ADJUSTMENT: 'Selisih transfer', COUNT: 'Stock opname', TITLE_CORRECTION: 'Koreksi kepemilikan', RETURN_TITLE: 'Perolehan kembali dari pelanggan', LOSS: 'Kehilangan', SCRAP: 'Penghapusan barang', DISPOSITION_REVERSAL: 'Pembalikan disposisi', ASSET_LOSS: 'Kehilangan perangkat pelanggan' }
export const approvalPersonLabel = (person: { id: string; name: string | null }) => person.name ?? `Petugas ${person.id}`
export const approvalLineLabel = (line: ApprovalDocument['lines'][number]) => `${line.name} · ${line.code}${line.serial ? ` · ${line.serial}` : line.lotCode ? ` · ${line.lotCode}` : ''}`
export function approvalImpact(kind: ApprovalDocument['kind']) {
  switch (kind) {
    case 'RECEIPT': return 'Persetujuan akhir menerima barang ke pemeriksaan. Barang belum menjadi stok tersedia sampai pemeriksaan dan penempatan selesai.'
    case 'ADJUSTMENT': return 'Persetujuan akhir menyelesaikan sisa transfer ke tujuan penanganan yang tercatat. Jumlah yang benar-benar diterima tetap terpisah.'
    case 'COUNT': return 'Persetujuan akhir membukukan selisih dari penghitungan yang diajukan. Perubahan stok selama pemeriksaan dapat mewajibkan hitung ulang.'
    case 'TITLE_CORRECTION': return 'Persetujuan akhir mengubah kepemilikan sesuai bukti sumber. Riwayat perangkat dan penempatan tetap tercatat.'
    case 'RETURN_TITLE': return 'Persetujuan akhir memindahkan hak milik perangkat retur ke ISP. Barang tetap karantina sampai pemeriksaan dan penghapusan data selesai.'
    case 'LOSS': case 'ASSET_LOSS': return 'Persetujuan akhir mencatat kehilangan dari sumber yang terikat. Untuk perangkat terpasang, penempatan ditutup sesuai alur kehilangan perangkat.'
    case 'SCRAP': return 'Persetujuan akhir membukukan penghapusan barang dari sumber yang tercatat.'
    case 'DISPOSITION_REVERSAL': return 'Persetujuan akhir membalik disposisi yang dirujuk dan mengembalikan barang ke karantina untuk diperiksa.'
  }
}
export const approvalBlockLabels: Record<string, string> = {
  REQUESTER_REQUIRED: 'Pengajuan hanya dapat dilakukan pembuat dokumen sumber.', REQUEST_PERMISSION_REQUIRED: 'Izin ajukan persetujuan diperlukan.',
  REQUEST_ALREADY_EXISTS: 'Revisi sumber ini sudah pernah diajukan. Buka permintaan tersimpan di bawah.', SOURCE_NOT_READY: 'Selesaikan dokumen sumber sebelum mengajukan persetujuan baru.',
  CUTOVER_REQUIRED: 'Aktifkan gudang sebelum mengajukan persetujuan.', CUTOVER_CHANGED: 'Status aktivasi gudang berubah. Muat ulang dan periksa setelan gudang.',
  REQUEST_TERMINAL: 'Permintaan ini sudah memiliki hasil akhir.', SOURCE_CHANGED: 'Dokumen sumber berubah. Muat ulang dan periksa dokumen sumber sebelum melanjutkan.',
  INDEPENDENT_APPROVER_REQUIRED: 'Pembuat atau pihak yang terlibat tidak dapat menyetujui permintaan ini. Dibutuhkan pemeriksa independen.',
  NOT_CURRENT_APPROVER: 'Anda belum memenuhi izin, cakupan, atau penugasan pemeriksa untuk tahap ini.', NO_REMAINING_TIER: 'Tidak ada tahap keputusan yang tersisa.',
}
export function approvalCostLabel(cost: NonNullable<ApprovalDetails['cost']>) {
  const numerator = BigInt(cost.numerator), denominator = BigInt(cost.denominator)
  return `${numerator % denominator === 0n ? numerator / denominator : `${numerator}/${denominator}`} ${cost.currency} (satuan minor)`
}
