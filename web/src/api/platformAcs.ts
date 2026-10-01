import { api } from './client'

export interface PlatformAcsSettings {
  version: string
  nbiUrl: string
  username: string
  passwordSet: boolean
  cwmpUrl: string | null
  persisted: boolean
}

export interface UpdatePlatformAcsSettings {
  nbiUrl: string
  username: string
  password: string
  cwmpUrl: string
}

export interface AcsConnectionResult {
  reachable: boolean
  latencyMs: number | null
  error: string | null
}

export const getPlatformAcsSettings = () => api.get<PlatformAcsSettings>('/api/platform/acs-settings')
export const updatePlatformAcsSettings = (request: UpdatePlatformAcsSettings) =>
  api.put<PlatformAcsSettings>('/api/platform/acs-settings', request)
export const testPlatformAcsConnection = () => api.post<AcsConnectionResult>('/api/platform/acs-settings/test', {})
