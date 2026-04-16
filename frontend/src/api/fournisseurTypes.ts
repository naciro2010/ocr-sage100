import type { DossierListItem } from './dossierTypes'

export interface FournisseurSummary {
  nom: string
  ice: string | null
  identifiantFiscal: string | null
  rib: string | null
  nbDossiers: number
  nbBrouillons: number
  nbEnVerification: number
  nbValides: number
  nbRejetes: number
  montantTotalTtc: number
  montantValide: number
  dernierDossier: string | null
  premierDossier: string | null
}

export interface FournisseurDetail {
  nom: string
  ice: string | null
  identifiantFiscal: string | null
  rc: string | null
  rib: string | null
  nbDossiers: number
  nbBrouillons: number
  nbEnVerification: number
  nbValides: number
  nbRejetes: number
  montantTotalTtc: number
  montantTotalHt: number
  montantTotalTva: number
  montantValide: number
  montantEnCours: number
  dernierDossier: string | null
  premierDossier: string | null
  dossiers: DossierListItem[]
}

export interface FournisseursStats {
  totalFournisseurs: number
  fournisseursActifs: number
  montantTotalEngage: number
  topFournisseurs: FournisseurSummary[]
}
