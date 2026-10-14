import type { CSSProperties } from 'react'

export function fiberStyle(hex?: string | null): CSSProperties | undefined {
  if (!hex || !/^#(?:[a-f\d]{3}|[a-f\d]{6})$/i.test(hex)) return undefined
  const rgb = hex.length === 4 ? [...hex.slice(1)].map(channel => channel.repeat(2)).join('') : hex.slice(1)
  const [r, g, b] = [0, 2, 4].map(offset => {
    const channel = parseInt(rgb.slice(offset, offset + 2), 16) / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
  // Choose the higher WCAG contrast ratio; either black or white exceeds 4.5:1.
  const ink = (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? '#000000' : '#ffffff'
  return { '--fiber-color': hex, '--fiber-ink': ink } as CSSProperties
}

