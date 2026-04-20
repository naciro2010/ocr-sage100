export interface ValidationRule {
  code: string
  label: string
  desc: string
  category: 'system' | 'checklist'
  appliesToBC: boolean
  appliesToContractuel: boolean
  /** Formule humaine, ex: "facture.montantTTC ≈ bc.montantTTC (tol ±5%)" */
  formula?: string
  /** Methode concrete utilisee pour executer le controle */
  method?: string
  /**
   * Champs extraits que la regle utilise (format "<typeDoc>.<champ>" ou simplement "<champ>").
   * Sert a lier chaque donnee extraite aux regles qui s'en servent (cross-link UX).
   */
  fields?: string[]
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
  { code: 'R20', label: 'Completude du dossier', desc: 'Verifie que toutes les pieces obligatoires sont presentes',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'completude',
    formula: 'dossier.documents ⊇ { FACTURE, BC ou CONTRAT, ORDRE_PAIEMENT, CHECKLIST, TC, ATTESTATION_FISCALE }',
    method: 'Compte les types de documents presents dans le dossier et compare avec la liste obligatoire selon le type de dossier (BC ou CONTRACTUEL). Retourne la liste des documents manquants.',
    fields: ['typeDocument'],
  },

  // 2. Montants
  { code: 'R16', label: 'Verification arithmetique HT+TVA=TTC', desc: 'Verifie que HT + TVA = TTC sur la facture',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: 'facture.montantHT + facture.montantTVA ≈ facture.montantTTC (tol ±0.02 MAD)',
    method: 'Additionne montantHT et montantTVA extraits par Claude sur la facture et compare au montantTTC. Tolerance de 2 centimes pour absorber les arrondis.',
    fields: ['montantHT', 'montantTVA', 'montantTTC'],
  },
  { code: 'R16b', label: 'Arithmetique des lignes facture', desc: 'Pour chaque ligne de la facture, verifie que quantite x prix unitaire HT = montant total HT de la ligne (tolerance parametrable, par defaut 5 %).',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: '∀ ligne ∈ facture.lignes : ligne.quantite × ligne.prixUnitaireHT ≈ ligne.montantTotalHT',
    method: 'Itere sur facture.lignes (extraites par Claude) et verifie l\'egalite quantite × PU = total. Une ligne en erreur suffit a declencher non-conforme.',
    fields: ['lignes.quantite', 'lignes.prixUnitaireHT', 'lignes.montantTotalHT'],
  },
  { code: 'R16c', label: 'Somme des lignes = HT facture', desc: 'Additionne les montants HT de toutes les lignes de la facture et verifie que le total est egal au montant HT imprime en pied de facture.',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: 'Σ facture.lignes[i].montantTotalHT ≈ facture.montantHT (tol 5%)',
    method: 'Somme les montantTotalHT de toutes les lignes extraites et compare au montantHT de pied de facture. Detecte des lignes manquees par l\'OCR.',
    fields: ['lignes.montantTotalHT', 'montantHT'],
  },
  { code: 'R01', label: 'Concordance montant TTC', desc: 'Compare le TTC de la facture avec le BC',
    category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants',
    formula: 'facture.montantTTC ≈ bonCommande.montantTTC (tol ±5%)',
    method: 'Recupere le champ montantTTC des deux documents (extraction Claude) et calcule l\'ecart relatif. Conforme si |a-b|/max(a,b) ≤ tolerance.',
    fields: ['montantTTC'],
  },
  { code: 'R02', label: 'Concordance montant HT', desc: 'Compare le HT de la facture avec le BC',
    category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants',
    formula: 'facture.montantHT ≈ bonCommande.montantHT (tol ±5%)',
    method: 'Comparaison du champ montantHT extrait sur facture vs bon de commande avec tolerance configurable (app.tolerance-montant).',
    fields: ['montantHT'],
  },
  { code: 'R03', label: 'Concordance TVA', desc: 'Compare la TVA de la facture avec le BC',
    category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants',
    formula: 'facture.montantTVA ≈ bonCommande.montantTVA (tol ±5%)',
    method: 'Meme principe que R01/R02 applique au montant TVA. Permet de verifier la coherence fiscale entre commande et facture.',
    fields: ['montantTVA', 'tauxTVA'],
  },
  { code: 'R01f', label: 'Somme lignes facture = somme lignes BC', desc: 'Compare la somme des lignes de la facture avec la somme des lignes du bon de commande. Permet de detecter un ecart de volumetrie (lignes manquantes ou en trop) meme si les totaux de pied restent coherents.',
    category: 'system', appliesToBC: true, appliesToContractuel: false, group: 'montants',
    formula: 'Σ facture.lignes.montantTotalHT ≈ Σ bonCommande.lignes.montantTotalHT',
    method: 'Somme independante des lignes facture vs lignes BC. Detecte l\'ajout ou l\'oubli d\'une prestation non visible dans les totaux de pied.',
    fields: ['lignes.montantTotalHT'],
  },
  { code: 'R01g', label: 'Matching ligne par ligne facture ↔ BC/contrat', desc: 'Apparie chaque ligne de facture a une ligne du BC (ou a la grille tarifaire du contrat) via le code article ou la similarite du libelle, puis compare quantite, prix unitaire HT et montant HT. Non conforme si une ligne facture n\'a pas de correspondance ou si qte / PU / total divergent au-dela de la tolerance.',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: '∀ ligne facture, ∃ ligne BC : codeArticle=id OR similarity(designation) > 0.7',
    method: 'Algorithme de matching fuzzy : d\'abord par codeArticle, puis par similarite de libelle (Jaccard). Une fois appariees, compare qte, PU, total. Signale aussi les lignes non appariees.',
    fields: ['lignes.codeArticle', 'lignes.designation', 'lignes.quantite', 'lignes.prixUnitaireHT'],
  },
  { code: 'R04', label: 'Montant OP = TTC (sans retenues)', desc: 'Verifie que le montant de l\'OP correspond au TTC de la facture',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: 'ordrePaiement.montantOperation ≈ facture.montantTTC (si retenues absentes)',
    method: 'Si la facture ne comporte pas de retenues a la source, le montant de l\'OP doit correspondre exactement au TTC. Sinon R05 s\'applique.',
    fields: ['montantTTC', 'montantOperation'],
  },
  { code: 'R05', label: 'Montant OP = TTC - retenues', desc: 'Verifie le montant OP apres deduction des retenues a la source',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'montants',
    formula: 'ordrePaiement.montantNetAPayer ≈ facture.montantTTC − Σ facture.retenues.montant',
    method: 'Calcule le net a payer attendu (TTC − retenues) a partir des donnees de la facture et le compare au montant reel de l\'OP. Utilise aussi pour valider l\'arithmetique des retenues (R06 interne).',
    fields: ['montantTTC', 'retenues.montant', 'montantNetAPayer'],
  },
  { code: 'R15', label: 'Grille tarifaire x duree', desc: 'Verifie que la somme des prix mensuels de l\'avenant x nombre de mois = HT facture',
    category: 'system', appliesToBC: false, appliesToContractuel: true, group: 'montants',
    formula: 'Σ (contrat.grille[mois] × duree) ≈ facture.montantHT',
    method: 'Specifique aux dossiers CONTRACTUEL. Reconstitue le HT attendu a partir de la grille tarifaire du contrat multipliee par la periode facturee.',
    fields: ['grilleTarifaire', 'periode', 'montantHT'],
  },

  // 3. References
  { code: 'R07', label: 'Reference facture dans l\'OP', desc: 'Verifie que le numero de facture est cite dans l\'ordre de paiement',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'references',
    formula: 'facture.numeroFacture ∈ ordrePaiement.referenceFacture',
    method: 'Normalisation des deux chaines (casse, espaces, separateurs) puis test d\'inclusion. Signale toute OP detachee de sa facture.',
    fields: ['numeroFacture', 'referenceFacture'],
  },
  { code: 'R08', label: 'Reference BC/contrat dans l\'OP', desc: 'Verifie que le numero de BC ou contrat est cite dans l\'OP',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'references',
    formula: 'bonCommande.numero ∈ ordrePaiement.referenceBcOuContrat',
    method: 'Meme principe que R07, mais sur la reference du bon de commande ou du contrat. Permet la tracabilite vers l\'engagement de depense.',
    fields: ['numeroBonCommande', 'referenceContrat', 'referenceBcOuContrat'],
  },

  // 4. Identifiants
  { code: 'R09', label: 'Coherence ICE', desc: 'Verifie que l\'ICE du fournisseur est identique entre facture et attestation fiscale',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants',
    formula: 'facture.ice == attestationFiscale.ice (15 chiffres normalises)',
    method: 'Supprime les espaces/ponctuation et compare caractere par caractere. L\'ICE marocain fait 15 chiffres exactement.',
    fields: ['ice'],
  },
  { code: 'R10', label: 'Coherence IF', desc: 'Verifie que l\'identifiant fiscal est identique entre documents',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants',
    formula: 'facture.identifiantFiscal == attestationFiscale.identifiantFiscal',
    method: 'Comparaison stricte apres normalisation. L\'IF est fourni par la DGI et doit etre identique entre toutes les pieces.',
    fields: ['identifiantFiscal'],
  },
  { code: 'R11', label: 'Coherence RIB', desc: 'Verifie que le RIB de la facture correspond a celui de l\'OP',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants',
    formula: 'ordrePaiement.rib ∈ facture.ribs (RIB de 24 chiffres)',
    method: 'Une facture peut citer plusieurs RIB ; on verifie que celui qui sera utilise par l\'OP figure bien dans la liste. Protection contre les fraudes de substitution de RIB.',
    fields: ['rib', 'ribs'],
  },
  { code: 'R14', label: 'Coherence fournisseur', desc: 'Compare le nom du fournisseur entre la facture, le bon de commande, l\'ordre de paiement (beneficiaire), le tableau de controle, la checklist (prestataire) et la raison sociale de l\'attestation fiscale. Conforme si tous les noms sont identiques apres normalisation.',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants',
    formula: 'facture.fournisseur ≈ bc.fournisseur ≈ op.beneficiaire ≈ tc.prestataire ≈ checklist.prestataire ≈ attestation.raisonSociale',
    method: 'Normalisation (casse, accents, SARL/S.A.R.L, espaces multiples) puis comparaison par paires. Utilise la distance de Levenshtein pour tolerer les variations de saisie minimes.',
    fields: ['fournisseur', 'beneficiaire', 'prestataire', 'raisonSociale'],
  },
  { code: 'R14b', label: 'Attestation fiscale = fournisseur facture', desc: 'Controle dedie : l\'attestation fiscale appartient bien au fournisseur facture. Compare raison sociale (similarite), ICE et IF entre la facture et l\'attestation. Toute divergence est non conforme (et pas simple avertissement).',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'identifiants',
    formula: 'facture.(fournisseur, ice, if) == attestation.(raisonSociale, ice, identifiantFiscal)',
    method: 'Triple verification (nom + ICE + IF) sur le couple facture/attestation. Toute divergence est bloquante pour le paiement.',
    fields: ['fournisseur', 'ice', 'identifiantFiscal', 'raisonSociale'],
  },

  // 5. Documents
  { code: 'R12', label: 'Checklist autocontrole', desc: 'Verifie que tous les points de la checklist sont valides',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'documents',
    formula: '∀ point ∈ checklist.points : estValide ∈ { "OUI", "NA" }',
    method: 'Parse les 10 points du document CCF-EN-04, verifie que chacun a un statut "OUI" ou "NA". Un seul "NON" ou point manquant declenche non-conforme.',
    fields: ['points.estValide'],
  },
  { code: 'R13', label: 'Tableau de controle', desc: 'Verifie que tous les points du TC sont Conforme ou NA',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'documents',
    formula: '∀ point ∈ tableauControle.points : statut ∈ { "CONFORME", "NA" }',
    method: 'Lit les items du tableau de controle financier. Statut admissible : CONFORME ou NA. Toute autre valeur (NON_CONFORME, vide) fait basculer en non-conforme.',
    fields: ['points.statut'],
  },

  // 6. Dates
  { code: 'R17', label: 'Chronologie des dates', desc: 'Verifie que date BC/contrat <= date facture <= date OP',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates',
    formula: 'bc.dateEmission ≤ facture.dateFacture ≤ ordrePaiement.dateOperation',
    method: 'Compare trois dates parsees. Protege contre les factures anterieures a la commande (anomalie) ou posterieures au paiement (irregularite comptable).',
    fields: ['dateFacture', 'dateOperation', 'dateEmission'],
  },
  { code: 'R18', label: 'Validite attestation fiscale', desc: 'Verifie que la date d\'edition de l\'attestation fiscale est comprise dans les 6 derniers mois (fenetre reglementaire DGI).',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates',
    formula: 'today − attestation.dateEdition ≤ 180 jours',
    method: 'Regle reglementaire DGI : l\'attestation fiscale ne reste valide que 6 mois. Compare la date d\'edition extraite a la date du jour.',
    fields: ['dateEdition', 'validite'],
  },
  { code: 'R19', label: 'Authenticite attestation fiscale (QR DGI)', desc: 'Extrait le QR code de l\'attestation, compare le code de verification du QR avec celui imprime sous le QR, et verifie que l\'URL pointe bien vers un domaine officiel tax.gov.ma. Le lien peut etre ouvert dans les preuves pour verification manuelle sur le portail DGI.',
    category: 'system', appliesToBC: true, appliesToContractuel: true, group: 'dates',
    formula: 'qr.code == imprime.codeVerification && qr.url ∈ { *.tax.gov.ma }',
    method: 'Decode le QR code via ZXing, extrait le code embarque et l\'URL. Compare le code au code imprime sous le QR (extrait par OCR) et valide le domaine de l\'URL.',
    fields: ['codeVerification', '_qr'],
  },

  // Checklist d'autocontrole MADAEF (CCF-EN-04-V02, 15/10/2021)
  // Points exacts du document officiel — methode = "verification humaine guidee" car ils demandent le jugement
  { code: 'CK01', label: 'Concordance facture / modalites contractuelles / livrables',
    desc: 'La concordance entre la facture, les modalites de paiement contractuelles (Bon de commande, contrat,...) et les livrables (respect de l\'echeancier de paiement)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'facture ~ echeancier(contrat) ∧ livrables livres',
    method: 'Verification humaine : l\'utilisateur coche si la facture respecte le calendrier de paiement et si les livrables mentionnes dans le BC/contrat ont bien ete receptionnes.',
  },
  { code: 'CK02', label: 'Verification arithmetique des montants',
    desc: 'La verification arithmetique des montants figurant au niveau de la facture a regler par rapport a l\'echeancier de paiement',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'facture.montants = echeancier.tranche',
    method: 'Verification humaine corroboree par les controles automatiques R16/R16b/R16c. L\'utilisateur valide que les totaux correspondent a la tranche prevue.',
  },
  { code: 'CK03', label: 'Respect du delai d\'execution',
    desc: 'La verification du respect du delai d\'execution des prestations par rapport aux modalites contractuelles (Bon de commande, contrat,...)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'date(reception) ≤ date(commande) + delai contractuel',
    method: 'Verification humaine : lecture du PV de reception et comparaison avec le delai inscrit dans le BC/contrat. Si depassement, des penalites CK05 peuvent etre appliquees.',
  },
  { code: 'CK04', label: 'Modifications / avenants (plafonds et variations)',
    desc: 'En cas d\'existence de modification dans la consistance des prestations (avenants), s\'assurer du respect du reglement des achats en ce qui concerne les plafonds et variations autorisees',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'Σ avenants ≤ plafond reglement achats (typiquement ±10% du contrat initial)',
    method: 'Verification humaine : si des avenants existent, l\'utilisateur verifie qu\'ils respectent les regles internes MADAEF de variation du contrat.',
  },
  { code: 'CK05', label: 'Retenues de garantie et penalites de retard',
    desc: 'La verification de l\'application des retenues (retenue de garantie et assurances) et penalites de retard contractuelles sur le montant des prestations realisees',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'facture.retenues ⊇ { RG, assurance, penalites si retard }',
    method: 'Verification humaine appuyee par les retenues extraites par Claude (facture.retenues). L\'utilisateur confirme que toutes les retenues contractuelles apparaissent.',
    fields: ['retenues'],
  },
  { code: 'CK06', label: 'Signatures et visas des personnes habilitees',
    desc: 'L\'existence de l\'ensemble des signatures et visas des personnes habilitees intervenant dans le circuit de validation des depenses a regler (facture) et des documents contractuels avec la mention "service fait" et "Bon a payer"',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'mentions { "service fait", "bon a payer" } presentes ∧ signataires habilites',
    method: 'Verification humaine visuelle sur les documents PDF. La mention textuelle peut etre detectee par OCR mais l\'habilitation des signataires reste une decision humaine.',
    fields: ['signataires', 'signataire'],
  },
  { code: 'CK07', label: 'Conformite reglementaire de la facture',
    desc: 'La verification de la conformite de la facture par rapport aux exigences reglementaires (ICE, identifiant fiscal, numero du RC, CNSS, raison sociale,...)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'facture.{ice, identifiantFiscal, rc, cnss, raisonSociale} tous presents',
    method: 'Verification humaine renforcee par R09, R10, R14. L\'utilisateur s\'assure que l\'ensemble des identifiants reglementaires figure sur la facture.',
    fields: ['ice', 'identifiantFiscal', 'rc', 'cnss', 'raisonSociale'],
  },
  { code: 'CK08', label: 'Conformite du RIB contractuel vs facture',
    desc: 'La conformite du RIB contractuel avec le RIB presente au niveau de la facture (se referer toujours au RIB contractuel si ce dernier precise le numero de RIB)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'facture.rib == contrat.rib (si specifie)',
    method: 'Verification humaine appuyee par R11. Priorite au RIB contractuel si le contrat en specifie un.',
    fields: ['rib', 'ribs'],
  },
  { code: 'CK09', label: 'Conformite BL / PV de reception',
    desc: 'La verification de la conformite du bon de livraison et/ou du PV de reception par rapport a la facture et au bon de commande',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'PV.prestations ⊇ facture.lignes ∧ PV.prestations ⊆ BC.prestations',
    method: 'Verification humaine : l\'utilisateur croise les prestations livrees (PV) avec celles facturees et commandees. Rien de plus, rien de moins.',
  },
  { code: 'CK10', label: 'Habilitations des signataires des receptions',
    desc: 'La verification de la conformite des habilitations des signataires des receptions (Bon de livraison ou PV de reception definitive)',
    category: 'checklist', appliesToBC: true, appliesToContractuel: true,
    formula: 'PV.signataire ∈ liste habilitations internes',
    method: 'Verification humaine contre le referentiel interne MADAEF des personnes habilitees a receptionner.',
    fields: ['signataire', 'signataires'],
  },
]

/**
 * Index inverse : pour chaque champ extrait, liste les codes de regles qui l'utilisent.
 * Permet d'afficher, sur un champ affiche dans ExtractedDataView, la liste des regles
 * susceptibles de recalcul quand la valeur est corrigee.
 *
 * Construit a partir des `fields` declares dans ALL_RULES. Les champs imbriques
 * (ex. "lignes.montantTotalHT") sont exposes tels quels, et leur racine
 * (ex. "lignes") pointe sur la meme liste de regles pour les lookups larges.
 */
export const FIELD_TO_RULES: Record<string, string[]> = (() => {
  const map: Record<string, Set<string>> = {}
  for (const rule of ALL_RULES) {
    if (!rule.fields) continue
    for (const field of rule.fields) {
      if (!map[field]) map[field] = new Set()
      map[field].add(rule.code)
      const root = field.split('.')[0]
      if (root !== field) {
        if (!map[root]) map[root] = new Set()
        map[root].add(rule.code)
      }
    }
  }
  const out: Record<string, string[]> = {}
  for (const key of Object.keys(map)) {
    out[key] = Array.from(map[key]).sort()
  }
  return out
})()

/** Retourne les codes de regles qui utilisent ce champ extrait (ou tableau vide). */
export function rulesForField(field: string): string[] {
  return FIELD_TO_RULES[field] || []
}

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
