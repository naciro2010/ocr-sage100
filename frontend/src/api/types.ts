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
