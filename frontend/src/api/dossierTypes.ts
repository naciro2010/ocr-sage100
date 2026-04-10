export type DossierType = 'BC' | 'CONTRACTUEL'
export type StatutDossier = 'BROUILLON' | 'EN_VERIFICATION' | 'VALIDE' | 'REJETE'
export type TypeDocument = 'FACTURE' | 'BON_COMMANDE' | 'CONTRAT_AVENANT' | 'ORDRE_PAIEMENT' | 'CHECKLIST_AUTOCONTROLE' | 'CHECKLIST_PIECES' | 'TABLEAU_CONTROLE' | 'PV_RECEPTION' | 'ATTESTATION_FISCALE' | 'FORMULAIRE_FOURNISSEUR' | 'INCONNU'
export type StatutExtraction = 'EN_ATTENTE' | 'EN_COURS' | 'EXTRAIT' | 'ERREUR'
export type StatutCheck = 'CONFORME' | 'NON_CONFORME' | 'AVERTISSEMENT' | 'NON_APPLICABLE'

export interface DossierListItem {
  id: string
  reference: string
  type: DossierType
  statut: StatutDossier
  fournisseur: string | null
  description: string | null
  montantTtc: number | null
  montantNetAPayer: number | null
  dateCreation: string
  nbDocuments: number
  nbChecksConformes: number
  nbChecksTotal: number
}

export interface DossierDetail {
  id: string
  reference: string
  type: DossierType
  statut: StatutDossier
  fournisseur: string | null
  description: string | null
  montantTtc: number | null
  montantHt: number | null
  montantTva: number | null
  montantNetAPayer: number | null
  dateCreation: string
  dateValidation: string | null
  validePar: string | null
  motifRejet: string | null
  documents: DocumentInfo[]
  facture: Record<string, unknown> | null
  bonCommande: Record<string, unknown> | null
  contratAvenant: Record<string, unknown> | null
  ordrePaiement: Record<string, unknown> | null
  checklistAutocontrole: Record<string, unknown> | null
  tableauControle: Record<string, unknown> | null
  pvReception: Record<string, unknown> | null
  attestationFiscale: Record<string, unknown> | null
  resultatsValidation: ValidationResult[]
}

export interface DocumentInfo {
  id: string
  typeDocument: TypeDocument
  nomFichier: string
  statutExtraction: StatutExtraction
  erreurExtraction: string | null
  dateUpload: string
  donneesExtraites: Record<string, unknown> | null
}

export interface ValidationResult {
  regle: string
  libelle: string
  statut: StatutCheck
  detail: string | null
  valeurAttendue: string | null
  valeurTrouvee: string | null
  source: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface DashboardStats {
  total: number
  brouillons: number
  enVerification: number
  valides: number
  rejetes: number
  montantTotal: number
}

export interface AuditEntry {
  action: string
  detail: string | null
  utilisateur: string | null
  dateAction: string
}

export const TYPE_DOCUMENT_LABELS: Record<TypeDocument, string> = {
  FACTURE: 'Facture',
  BON_COMMANDE: 'Bon de commande',
  CONTRAT_AVENANT: 'Contrat / Avenant',
  ORDRE_PAIEMENT: 'Ordre de paiement',
  CHECKLIST_AUTOCONTROLE: 'Checklist autocontrole',
  CHECKLIST_PIECES: 'Checklist pieces',
  TABLEAU_CONTROLE: 'Tableau de controle',
  PV_RECEPTION: 'PV de reception',
  ATTESTATION_FISCALE: 'Attestation fiscale',
  FORMULAIRE_FOURNISSEUR: 'Formulaire fournisseur',
  INCONNU: 'A classer',
}

export const STATUT_CONFIG: Record<StatutDossier, { label: string; color: string; bg: string }> = {
  BROUILLON: { label: 'Brouillon', color: '#475569', bg: '#f1f5f9' },
  EN_VERIFICATION: { label: 'En verification', color: '#d97706', bg: '#fffbeb' },
  VALIDE: { label: 'Valide', color: '#059669', bg: '#ecfdf5' },
  REJETE: { label: 'Rejete', color: '#dc2626', bg: '#fef2f2' },
}

export const CHECK_ICONS: Record<StatutCheck, { icon: string; color: string }> = {
  CONFORME: { icon: '✓', color: '#10b981' },
  NON_CONFORME: { icon: '✗', color: '#ef4444' },
  AVERTISSEMENT: { icon: '⚠', color: '#f59e0b' },
  NON_APPLICABLE: { icon: '—', color: '#6b7280' },
}
