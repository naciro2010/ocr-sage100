export interface Invoice {
  id: number
  fileName: string
  status: string

  // Supplier
  supplierName: string | null
  supplierIce: string | null
  supplierIf: string | null
  supplierRc: string | null
  supplierPatente: string | null
  supplierCnss: string | null
  supplierAddress: string | null
  supplierCity: string | null

  // Client
  clientName: string | null
  clientIce: string | null

  // Invoice
  invoiceNumber: string | null
  invoiceDate: string | null

  // Amounts
  amountHt: number | null
  tvaRate: number | null
  amountTva: number | null
  amountTtc: number | null
  discountAmount: number | null
  discountPercent: number | null
  currency: string

  // Payment
  paymentMethod: string | null
  paymentDueDate: string | null
  bankName: string | null
  bankRib: string | null

  // Line items
  lineItems: LineItem[]

  // Sage
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
