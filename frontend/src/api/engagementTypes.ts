export type TypeEngagement = 'MARCHE' | 'BON_COMMANDE' | 'CONTRAT'
export type StatutEngagement = 'ACTIF' | 'CLOTURE' | 'SUSPENDU'
export type CategorieMarche = 'TRAVAUX' | 'FOURNITURES' | 'SERVICES'
export type PeriodiciteContrat = 'MENSUEL' | 'TRIMESTRIEL' | 'SEMESTRIEL' | 'ANNUEL'

export interface EngagementListItem {
  id: string
  type: TypeEngagement
  reference: string
  statut: StatutEngagement
  objet: string | null
  fournisseur: string | null
  montantTtc: number | null
  dateDocument: string | null
  nbDossiers: number
  montantConsomme: number
  tauxConsommation: number | null
}

export interface MarcheDetails {
  numeroAo: string | null
  dateAo: string | null
  categorie: CategorieMarche | null
  delaiExecutionMois: number | null
  penalitesRetardJourPct: number | null
  retenueGarantiePct: number | null
  cautionDefinitivePct: number | null
  revisionPrixAutorisee: boolean
}

export interface BonCommandeDetails {
  plafondMontant: number | null
  dateValiditeFin: string | null
  seuilAntiFractionnement: number | null
}

export interface ContratDetails {
  periodicite: PeriodiciteContrat | null
  dateDebut: string | null
  dateFin: string | null
  reconductionTacite: boolean
  preavisResiliationJours: number | null
  indiceRevision: string | null
}

export interface DossierAttache {
  id: string
  reference: string
  statut: string
  fournisseur: string | null
  montantTtc: number | null
  dateCreation: string
}

export interface EngagementResponse {
  id: string
  type: TypeEngagement
  reference: string
  statut: StatutEngagement
  objet: string | null
  fournisseur: string | null
  montantHt: number | null
  montantTva: number | null
  tauxTva: number | null
  montantTtc: number | null
  dateDocument: string | null
  dateSignature: string | null
  dateNotification: string | null
  dateCreation: string
  dateModification: string | null
  marche?: MarcheDetails
  bonCommande?: BonCommandeDetails
  contrat?: ContratDetails
  dossiers: DossierAttache[]
  montantConsomme: number
  tauxConsommation: number | null
}

export interface EngagementStats {
  totalEngagements: number
  actifs: number
  clotures: number
  suspendus: number
  nbMarches: number
  nbBonsCommande: number
  nbContrats: number
  montantTotalTtc: number
  montantTotalConsomme: number
}

export interface CreateEngagementRequest {
  type: TypeEngagement
  reference: string
  objet?: string | null
  fournisseur?: string | null
  montantHt?: number | null
  montantTva?: number | null
  tauxTva?: number | null
  montantTtc?: number | null
  dateDocument?: string | null
  dateSignature?: string | null
  dateNotification?: string | null
  statut?: StatutEngagement | null

  numeroAo?: string | null
  dateAo?: string | null
  categorie?: CategorieMarche | null
  delaiExecutionMois?: number | null
  penalitesRetardJourPct?: number | null
  retenueGarantiePct?: number | null
  cautionDefinitivePct?: number | null
  revisionPrixAutorisee?: boolean | null

  plafondMontant?: number | null
  dateValiditeFin?: string | null
  seuilAntiFractionnement?: number | null

  periodicite?: PeriodiciteContrat | null
  dateDebut?: string | null
  dateFin?: string | null
  reconductionTacite?: boolean | null
  preavisResiliationJours?: number | null
  indiceRevision?: string | null
}

export const TYPE_CONFIG: Record<TypeEngagement, { label: string; shortLabel: string; color: string; bg: string }> = {
  MARCHE: { label: 'Marche public', shortLabel: 'Marche', color: '#5b21b6', bg: '#ede9fe' },
  BON_COMMANDE: { label: 'Bon de commande cadre', shortLabel: 'BC cadre', color: '#1e40af', bg: '#dbeafe' },
  CONTRAT: { label: 'Contrat', shortLabel: 'Contrat', color: '#065f46', bg: '#d1fae5' },
}

export const STATUT_ENG_CONFIG: Record<StatutEngagement, { label: string; color: string; bg: string }> = {
  ACTIF: { label: 'Actif', color: '#065f46', bg: '#d1fae5' },
  CLOTURE: { label: 'Cloture', color: '#64748b', bg: '#f1f5f9' },
  SUSPENDU: { label: 'Suspendu', color: '#92400e', bg: '#fef3c7' },
}
