export interface ValidationRule {
  code: string
  label: string
  desc: string
  category: 'system' | 'checklist'
  appliesToBC: boolean
  appliesToContractuel: boolean
}

export type RuleGroup = 'completude' | 'montants' | 'references' | 'identifiants' | 'documents' | 'dates'

export const RULE_GROUPS: { key: RuleGroup; label: string }[] = [
  { key: 'completude', label: 'Completude' },
  { key: 'montants', label: 'Montants' },
  { key: 'references', label: 'References' },
  { key: 'identifiants', label: 'Identifiants' },
  { key: 'documents', label: 'Documents' },
  { key: 'dates', label: 'Dates & Validite' },
]

export const ALL_RULES: (ValidationRule & { group?: RuleGroup })[] = [
  // 1. Completude
  { code: 'R20', label: 'Completude du dossier', desc: 'Verifie que toutes les pieces obligatoires sont presentes', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'completude' },

  // 2. Montants
  { code: 'R16', label: 'Verification arithmetique HT+TVA=TTC', desc: 'Verifie que HT + TVA = TTC sur la facture', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants' },
  { code: 'R16b', label: 'Arithmetique des lignes facture', desc: 'Pour chaque ligne de la facture, verifie que quantite x prix unitaire HT = montant total HT de la ligne (tolerance parametrable, par defaut 5 %).', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants' },
  { code: 'R16c', label: 'Somme des lignes = HT facture', desc: 'Additionne les montants HT de toutes les lignes de la facture et verifie que le total est egal au montant HT imprime en pied de facture.', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants' },
  { code: 'R01', label: 'Concordance montant TTC', desc: 'Compare le TTC de la facture avec le BC', category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants' },
  { code: 'R02', label: 'Concordance montant HT', desc: 'Compare le HT de la facture avec le BC', category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants' },
  { code: 'R03', label: 'Concordance TVA', desc: 'Compare la TVA de la facture avec le BC', category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants' },
  { code: 'R01f', label: 'Somme lignes facture = somme lignes BC', desc: 'Compare la somme des lignes de la facture avec la somme des lignes du bon de commande. Permet de detecter un ecart de volumetrie (lignes manquantes ou en trop) meme si les totaux de pied restent coherents.', category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants' },
  { code: 'R04', label: 'Montant OP = TTC (sans retenues)', desc: 'Verifie que le montant de l\'OP correspond au TTC de la facture', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants' },
  { code: 'R05', label: 'Montant OP = TTC - retenues', desc: 'Verifie le montant OP apres deduction des retenues a la source', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants' },
  { code: 'R15', label: 'Grille tarifaire x duree', desc: 'Verifie que la somme des prix mensuels de l\'avenant x nombre de mois = HT facture', category: 'system', appliesToBC: false, appliesToContractuel: true, group: 'montants' },

  // 3. References
  { code: 'R07', label: 'Reference facture dans l\'OP', desc: 'Verifie que le numero de facture est cite dans l\'ordre de paiement', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'references' },
  { code: 'R08', label: 'Reference BC/contrat dans l\'OP', desc: 'Verifie que le numero de BC ou contrat est cite dans l\'OP', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'references' },

  // 4. Identifiants
  { code: 'R09', label: 'Coherence ICE', desc: 'Verifie que l\'ICE du fournisseur est identique entre facture et attestation fiscale', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants' },
  { code: 'R10', label: 'Coherence IF', desc: 'Verifie que l\'identifiant fiscal est identique entre documents', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants' },
  { code: 'R11', label: 'Coherence RIB', desc: 'Verifie que le RIB de la facture correspond a celui de l\'OP', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants' },
  { code: 'R14', label: 'Coherence fournisseur', desc: 'Compare le nom du fournisseur entre la facture, le bon de commande, l\'ordre de paiement (beneficiaire), le tableau de controle, la checklist (prestataire) et la raison sociale de l\'attestation fiscale. Conforme si tous les noms sont identiques apres normalisation.', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants' },

  // 5. Documents
  { code: 'R12', label: 'Checklist autocontrole', desc: 'Verifie que tous les points de la checklist sont valides', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'documents' },
  { code: 'R13', label: 'Tableau de controle', desc: 'Verifie que tous les points du TC sont Conforme ou NA', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'documents' },

  // 6. Dates
  { code: 'R17', label: 'Chronologie des dates', desc: 'Verifie que date BC/contrat <= date facture <= date OP', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates' },
  { code: 'R18', label: 'Validite attestation fiscale', desc: 'Verifie que la date d\'edition de l\'attestation fiscale est comprise dans les 6 derniers mois (fenetre reglementaire DGI).', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates' },
  { code: 'R19', label: 'Authenticite attestation fiscale (QR DGI)', desc: 'Extrait le QR code de l\'attestation, compare le code de verification du QR avec celui imprime sous le QR, et verifie que l\'URL pointe bien vers un domaine officiel tax.gov.ma. Le lien peut etre ouvert dans les preuves pour verification manuelle sur le portail DGI.', category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates' },

  // Checklist d'autocontrole MADAEF (CCF-EN-04-V02, 15/10/2021)
  // Points exacts du document officiel
  { code: 'CK01', label: 'Concordance facture / modalites contractuelles / livrables',
    desc: 'La concordance entre la facture, les modalites de paiement contractuelles (Bon de commande, contrat,...) et les livrables (respect de l\'echeancier de paiement)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK02', label: 'Verification arithmetique des montants',
    desc: 'La verification arithmetique des montants figurant au niveau de la facture a regler par rapport a l\'echeancier de paiement',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK03', label: 'Respect du delai d\'execution',
    desc: 'La verification du respect du delai d\'execution des prestations par rapport aux modalites contractuelles (Bon de commande, contrat,...)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK04', label: 'Modifications / avenants (plafonds et variations)',
    desc: 'En cas d\'existence de modification dans la consistance des prestations (avenants), s\'assurer du respect du reglement des achats en ce qui concerne les plafonds et variations autorisees',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK05', label: 'Retenues de garantie et penalites de retard',
    desc: 'La verification de l\'application des retenues (retenue de garantie et assurances) et penalites de retard contractuelles sur le montant des prestations realisees',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK06', label: 'Signatures et visas des personnes habilitees',
    desc: 'L\'existence de l\'ensemble des signatures et visas des personnes habilitees intervenant dans le circuit de validation des depenses a regler (facture) et des documents contractuels avec la mention "service fait" et "Bon a payer"',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK07', label: 'Conformite reglementaire de la facture',
    desc: 'La verification de la conformite de la facture par rapport aux exigences reglementaires (ICE, identifiant fiscal, numero du RC, CNSS, raison sociale,...)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK08', label: 'Conformite du RIB contractuel vs facture',
    desc: 'La conformite du RIB contractuel avec le RIB presente au niveau de la facture (se referer toujours au RIB contractuel si ce dernier precise le numero de RIB)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK09', label: 'Conformite BL / PV de reception',
    desc: 'La verification de la conformite du bon de livraison et/ou du PV de reception par rapport a la facture et au bon de commande',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
  { code: 'CK10', label: 'Habilitations des signataires des receptions',
    desc: 'La verification de la conformite des habilitations des signataires des receptions (Bon de livraison ou PV de reception definitive)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true },
]

const STORAGE_KEY = 'recondoc_disabled_rules'

export function getDisabledRules(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? new Set(JSON.parse(raw)) : new Set()
  } catch { return new Set() }
}

export function setDisabledRules(codes: Set<string>) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify([...codes]))
}

export function isRuleEnabled(code: string): boolean {
  return !getDisabledRules().has(code)
}

export function getActiveRules(dossierType: 'BC' | 'CONTRACTUEL'): ValidationRule[] {
  const disabled = getDisabledRules()
  return ALL_RULES.filter(r => {
    if (disabled.has(r.code)) return false
    return dossierType === 'BC' ? r.appliesToBC : r.appliesToContractuel
  })
}
