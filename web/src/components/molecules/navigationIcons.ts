export {
  Apps16Filled as Dashboard, ChartMultiple16Filled as Chart, People16Filled as Customers,
  Chat16Filled as Chat, Receipt16Filled as Receipt, Box16Filled as Package,
  Wifi120Filled as Wifi, Map16Filled as Map, Storage16Filled as Storage,
  Gauge16Filled as Gauge, Branch16Filled as Route, Pulse20Filled as Monitor,
  Connector16Filled as Cable, Flow16Filled as Workflow, BoxCheckmark16Filled as PackageCheck,
  Warning16Filled as Alert, ClipboardTask16Filled as WorkOrder, ClipboardArrowRight16Filled as Inbox,
  CalendarCheckmark16Filled as Calendar, Payment16Filled as Payment, People16Filled as Users,
  ShieldCheckmark16Filled as Shield, Location16Filled as Area, DocumentSearch16Filled as Audit,
  Building16Filled as Building, Beaker16Filled as Flask, Mail16Filled as Mail, Add16Filled as Plus,
} from '@fluentui/react-icons'

import {
  Library16Filled, Stack16Filled, ArrowDownload16Filled, ClipboardBulletList16Filled,
  ArrowUpload16Filled, ArrowBidirectionalLeftRight16Filled, Toolbox16Filled,
  ClipboardCheckmark16Filled, CheckmarkCircle16Filled, ChartMultiple16Filled,
  History16Filled, Settings16Filled,
} from '@fluentui/react-icons'
import type { WAREHOUSE_PAGES } from '@/pages/warehouse/navigation'
import type { NavItem } from './SidebarNav'

export const warehouse: Record<typeof WAREHOUSE_PAGES[number]['path'], NavItem['icon']> = {
  catalog: Library16Filled, stock: Stack16Filled, receipts: ArrowDownload16Filled,
  requests: ClipboardBulletList16Filled, replenishment: ArrowUpload16Filled,
  transfers: ArrowBidirectionalLeftRight16Filled, returns: Toolbox16Filled,
  counts: ClipboardCheckmark16Filled, approvals: CheckmarkCircle16Filled,
  reports: ChartMultiple16Filled, provenance: History16Filled, settings: Settings16Filled,
}
