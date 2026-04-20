export interface AiSettingsResponse {
  enabled: boolean
  apiKey: string
  apiKeyConfigured: boolean
  model: string
  baseUrl: string
}

export interface OcrSettingsResponse {
  tikaVersion: string
  tesseractAvailable: boolean
  languages: string
  preprocessingEnabled: boolean
  dpi: number
  mistralEnabled: boolean
  mistralApiKey: string
  mistralApiKeyConfigured: boolean
  mistralModel: string
  mistralBaseUrl: string
}

export type HealthTone = 'ok' | 'warn' | 'error' | 'muted'
export type HealthStatus = 'up' | 'degraded' | 'down' | 'off'

export interface HealthComponent {
  id: string
  label: string
  category: 'core' | 'pipeline' | 'runtime'
  status: HealthStatus
  tone: HealthTone
  latencyMs?: number
  details?: Record<string, unknown>
}

export interface SystemHealthResponse {
  checkedAt: string
  uptime: string
  application: {
    name: string
    startedAt: string
    javaVersion: string
    timezone: string
  }
  components: HealthComponent[]
}
