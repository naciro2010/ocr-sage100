export interface Invoice {
  id: number
  fileName: string
  status: string
  supplierName: string | null
  invoiceNumber: string | null
  invoiceDate: string | null
  amountHt: number | null
  amountTva: number | null
  amountTtc: number | null
  currency: string
  sageSynced: boolean
  sageReference: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
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
