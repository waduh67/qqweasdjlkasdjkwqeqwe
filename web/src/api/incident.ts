/** Insiden hasil korelasi alarm (module `incident`). */
export interface IncidentView {
  id: string
  key: string
  rootType: string
  rootId: string
  rootLabel: string
  severity: string
  status: string
  title: string
  alarmCount: number
  affectedCustomerCount: number
  openedAt: string
  lastSeenAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
}

export interface IncidentAlarm {
  entityType: string
  entityId: string
  kind: string
  severity: string
  label: string
}

export interface IncidentEventView {
  type: string
  message: string
  at: string
}

export interface IncidentDetail {
  incident: IncidentView
  timeline: IncidentEventView[]
  members: IncidentAlarm[]
}
