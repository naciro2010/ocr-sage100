export interface Invoice {
  id: number
  fileName: string
  status: string
  rawText: string | null

  supplierName: string | null
  supplierIce: string | null
  supplierIf: string | null
  supplierRc: string | null
  supplierPatente: string | null
  supplierCnss: string | null
  supplierAddress: string | null
  supplierCity: string | null

  clientName: string | null
  clientIce: string | null

  invoiceNumber: string | null
  invoiceDate: string | null

  amountHt: number | null
  tvaRate: number | null
  amountTva: number | null
  amountTtc: number | null
  discountAmount: number | null
  discountPercent: number | null
  currency: string

  paymentMethod: string | null
  paymentDueDate: string | null
  bankName: string | null
  bankRib: string | null

  lineItems: LineItem[]

  ocrEngine: string | null
  ocrConfidence: number | null
  ocrPageCount: number | null
  aiUsed: boolean

  sageSynced: boolean
  sageReference: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface LineItem {
  id: number
  lineNumber: number
  description: string | null
  quantity: number | null
  unit: string | null
  unitPriceHt: number | null
  tvaRate: number | null
  tvaAmount: number | null
  totalHt: number | null
  totalTtc: number | null
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface DashboardStats {
  totalInvoices: number
  byStatus: Record<string, number>
  sageSynced: number
  pendingSync: number
  totalProcessedAmount: number
  topSuppliers: Record<string, number>
}

export interface BatchResult {
  totalFiles: number
  successful: number
  failed: number
  processingTimeMs?: number
  results: BatchItemResult[]
}

export interface BatchItemResult {
  fileName: string
  success: boolean
  invoiceId?: number
  status?: string
  error?: string
}

export interface BatchSyncResult {
  totalInvoices: number
  synced: number
  failed: number
  erpType?: string
  results: BatchSyncItemResult[]
}

export interface BatchSyncItemResult {
  invoiceId: number
  success: boolean
  sageReference?: string
  error?: string
}

export interface ValidationResult {
  valid: boolean
  errors: ValidationMessage[]
  warnings: ValidationMessage[]
}

export interface ValidationMessage {
  field: string
  message: string
  severity: 'ERROR' | 'WARNING' | 'INFO'
}

export interface InvoiceUpdateRequest {
  supplierName?: string
  supplierIce?: string
  supplierIf?: string
  supplierRc?: string
  supplierPatente?: string
  supplierCnss?: string
  supplierAddress?: string
  supplierCity?: string
  clientName?: string
  clientIce?: string
  invoiceNumber?: string
  invoiceDate?: string
  amountHt?: number
  tvaRate?: number
  amountTva?: number
  amountTtc?: number
  discountAmount?: number
  discountPercent?: number
  currency?: string
  paymentMethod?: string
  paymentDueDate?: string
  bankName?: string
  bankRib?: string
}

export interface ErpSettings {
  erpType: string
  configured: boolean
}

export interface AiSettingsResponse {
  enabled: boolean
  apiKey: string
  apiKeyConfigured: boolean
  model: string
  baseUrl: string
}

export interface ErpSettingsResponse {
  activeType: string
  availableTypes: string[]
  sage1000: {
    baseUrl: string
    apiKey: string
    apiKeyConfigured: boolean
    companyCode: string
    timeout: string
  }
  sageX3: {
    baseUrl: string
    clientId: string
    clientSecret: string
    clientSecretConfigured: boolean
    folder: string
    poolAlias: string
  }
  sage50: {
    baseUrl: string
    username: string
    password: string
    passwordConfigured: boolean
    companyFile: string
    journalCode: string
    fiscalYear: string
  }
}
