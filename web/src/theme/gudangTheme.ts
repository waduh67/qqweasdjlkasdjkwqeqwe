import { createDarkTheme, createLightTheme, type BrandVariants, type Theme } from '@fluentui/react-components'

const brand: BrandVariants = {
  10: '#00170f', 20: '#002d1e', 30: '#00432d', 40: '#00593c',
  50: '#006344', 60: '#007d58', 70: '#009b6b', 80: '#00b77f',
  90: '#00c88b', 100: '#00e29d', 110: '#36eab3', 120: '#6bf1c5',
  130: '#9af5d8', 140: '#b3f5dd', 150: '#d8faed', 160: '#effcf7',
}
const shared: Partial<Theme> = {
  fontFamilyBase: "'Hanken Grotesk', system-ui, sans-serif",
  fontFamilyNumeric: "'Hanken Grotesk', system-ui, sans-serif",
  fontSizeBase300: '14px',
  borderRadiusSmall: '8px', borderRadiusMedium: '14px',
  borderRadiusLarge: '16px', borderRadiusXLarge: '24px',
}

export const gudangLight: Theme = {
  ...createLightTheme(brand), ...shared,
  colorNeutralBackground1: '#ffffff', colorNeutralBackground1Hover: '#f2f4f6',
  colorNeutralBackground1Pressed: '#eceef0', colorNeutralBackground1Selected: '#e1f5ed',
  colorNeutralBackground2: '#f8f9fb', colorNeutralBackground2Hover: '#f2f4f6',
  colorNeutralBackground2Pressed: '#eceef0', colorNeutralBackground2Selected: '#e1f5ed',
  colorNeutralBackground3: '#f2f4f6', colorNeutralBackground4: '#eceef0',
  colorNeutralBackground5: '#e2e8ed', colorNeutralBackground6: '#bac4d3',
  colorNeutralForeground1: '#161c28', colorNeutralForeground1Hover: '#161c28',
  colorNeutralForeground1Pressed: '#161c28', colorNeutralForeground1Selected: '#161c28',
  colorNeutralForeground2: '#414b5c', colorNeutralForeground2Hover: '#161c28',
  colorNeutralForeground2Pressed: '#161c28', colorNeutralForeground2Selected: '#161c28',
  colorNeutralForeground3: '#626d7b', colorNeutralForeground4: '#626d7b',
  colorNeutralStroke1: '#bac4d3', colorNeutralStroke1Hover: '#626d7b',
  colorNeutralStroke1Pressed: '#414b5c', colorNeutralStroke2: '#e2e8ed',
  colorNeutralStrokeAccessible: '#626d7b',
  colorStrokeFocus2: '#007d58',
  colorBrandBackground: '#161c28', colorBrandBackgroundHover: '#293344',
  colorBrandBackgroundPressed: '#080e1a', colorBrandBackgroundSelected: '#293344',
  colorNeutralForegroundOnBrand: '#ffffff',
  colorBrandForeground1: '#007d58', colorBrandForeground2: '#007d58',
  colorBrandForegroundLink: '#007d58', colorBrandForegroundLinkHover: '#006344',
  colorBrandForegroundLinkPressed: '#006344',
  colorBrandBackground2: '#e1f5ed', colorBrandStroke1: '#007d58',
  colorCompoundBrandForeground1: '#007d58', colorCompoundBrandForeground1Hover: '#006344',
  colorCompoundBrandForeground1Pressed: '#006344',
  colorCompoundBrandStroke: '#007d58', colorCompoundBrandStrokeHover: '#006344',
  colorCompoundBrandStrokePressed: '#006344', colorCompoundBrandBackground: '#007d58',
  colorCompoundBrandBackgroundHover: '#006344', colorCompoundBrandBackgroundPressed: '#00432d',
}

export const gudangDark: Theme = {
  ...createDarkTheme(brand), ...shared,
  colorNeutralBackground1: '#121824', colorNeutralBackground1Hover: '#161c28',
  colorNeutralBackground1Pressed: '#242f44', colorNeutralBackground1Selected: '#183f33',
  colorNeutralBackground2: '#080e1a', colorNeutralBackground2Hover: '#161c28',
  colorNeutralBackground2Pressed: '#242f44', colorNeutralBackground2Selected: '#183f33',
  colorNeutralBackground3: '#161c28', colorNeutralBackground4: '#242f44',
  colorNeutralBackground5: '#222c3d', colorNeutralBackground6: '#626d7b',
  colorNeutralForeground1: '#f8f9fb', colorNeutralForeground1Hover: '#f8f9fb',
  colorNeutralForeground1Pressed: '#f8f9fb', colorNeutralForeground1Selected: '#f8f9fb',
  colorNeutralForeground2: '#c4ccd8', colorNeutralForeground2Hover: '#f8f9fb',
  colorNeutralForeground2Pressed: '#f8f9fb', colorNeutralForeground2Selected: '#f8f9fb',
  colorNeutralForeground3: '#a5afbd', colorNeutralForeground4: '#a5afbd',
  colorNeutralStroke1: '#626d7b', colorNeutralStroke1Hover: '#a5afbd',
  colorNeutralStroke1Pressed: '#c4ccd8', colorNeutralStroke2: '#222c3d',
  colorNeutralStrokeAccessible: '#a5afbd', colorStrokeFocus2: '#00e29d',
  colorBrandBackground: '#00e29d', colorBrandBackgroundHover: '#6bf1c5',
  colorBrandBackgroundPressed: '#00c88b', colorBrandBackgroundSelected: '#6bf1c5',
  colorNeutralForegroundOnBrand: '#080e1a',
  colorBrandForeground1: '#00e29d', colorBrandForeground2: '#00e29d',
  colorBrandForegroundLink: '#00e29d', colorBrandForegroundLinkHover: '#6bf1c5',
  colorBrandForegroundLinkPressed: '#00c88b',
  colorBrandBackground2: '#183f33', colorBrandStroke1: '#00e29d',
  colorCompoundBrandForeground1: '#00e29d', colorCompoundBrandForeground1Hover: '#6bf1c5',
  colorCompoundBrandForeground1Pressed: '#00c88b',
  colorCompoundBrandStroke: '#00e29d', colorCompoundBrandStrokeHover: '#6bf1c5',
  colorCompoundBrandStrokePressed: '#00c88b', colorCompoundBrandBackground: '#00e29d',
  colorCompoundBrandBackgroundHover: '#6bf1c5', colorCompoundBrandBackgroundPressed: '#00c88b',
}

const publicPaths = ['/login', '/signup', '/paid', '/failed', '/expired', '/portal', '/hotspot-portal', '/bayar'] as const

export function isInternalPath(pathname: string): boolean {
  return !publicPaths.some(path => pathname === path || pathname.startsWith(path + '/'))
}
