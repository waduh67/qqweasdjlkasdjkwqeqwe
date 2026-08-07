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
