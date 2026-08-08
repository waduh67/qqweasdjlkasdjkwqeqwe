import {
  createLightTheme,
  createDarkTheme,
  type BrandVariants,
  type Theme,
} from '@fluentui/react-components'

/**
 * Brand ramp Azure — dipusatkan ke Azure Blue `#0078D4` (shade 80). Nilai gelap
 * (10–70) dan terang (90–160) diturunkan dengan pencampuran hitam/putih bertahap
 * agar monoton dan konsisten dengan bahasa visual Microsoft Fluent / Azure Portal.
 */
const azureBrand: BrandVariants = {
  10: '#000e19',
  20: '#001f37',
  30: '#003055',
  40: '#003e6e',
  50: '#004d88',
  60: '#005ba1',
  70: '#006abb',
  80: '#0078d4', // Azure Blue — aksen utama
  90: '#1f8dd9',
  100: '#429ddf',
  110: '#66aee5',
  120: '#8abeeb',
  130: '#a8cbf0',
  140: '#c7d9f5',
  150: '#e0effa',
  160: '#f2f8fd',
}

/**
 * Penyetelan yang berlaku di kedua mode: sudut membulat kecil (Azure Portal pakai
 * radius ~2px, bukan 4px default Fluent Teams) dan tumpukan font Segoe yang sama
 * dengan token index.css. Dengan ini komponen Fluent (Button/Tab/Input/Toolbar)
 * langsung berpenampilan Azure lewat TEMA — bukan lagi CSS per-elemen.
 */
const azureShared: Partial<Theme> = {
  borderRadiusSmall: '2px',
  borderRadiusMedium: '2px',
  borderRadiusLarge: '4px',
  borderRadiusXLarge: '6px',
  fontFamilyBase:
    "'Segoe UI Variable Text', 'Segoe UI Variable', 'Segoe UI', 'Segoe UI Web (West European)', -apple-system, BlinkMacSystemFont, system-ui, 'Helvetica Neue', Arial, sans-serif",
}

/** Tema terang Azure — dasar konsol operator/platform. */
export const azureLight: Theme = {
  ...createLightTheme(azureBrand),
  ...azureShared,
}

/** Tema gelap Azure — selaras `data-theme="dark"` yang sudah ada. */
export const azureDark: Theme = {
  ...createDarkTheme(azureBrand),
  ...azureShared,
}

// Azure Portal memakai neutral yang sedikit lebih sejuk untuk latar kanvas.
azureLight.colorNeutralBackground2 = '#f8f9fa'
azureLight.colorNeutralBackground3 = '#f3f4f6'
