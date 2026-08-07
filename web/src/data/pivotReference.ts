/**
 * Data referensi Pivot untuk form default sub-account (PlatformBillingSettingsPage).
 * Nilai-nilai ini HARUS sama persis dengan daftar Pivot — karena itu dijadikan dropdown,
 * bukan input bebas, supaya super-admin tak salah ketik (mis. "PT" vs "PERSEROAN TERBATAS",
 * atau MCC yang tak cocok dengan pasangan industrinya).
 *
 * Sumber: dokumentasi Pivot "Industry and Code List" + berkas District & City ID.
 * Industri & MCC ditranskrip apa adanya dari daftar Pivot (termasuk ejaan aslinya, mis.
 * "acessories"/"exchage") agar cocok saat validasi downstream.
 */

export interface PivotIndustry {
  parent: string
  child: string
  mcc: string
}

/** Tabel industri Pivot: pasangan (induk → anak → MCC). MCC ditentukan oleh anak industrinya. */
export const PIVOT_INDUSTRIES: readonly PivotIndustry[] = [
  { parent: "Airlines", child: "Airlines, Air Carriers", mcc: "4511" },
  { parent: "Automotive", child: "Car and Truck Dealers", mcc: "7699" },
  { parent: "Automotive", child: "Automotive Parts and Accessories Stores", mcc: "7699" },
  { parent: "Automotive", child: "Service Stations (with or without Ancillary Services)", mcc: "7699" },
  { parent: "Clothing, apparel & acessories", child: "Clothing stores", mcc: "7299" },
  { parent: "Clothing, apparel & acessories", child: "Pet shop", mcc: "7299" },
  { parent: "Clothing, apparel & acessories", child: "Tailors", mcc: "7299" },
  { parent: "Clothing, apparel & acessories", child: "Men’s and Women’s Clothing Stores", mcc: "7299" },
  { parent: "Digital goods", child: "Games", mcc: "5816" },
  { parent: "Digital goods", child: "Media (books, movies, artwork, images)", mcc: "5815" },
  { parent: "Digital goods", child: "Applications (excl. games)", mcc: "5817" },
  { parent: "Digital goods", child: "Utilities", mcc: "4900" },
  { parent: "Education", child: "Elementary and Secondary Schools", mcc: "8299" },
  { parent: "Education", child: "Colleges, Universities, Professional Schools", mcc: "8299" },
  { parent: "Education", child: "Correspondence Schools", mcc: "8299" },
  { parent: "Education", child: "Other educational services", mcc: "8299" },
  { parent: "Education", child: "LMS Platform", mcc: "8299" },
  { parent: "Education", child: "Edutech", mcc: "8299" },
  { parent: "Entertainment", child: "Theaters", mcc: "7999" },
  { parent: "Entertainment", child: "Tourist Attractions and Exhibits", mcc: "7999" },
  { parent: "Entertainment", child: "Public Golf Courses", mcc: "7999" },
  { parent: "Entertainment", child: "Membership Clubs (Sports, Recreation)", mcc: "7999" },
  { parent: "Entertainment", child: "Streaming services (gaming, music, TV)", mcc: "7999" },
  { parent: "Entertainment", child: "Health & beauty spas", mcc: "7999" },
  { parent: "Entertainment", child: "Other entertainment/recreation services", mcc: "7999" },
  { parent: "Financial services", child: "Banks, Credit unions", mcc: "6010" },
  { parent: "Financial services", child: "Remittance", mcc: "4829" },
  { parent: "Financial services", child: "Quasi-Cash Transactions (Gambling, Lottery)", mcc: "6051" },
  { parent: "Financial services", child: "Investments", mcc: "6211" },
  { parent: "Financial services", child: "Cryptocurrency exchage", mcc: "6051" },
  { parent: "Financial services", child: "Forex", mcc: "6051" },
  { parent: "Financial services", child: "P2P Lending", mcc: "6051" },
  { parent: "Financial services", child: "Other financial services", mcc: "6051" },
  { parent: "Financial services", child: "Aggregator/Payment Reseller", mcc: "6051" },
  { parent: "Healthcare", child: "Hospital", mcc: "8099" },
  { parent: "Healthcare", child: "Clinics (Chiropractors, Dentist, Optometrics)", mcc: "8099" },
  { parent: "Healthcare", child: "Drug stores/Pharmacy", mcc: "8099" },
  { parent: "Healthcare", child: "Laboratories", mcc: "8099" },
  { parent: "Healthcare", child: "Opticians, Optical Goods, Eyeglasses", mcc: "8099" },
  { parent: "Logistics", child: "Courier, Express, and Parcel", mcc: "4789" },
  { parent: "Logistics", child: "Third-Party Logistics (3PL) Provider", mcc: "4789" },
  { parent: "Logistics", child: "Freight Forwarding Companies & Multimodal Transport Operators (MTOs)", mcc: "4789" },
  { parent: "Logistics", child: "Cold Chain Logistics Providers", mcc: "4789" },
  { parent: "Marketplace", child: "Horizontal Marketplace", mcc: "5262" },
  { parent: "Marketplace", child: "Vertical Marketplace", mcc: "5262" },
  { parent: "Marketplace", child: "Gaming Marketplace", mcc: "5262" },
  { parent: "Organization", child: "Charitable and Social Service Organizations", mcc: "8699" },
  { parent: "Organization", child: "Political organizations", mcc: "8699" },
  { parent: "Organization", child: "Religious organizations", mcc: "8699" },
  { parent: "Organization", child: "Civic, Social, and Fraternal Associations", mcc: "8699" },
  { parent: "Outsourcing", child: "Freelance marketplace/Gig economy platform/Crowdsourcing", mcc: "7399" },
  { parent: "Outsourcing", child: "Business Process Outsourcing (BPO)", mcc: "7399" },
  { parent: "Personal services", child: "Funeral services/crematorium", mcc: "7399" },
  { parent: "Personal services", child: "Beauty & barber shops", mcc: "7399" },
  { parent: "Personal services", child: "Laundry, cleaning, garment services", mcc: "7399" },
  { parent: "Personal services", child: "Photography Studios", mcc: "7399" },
  { parent: "Personal services", child: "Wedding and Bridal Services", mcc: "7399" },
  { parent: "Personal services", child: "Counseling services", mcc: "7399" },
  { parent: "Personal services", child: "Massage parlors", mcc: "7399" },
  { parent: "Professional services", child: "Advertising Services", mcc: "8999" },
  { parent: "Professional services", child: "Commercial Photography, Art, and Graphics", mcc: "8999" },
  { parent: "Professional services", child: "Consulting, Public Relations Services", mcc: "8999" },
  { parent: "Professional services", child: "Professional Services (Not Elsewhere Classified)", mcc: "8999" },
  { parent: "Professional services", child: "Law firm", mcc: "8999" },
  { parent: "Professional services", child: "Accounting, Auditing, Book keeping", mcc: "8999" },
  { parent: "Professional services", child: "Insurance Sales, Underwriting, and Premiums", mcc: "8999" },
  { parent: "Professional services", child: "Timeshares", mcc: "8999" },
  { parent: "Professional services", child: "Tax Preparation Services", mcc: "8999" },
  { parent: "Professional services", child: "Counseling Services – Debt, Marriage, and Personal", mcc: "8999" },
  { parent: "Professional services", child: "Advertising Services", mcc: "8999" },
  { parent: "Professional services", child: "Employment Agencies and Temporary Help Services", mcc: "8999" },
  { parent: "Professional services", child: "Management, Consulting, and Public Relations Services", mcc: "8999" },
  { parent: "Professional services", child: "Detective Agencies, Protective Services, and Security Services, including Armored Cars, and Guard Dogs", mcc: "8999" },
  { parent: "Professional services", child: "Architectural, Engineering, and Surveying Services", mcc: "8999" },
  { parent: "Recreational services", child: "Fitness & Sports Club", mcc: "8999" },
  { parent: "Restaurants", child: "Restaurants and Eating Places", mcc: "5812" },
  { parent: "Restaurants", child: "Drinking Places", mcc: "5812" },
  { parent: "Restaurants", child: "Coffee shops/cafe", mcc: "5812" },
  { parent: "Restaurants", child: "Fast Food Restaurants", mcc: "5812" },
  { parent: "Retail", child: "Department Stores", mcc: "5999" },
  { parent: "Retail", child: "Grocery Stores", mcc: "5999" },
  { parent: "Retail", child: "Miscellaneous and Specialty Retail", mcc: "5999" },
  { parent: "Retail", child: "Book Stores", mcc: "5999" },
  { parent: "Retail", child: "Office Supplies", mcc: "5999" },
  { parent: "Retail", child: "Furniture, home decor & home appliances", mcc: "5999" },
  { parent: "Retail", child: "Alcohol", mcc: "5999" },
  { parent: "Retail", child: "Other retail", mcc: "5999" },
  { parent: "SaaS", child: "POS", mcc: "7399" },
  { parent: "SaaS", child: "CRM & Marketing Automation", mcc: "7399" },
  { parent: "SaaS", child: "HRIS", mcc: "7399" },
  { parent: "SaaS", child: "Invoicing platform", mcc: "7399" },
  { parent: "SaaS", child: "Enabler (website developer, ecommerce enabler)", mcc: "4816" },
  { parent: "SaaS", child: "Ecommerce enabler", mcc: "4816" },
  { parent: "Travel services", child: "Lodging - Hotels, Motels, Resorts", mcc: "7011" },
  { parent: "Travel services", child: "Online travel agent", mcc: "7011" },
]

/** Industri induk unik (urut sesuai daftar Pivot) untuk dropdown tingkat pertama. */
export const PIVOT_PARENT_INDUSTRIES: readonly string[] = [
  "Airlines",
  "Automotive",
  "Clothing, apparel & acessories",
  "Digital goods",
  "Education",
  "Entertainment",
  "Financial services",
  "Healthcare",
  "Logistics",
  "Marketplace",
  "Organization",
  "Outsourcing",
  "Personal services",
  "Professional services",
  "Recreational services",
  "Restaurants",
  "Retail",
  "SaaS",
  "Travel services",
]

/** Anak industri untuk sebuah induk (deduplikasi nama), untuk dropdown tingkat kedua. */
export function childrenOfIndustry(parent: string): PivotIndustry[] {
  const out: PivotIndustry[] = []
  const seen = new Set<string>()
  for (const i of PIVOT_INDUSTRIES) {
    if (i.parent === parent && !seen.has(i.child)) {
      seen.add(i.child)
      out.push(i)
    }
  }
  return out
}

/** MCC untuk pasangan induk+anak (auto-isi saat anak dipilih); undefined bila tak dikenal. */
export function mccForIndustry(parent: string, child: string): string | undefined {
  return PIVOT_INDUSTRIES.find((i) => i.parent === parent && i.child === child)?.mcc
}

/**
 * Struktur bisnis (badan usaha) yang dikirim ke Pivot. Dokumen Pivot tak menerbitkan daftar
 * enum lengkap; contoh resmi memakai bentuk panjang "PERSEROAN TERBATAS" (bukan "PT"), jadi
 * daftar ini memakai bentuk panjang uppercase. "PERSEROAN TERBATAS" = pilihan paling umum (PT).
 */
export const PIVOT_BUSINESS_STRUCTURES: readonly string[] = [
  'PERSEROAN TERBATAS',
  'PERSEROAN PERORANGAN',
  'PERSEKUTUAN KOMANDITER',
  'FIRMA',
  'PERSEKUTUAN PERDATA',
  'KOPERASI',
  'YAYASAN',
  'PERKUMPULAN',
  'PERUSAHAAN UMUM',
  'PERUSAHAAN PERSEROAN',
  'USAHA DAGANG',
  'PERORANGAN',
]

/** Negara (ISO 3166-1 alpha-2) untuk businessCountry/countryOfEntity — ID default untuk pasar Indonesia. */
export const PIVOT_COUNTRIES: ReadonlyArray<{ code: string; name: string }> = [
  { code: 'ID', name: 'Indonesia' },
  { code: 'SG', name: 'Singapura' },
  { code: 'MY', name: 'Malaysia' },
  { code: 'TH', name: 'Thailand' },
  { code: 'PH', name: 'Filipina' },
  { code: 'VN', name: 'Vietnam' },
  { code: 'BN', name: 'Brunei Darussalam' },
  { code: 'KH', name: 'Kamboja' },
  { code: 'LA', name: 'Laos' },
  { code: 'MM', name: 'Myanmar' },
  { code: 'TL', name: 'Timor Leste' },
  { code: 'AU', name: 'Australia' },
  { code: 'CN', name: 'Tiongkok' },
  { code: 'HK', name: 'Hong Kong' },
  { code: 'IN', name: 'India' },
  { code: 'JP', name: 'Jepang' },
  { code: 'KR', name: 'Korea Selatan' },
  { code: 'TW', name: 'Taiwan' },
  { code: 'AE', name: 'Uni Emirat Arab' },
  { code: 'SA', name: 'Arab Saudi' },
  { code: 'GB', name: 'Inggris' },
  { code: 'US', name: 'Amerika Serikat' },
  { code: 'NL', name: 'Belanda' },
  { code: 'DE', name: 'Jerman' },
]

/** Tipe channel tujuan payout Pivot: bank, e-wallet, atau virtual account. */
export type PivotChannelType = 'BANK' | 'EWALLET' | 'VA'

export const PIVOT_CHANNEL_TYPE_LABEL: Record<PivotChannelType, string> = {
  BANK: 'Bank',
  EWALLET: 'E-Wallet',
  VA: 'Virtual Account',
}

export interface PivotChannel {
  /** Kode channel Pivot (mis. `MANDIRI`) — nilai yang dikirim sebagai `channelCode`. */
  code: string
  name: string
  type: PivotChannelType
}

/**
 * Daftar mentah channel payout Pivot (Bank + E-Wallet + Virtual Account), transkrip dari dokumen
 * "Channel Codes" (`api-lists/payout-local/channel-codes`). Beberapa kode muncul ganda: lintas-tipe
 * (mis. `PERMATA`/`DANAMON`/`BNC` ada di Bank & Virtual Account) atau di dalam Bank sendiri
 * (mis. `KALIMANTAN_TIMUR`). Dedup dilakukan di [PIVOT_CHANNEL_CODES] — kemunculan pertama menang.
 */
const RAW_CHANNELS: readonly PivotChannel[] = [
  { code: 'BRI', name: 'Bank Rakyat Indonesia', type: 'BANK' },
  { code: 'EXIMBANK', name: 'Bank Indonesia Eximbank', type: 'BANK' },
  { code: 'MANDIRI', name: 'Bank Mandiri', type: 'BANK' },
  { code: 'BNI', name: 'Bank Negara Indonesia', type: 'BANK' },
  { code: 'DANAMON', name: 'Bank Danamon Indonesia', type: 'BANK' },
  { code: 'DANAMON_UUS', name: 'Bank Danamon UUS', type: 'BANK' },
  { code: 'PERMATA', name: 'Bank Permata', type: 'BANK' },
  { code: 'PERMATA_UUS', name: 'Bank Permata UUS', type: 'BANK' },
  { code: 'BCA', name: 'Bank Central Asia (BCA)', type: 'BANK' },
  { code: 'MAYBANK', name: 'Bank Maybank Indonesia', type: 'BANK' },
  { code: 'PANIN', name: 'Bank Panin Indonesia', type: 'BANK' },
  { code: 'ARTA_NIAGA_KENCANA', name: 'Bank Arta Niaga Kencana', type: 'BANK' },
  { code: 'CIMB', name: 'Bank CIMB Niaga', type: 'BANK' },
  { code: 'CIMB_SYR', name: 'Bank CIMB Niaga Syariah', type: 'BANK' },
  { code: 'CIMB_UUS', name: 'Bank CIMB Niaga UUS', type: 'BANK' },
  { code: 'UOB', name: 'Bank UOB Indonesia', type: 'BANK' },
  { code: 'TMRW', name: 'TMRW by UOB Indonesia', type: 'BANK' },
  { code: 'LIPPO', name: 'Bank Lippo', type: 'BANK' },
  { code: 'OCBC', name: 'Bank OCBC NISP', type: 'BANK' },
  { code: 'OCBC_UUS', name: 'Bank OCBC NISP UUS', type: 'BANK' },
  { code: 'DANAGUNG_ABADI', name: 'BPR Danagung Abadi', type: 'BANK' },
  { code: 'DANAGUNG_BAKTI', name: 'BPR Danagung Bakti', type: 'BANK' },
  { code: 'DANAGUNG_RAMULTI', name: 'BPR Danagung Ramulti', type: 'BANK' },
  { code: 'AMEX', name: 'American Express Bank Ltd', type: 'BANK' },
  { code: 'CITIBANK', name: 'Bank Citibank', type: 'BANK' },
  { code: 'JPMORGAN', name: 'JP Morgan Chase Bank', type: 'BANK' },
  { code: 'BAML', name: 'Bank of America Merill-Lynch', type: 'BANK' },
  { code: 'ING', name: 'Bank ING Indonesia', type: 'BANK' },
  { code: 'BCC', name: 'Bank China Construction', type: 'BANK' },
  { code: 'MULTICOR', name: 'Bank Multicor', type: 'BANK' },
  { code: 'ARTHA', name: 'Bank Artha Graha International', type: 'BANK' },
  { code: 'CREDIT_AGRICOLE', name: 'Bank Credit Agricole Indosuez', type: 'BANK' },
  { code: 'BANGKOK_BANK', name: 'The Bangkok Bank Company', type: 'BANK' },
  { code: 'MUFG', name: 'Bank MUFG (Mitsubishi UFJ)', type: 'BANK' },
  { code: 'SUMITOMO', name: 'Bank Sumitomo Mitsui Indonesia', type: 'BANK' },
  { code: 'DBS', name: 'Bank DBS Indonesia', type: 'BANK' },
  { code: 'DIGIBANK', name: 'Digibank', type: 'BANK' },
  { code: 'RESONA', name: 'Bank Resona Perdania', type: 'BANK' },
  { code: 'MIZUHO', name: 'Bank Mizuho Indonesia', type: 'BANK' },
  { code: 'STANDARD_CHARTERED', name: 'Bank Standard Chartered', type: 'BANK' },
  { code: 'ABN_AMRO', name: 'Bank ABN AMRO', type: 'BANK' },
  { code: 'KEPPEL', name: 'Bank Keppel Tatlee Buana', type: 'BANK' },
  { code: 'CAPITAL', name: 'Bank Capital Indonesia', type: 'BANK' },
  { code: 'BNP_PARIBAS', name: 'Bank BNP Paribas Indonesia', type: 'BANK' },
  { code: 'KEB_INDONESIA', name: 'Korean Exchange Bank Danamon', type: 'BANK' },
  { code: 'RABOBANK', name: 'Bank Rabobank International Indonesia', type: 'BANK' },
  { code: 'ANZ', name: 'Bank ANZ Indonesia', type: 'BANK' },
  { code: 'DEUTSCHE', name: 'Deutsche Bank', type: 'BANK' },
  { code: 'WOORI', name: 'Bank Woori Indonesia', type: 'BANK' },
  { code: 'BOC', name: 'Bank of China', type: 'BANK' },
  { code: 'BUMI_ARTA', name: 'Bank Bumi Arta', type: 'BANK' },
  { code: 'HSBC', name: 'Bank HSBC', type: 'BANK' },
  { code: 'HSBC_UUS', name: 'Bank HSBC UUS', type: 'BANK' },
  { code: 'ANTARDAERAH', name: 'Bank Antardaerah', type: 'BANK' },
  { code: 'HAGA', name: 'Bank Haga', type: 'BANK' },
  { code: 'IFI', name: 'Bank IFI', type: 'BANK' },
  { code: 'JTRUST', name: 'Bank J Trust Indonesia', type: 'BANK' },
  { code: 'MAYAPADA', name: 'Bank Mayapada', type: 'BANK' },
  { code: 'BJB', name: 'Bank Jabar dan Banten (BJB)', type: 'BANK' },
  { code: 'DKI', name: 'Bank DKI', type: 'BANK' },
  { code: 'DKI_UUS', name: 'Bank DKI UUS', type: 'BANK' },
  { code: 'DAERAH_ISTIMEWA', name: 'BPD DI Yogyakarta (DIY)', type: 'BANK' },
  { code: 'DAERAH_ISTIMEWA_UUS', name: 'BPD DI Yogyakarta (DIY) UUS', type: 'BANK' },
  { code: 'JAWA_TENGAH', name: 'BPD Jawa Tengah', type: 'BANK' },
  { code: 'JAWA_TENGAH_UUS', name: 'BPD Jawa Tengah UUS', type: 'BANK' },
  { code: 'JAWA_TIMUR', name: 'BPD Jawa Timur', type: 'BANK' },
  { code: 'JAWA_TIMUR_UUS', name: 'BPD Jawa Timur UUS', type: 'BANK' },
  { code: 'JAMBI', name: 'BPD Jambi', type: 'BANK' },
  { code: 'JAMBI_UUS', name: 'BPD Jambi UUS', type: 'BANK' },
  { code: 'ACEH', name: 'BPD Aceh', type: 'BANK' },
  { code: 'ACEH_UUS', name: 'BPD Aceh UUS', type: 'BANK' },
  { code: 'ACEH_SYR', name: 'BPD Aceh Syariah', type: 'BANK' },
  { code: 'SUMUT', name: 'BPD Sumatera Utara (Sumut)', type: 'BANK' },
  { code: 'SUMUT_UUS', name: 'BPD Sumut UUS', type: 'BANK' },
  { code: 'SUMATERA_BARAT', name: 'BPD Sumatera Barat (Sumbar)', type: 'BANK' },
  { code: 'SUMATERA_BARAT_UUS', name: 'BPD Sumbar UUS', type: 'BANK' },
  { code: 'KALIMANTAN_TIMUR', name: 'BPD Kalimantan Timur', type: 'BANK' },
  { code: 'KALIMANTAN_TIMUR_UUS', name: 'BPD Kalimantan Timur UUS', type: 'BANK' },
  { code: 'RIAU_DAN_KEPRI', name: 'BPD Riau dan Kepri', type: 'BANK' },
  { code: 'RIAU_DAN_KEPRI_SYR', name: 'BPD Riau Kepri Syariah', type: 'BANK' },
  { code: 'SUMSEL_DAN_BABEL', name: 'BPD Sumsel dan Babel', type: 'BANK' },
  { code: 'SUMSEL_DAN_BABEL_UUS', name: 'BPD Sumsel dan Babel UUS', type: 'BANK' },
  { code: 'LAMPUNG', name: 'BPD Lampung', type: 'BANK' },
  { code: 'KALIMANTAN_SELATAN', name: 'BPD Kalimantan Selatan', type: 'BANK' },
  { code: 'KALIMANTAN_SELATAN_UUS', name: 'BPD Kalimantan Selatan UUS', type: 'BANK' },
  { code: 'KALIMANTAN_BARAT', name: 'BPD Kalimantan Barat', type: 'BANK' },
  { code: 'KALIMANTAN_BARAT_UUS', name: 'BPD Kalimantan Barat UUS', type: 'BANK' },
  { code: 'KALIMANTAN_TENGAH', name: 'BPD Kalimantan Tengah', type: 'BANK' },
  { code: 'SULSELBAR', name: 'BPD Sulawesi Selatan dan Barat (Sulselbar)', type: 'BANK' },
  { code: 'SULSELBAR_UUS', name: 'BPD Sulselbar UUS', type: 'BANK' },
  { code: 'SULUTGO', name: 'BPD Sulawesi Utara dan Gorontalo (Sulutgo)', type: 'BANK' },
  { code: 'NUSA_TENGGARA_BARAT', name: 'BPD Nusa Tenggara Barat', type: 'BANK' },
  { code: 'NTB_SYR', name: 'Bank NTB Syariah', type: 'BANK' },
  { code: 'BALI', name: 'BPD Bali', type: 'BANK' },
  { code: 'NUSA_TENGGARA_TIMUR', name: 'BPD Nusa Tenggara Timur', type: 'BANK' },
  { code: 'MALUKU', name: 'BPD Maluku dan Maluku Utara', type: 'BANK' },
  { code: 'PAPUA', name: 'BPD Papua', type: 'BANK' },
  { code: 'BENGKULU', name: 'BPD Bengkulu', type: 'BANK' },
  { code: 'SULAWESI', name: 'BPD Sulawesi Tengah', type: 'BANK' },
  { code: 'SULAWESI_TENGGARA', name: 'BPD Sulawesi Tenggara', type: 'BANK' },
  { code: 'BANTEN', name: 'BPD Banten', type: 'BANK' },
  { code: 'NUSANTARA_PARAHYANGAN', name: 'Bank Nusantara Parahyangan', type: 'BANK' },
  { code: 'INDIA', name: 'Bank of India Indonesia', type: 'BANK' },
  { code: 'MUAMALAT', name: 'Bank Muamalat', type: 'BANK' },
  { code: 'MESTIKA_DHARMA', name: 'Bank Mestika Dharma', type: 'BANK' },
  { code: 'SHINHAN', name: 'Bank Shinhan', type: 'BANK' },
  { code: 'SINARMAS', name: 'Bank Sinarmas', type: 'BANK' },
  { code: 'SINARMAS_UUS', name: 'Bank Sinarmas UUS', type: 'BANK' },
  { code: 'MASPION', name: 'Bank Maspion Indonesia', type: 'BANK' },
  { code: 'HAGAKITA', name: 'Bank Hagakita', type: 'BANK' },
  { code: 'GANESHA', name: 'Bank Ganesha', type: 'BANK' },
  { code: 'WINDU_KENTJANA', name: 'Bank Windu Kentjana', type: 'BANK' },
  { code: 'ICBC', name: 'Bank ICBC Indonesia', type: 'BANK' },
  { code: 'HARMONI', name: 'Bank Harmoni International', type: 'BANK' },
  { code: 'QNB_INDONESIA', name: 'Bank QNB Indonesia', type: 'BANK' },
  { code: 'BTN', name: 'Bank Tabungan Negara (BTN)', type: 'BANK' },
  { code: 'BTN_UUS', name: 'Bank Syariah Nasional (BTN Syariah)', type: 'BANK' },
  { code: 'HIMPUNAN_SAUDARA', name: 'Bank Himpunan Saudara 1906', type: 'BANK' },
  { code: 'WOORI_SAUDARA', name: 'Bank Woori Saudara', type: 'BANK' },
  { code: 'SMBC_INDONESIA', name: 'Bank SMBC Indonesia', type: 'BANK' },
  { code: 'JENIUS', name: 'Jenius', type: 'BANK' },
  { code: 'TABUNGAN_PENSIUNAN_NASIONAL', name: 'Bank BTPN', type: 'BANK' },
  { code: 'KOP_INTIDANA', name: 'Kop Intidana', type: 'BANK' },
  { code: 'SWAGUNA', name: 'Bank Swaguna', type: 'BANK' },
  { code: 'VICTORIA_SYR', name: 'Bank Victoria Syariah', type: 'BANK' },
  { code: 'BRI_SYR', name: 'Bank Syariah Indonesia (BRI Syariah)', type: 'BANK' },
  { code: 'BJB_SYR', name: 'Bank BJB Syariah', type: 'BANK' },
  { code: 'MEGA', name: 'Bank Mega', type: 'BANK' },
  { code: 'BNI_SYR', name: 'Bank Syariah Indonesia (BNI Syariah)', type: 'BANK' },
  { code: 'KBID', name: 'Bank KB Bukopin', type: 'BANK' },
  { code: 'BSI', name: 'Bank Syariah Indonesia', type: 'BANK' },
  { code: 'KROM', name: 'Bank Krom', type: 'BANK' },
  { code: 'ANDARA', name: 'Bank Andara', type: 'BANK' },
  { code: 'SAQU', name: 'Bank Saqu Indonesia', type: 'BANK' },
  { code: 'HANA', name: 'Bank KEB Hana', type: 'BANK' },
  { code: 'MNC_INTERNASIONAL', name: 'Bank MNC International', type: 'BANK' },
  { code: 'BNC', name: 'Bank Neo Commerce', type: 'BANK' },
  { code: 'MITRANIAGA', name: 'Bank Mitraniaga', type: 'BANK' },
  { code: 'AGRONIAGA', name: 'Bank Raya', type: 'BANK' },
  { code: 'SBI_INDONESIA', name: 'Bank SBI Indonesia', type: 'BANK' },
  { code: 'BCA_DIGITAL', name: 'Bank BCA Digital (blu)', type: 'BANK' },
  { code: 'NATIONALNOBU', name: 'Bank National Nobu', type: 'BANK' },
  { code: 'MEGA_SYR', name: 'Bank Mega Syariah', type: 'BANK' },
  { code: 'INA_PERDANA', name: 'Bank Ina Perdana', type: 'BANK' },
  { code: 'PANIN_SYR', name: 'Bank Panin Dubai Syariah', type: 'BANK' },
  { code: 'PRIMA_MASTER', name: 'Bank Prima Master', type: 'BANK' },
  { code: 'BUKOPIN_SYR', name: 'Bank KB Indonesia', type: 'BANK' },
  { code: 'SAHABAT_SAMPOERNA', name: 'Bank Sahabat Sampoerna', type: 'BANK' },
  { code: 'BARCLAYS', name: 'Bank Barclays', type: 'BANK' },
  { code: 'DINAR_INDONESIA', name: 'Bank Dinar Indonesia', type: 'BANK' },
  { code: 'OKE', name: 'Bank Oke', type: 'BANK' },
  { code: 'ANGLOMAS', name: 'Anglomas International Bank', type: 'BANK' },
  { code: 'AMAR', name: 'Bank Amar Indonesia', type: 'BANK' },
  { code: 'SEABANK', name: 'SeaBank', type: 'BANK' },
  { code: 'BCA_SYR', name: 'Bank BCA Syariah', type: 'BANK' },
  { code: 'JAGO', name: 'Bank Jago', type: 'BANK' },
  { code: 'BTPN_SYARIAH', name: 'Bank BTPN Syariah', type: 'BANK' },
  { code: 'MULTI_ARTA_SENTOSA', name: 'Bank Multi Arta Sentosa', type: 'BANK' },
  { code: 'MAS', name: 'Bank Multiarta Sentosa', type: 'BANK' },
  { code: 'HIBANK', name: 'Bank Hibank Indonesia', type: 'BANK' },
  { code: 'INDEX_SELINDO', name: 'Bank Index Selindo', type: 'BANK' },
  { code: 'PUNDI', name: 'Bank Pundi', type: 'BANK' },
  { code: 'CNB', name: 'Centratama Nasional Bank', type: 'BANK' },
  { code: 'SUPERBANK', name: 'Super Bank Indonesia', type: 'BANK' },
  { code: 'MANDIRI_TASPEN', name: 'Bank Mandiri Taspen Pos', type: 'BANK' },
  { code: 'VICTORIA_INTERNASIONAL', name: 'Bank Victoria Internasional', type: 'BANK' },
  { code: 'HARDA_INTERNASIONAL', name: 'Allo Bank Indonesia', type: 'BANK' },
  { code: 'SUPRA', name: 'BPR Supra Artapersada', type: 'BANK' },
  { code: 'MANDIRI_BPR', name: 'Mandiri - BPR', type: 'BANK' },
  { code: 'KS', name: 'BPR KS (Karyajatnika Sedaya)', type: 'BANK' },
  { code: 'EKA', name: 'Bank Eka Bumi Artha', type: 'BANK' },
  { code: 'IBK', name: 'Bank IBK Indonesia', type: 'BANK' },
  { code: 'AGRIS', name: 'Bank Agris', type: 'BANK' },
  { code: 'MERINCORP', name: 'Bank Merincorp', type: 'BANK' },
  { code: 'ALADIN', name: 'Bank Aladin Syariah', type: 'BANK' },
  { code: 'OCBC_INDONESIA', name: 'Bank OCBC Indonesia', type: 'BANK' },
  { code: 'CTBC', name: 'Bank CTBC Indonesia', type: 'BANK' },
  { code: 'COMMONWEALTH', name: 'Bank Commonwealth', type: 'BANK' },
  { code: 'GOPAY', name: 'GoPay', type: 'EWALLET' },
  { code: 'OVO', name: 'OVO', type: 'EWALLET' },
  { code: 'SHOPEEPAY', name: 'ShopeePay', type: 'EWALLET' },
  { code: 'DANA', name: 'DANA', type: 'EWALLET' },
  { code: 'LINKAJA', name: 'LinkAja', type: 'EWALLET' },
  // Virtual Account: kodenya identik dengan bank di atas → tersapu dedup (kemunculan pertama menang).
  { code: 'BNC', name: 'Bank Neo Commerce', type: 'VA' },
  { code: 'PERMATA', name: 'Bank Permata', type: 'VA' },
  { code: 'DANAMON', name: 'Bank Danamon Indonesia', type: 'VA' },
]

/** Channel payout unik (dedup per `code`, kemunculan pertama menang) untuk dropdown. */
export const PIVOT_CHANNEL_CODES: readonly PivotChannel[] = (() => {
  const seen = new Set<string>()
  const out: PivotChannel[] = []
  for (const c of RAW_CHANNELS) {
    if (seen.has(c.code)) continue
    seen.add(c.code)
    out.push(c)
  }
  return out
})()

const CHANNEL_TYPE_ORDER: Record<PivotChannelType, number> = { BANK: 0, EWALLET: 1, VA: 2 }

/**
 * Cari channel payout untuk combobox (filter lokal). Cocokkan `code`/`name` (uppercase `includes`);
 * hasil diurut per grup tipe (Bank → E-Wallet → Virtual Account) lalu nama, dibatasi `limit` agar
 * dropdown tak sepanjang ratusan bank. Term kosong → `limit` pertama.
 */
export function searchChannelCodes(term: string, limit = 50): PivotChannel[] {
  const q = term.trim().toUpperCase()
  const matched = q
    ? PIVOT_CHANNEL_CODES.filter((c) => c.code.includes(q) || c.name.toUpperCase().includes(q))
    : [...PIVOT_CHANNEL_CODES]
  matched.sort(
    (a, b) => CHANNEL_TYPE_ORDER[a.type] - CHANNEL_TYPE_ORDER[b.type] || a.name.localeCompare(b.name),
  )
  return matched.slice(0, limit)
}

/** Nama channel dari kode (untuk seed label saat nilai sudah terisi). */
export function channelNameByCode(code: string): string | undefined {
  return PIVOT_CHANNEL_CODES.find((c) => c.code === code)?.name
}

export interface PivotDistrict {
  id: number
  name: string
}

// Daftar district ~7.200 baris (~150 KB) di-*dynamic import* & di-cache: hanya dimuat saat
// pemilih district dibuka, jadi tak membebani bundel awal.
let districtCache: PivotDistrict[] | null = null

async function loadDistricts(): Promise<PivotDistrict[]> {
  if (districtCache) return districtCache
  const mod = await import('./pivotDistricts')
  districtCache = mod.PIVOT_DISTRICTS.map(([id, name]) => ({ id, name }))
  return districtCache
}

/** Cari district untuk combobox (filter lokal). Cocokkan nama (uppercase) atau id persis; batasi hasil. */
export async function searchDistricts(term: string, limit = 50): Promise<PivotDistrict[]> {
  const all = await loadDistricts()
  const q = term.trim().toUpperCase()
  if (!q) return all.slice(0, limit)
  const out: PivotDistrict[] = []
  for (const d of all) {
    if (d.name.includes(q) || String(d.id) === q) {
      out.push(d)
      if (out.length >= limit) break
    }
  }
  return out
}

/** Nama district dari id (untuk menampilkan label saat nilai sudah terisi). */
export async function districtNameById(id: number): Promise<string | undefined> {
  const all = await loadDistricts()
  return all.find((d) => d.id === id)?.name
}
