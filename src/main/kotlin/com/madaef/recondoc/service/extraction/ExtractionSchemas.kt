package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument

/**
 * JSON Schemas d'entree des outils Anthropic `tool_use`. Utiliser ces schemas
 * force Claude a produire un JSON conforme au format attendu — fin des parse
 * errors sur reponse mal formee ou tronquee entouree de texte.
 *
 * Les cles correspondent 1:1 a ce que `ExtractionPrompts.kt` demande, pour
 * que `MandatoryFields`, `ExtractionSchemaValidator` et le scoring qualite
 * continuent de fonctionner sans adaptation.
 *
 * Chaque schema expose un `name` stable (`extract_<type>_data`) utilise dans
 * la requete Anthropic `tools[].name` et `tool_choice.name`.
 */
object ExtractionSchemas {

    data class ToolSchema(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>
    )

    // --- Helpers schema ---

    private fun primitive(
        baseType: String,
        nullable: Boolean,
        description: String?
    ): MutableMap<String, Any> {
        val m = mutableMapOf<String, Any>()
        m["type"] = if (nullable) listOf(baseType, "null") else baseType
        if (description != null) m["description"] = description
        return m
    }

    private fun str(description: String? = null, pattern: String? = null, nullable: Boolean = true): Map<String, Any> =
        primitive("string", nullable, description).also { if (pattern != null) it["pattern"] = pattern }

    private fun num(description: String? = null, minimum: Number? = null, nullable: Boolean = true): Map<String, Any> =
        primitive("number", nullable, description).also { if (minimum != null) it["minimum"] = minimum }

    private fun bool(description: String? = null, nullable: Boolean = true): Map<String, Any> =
        primitive("boolean", nullable, description)

    private fun enumField(
        values: List<Any>,
        description: String? = null,
        nullable: Boolean = true,
        baseType: String = "number"
    ): Map<String, Any> =
        primitive(baseType, nullable, description).also {
            it["enum"] = if (nullable) values + listOf<Any?>(null) else values
        }

    private fun arrayOf(items: Map<String, Any>, description: String? = null): Map<String, Any> {
        val m = mutableMapOf<String, Any>("type" to "array", "items" to items)
        if (description != null) m["description"] = description
        return m
    }

    private fun obj(
        properties: Map<String, Map<String, Any>>,
        required: List<String> = emptyList(),
        description: String? = null
    ): Map<String, Any> {
        val m = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false
        )
        if (required.isNotEmpty()) m["required"] = required
        if (description != null) m["description"] = description
        return m
    }

    private fun qualityFields(): Map<String, Map<String, Any>> = mapOf(
        "_confidence" to num("Confiance globale 0..1 (1=tout clair, 0.5=OCR bruite)", minimum = 0, nullable = false),
        "_warnings" to arrayOf(mapOf("type" to "string"), description = "Liste des problemes detectes")
    )

    // --- Helpers Maroc (formats reutilises entre les schemas) ---

    private const val ISO_DATE_PATTERN = "^\\d{4}-\\d{2}-\\d{2}$"
    private const val ICE_PATTERN = "^\\d{15}$"
    private const val RIB_PATTERN = "^\\d{24}$"
    private val TVA_RATES_MAROC = listOf<Any>(0, 7, 10, 14, 20)

    private fun dateIso(description: String, nullable: Boolean = true): Map<String, Any> =
        str(description, pattern = ISO_DATE_PATTERN, nullable = nullable)

    private fun ice(description: String = "ICE (Identifiant Commun Entreprise) : EXACTEMENT 15 chiffres. Apparait apres 'ICE:' ou 'I.C.E'. Commence typiquement par 00/01/02/19. Normaliser OCR O->0, l->1. Si != 15 chiffres apres normalisation : null + warning."): Map<String, Any> =
        str(description, pattern = ICE_PATTERN, nullable = true)

    private fun rib(description: String = "RIB marocain : EXACTEMENT 24 chiffres. Structure 3(banque)+3(ville)+16(compte+cle)+2. Espaces/tirets a supprimer. Si != 24 chiffres apres normalisation : null + warning."): Map<String, Any> =
        str(description, pattern = RIB_PATTERN, nullable = true)

    private fun tauxTvaMaroc(description: String = "Taux TVA dominant. Legaux Maroc : 0 (exonere), 7, 10, 14, 20. Tout autre taux = erreur OCR -> choisir le plus proche + warning.", nullable: Boolean = true): Map<String, Any> =
        enumField(TVA_RATES_MAROC, description, nullable)

    // --- Schemas par type ---

    /**
     * Schema `classify_document` pour la classification via tool_use. Remplace le
     * parse regex de `ClassificationService` — Claude est force de renvoyer
     * `{categorie, confidence}` bien type, plus de parse fragile ni de
     * troncature silencieuse sur max_tokens=256.
     */
    val CLASSIFICATION = ToolSchema(
        name = "classify_document",
        description = "Classifie un document MADAEF dans l'une des categories attendues.",
        inputSchema = obj(
            properties = mapOf(
                "categorie" to enumField(
                    listOf(
                        "FACTURE", "BON_COMMANDE", "CONTRAT_AVENANT", "ORDRE_PAIEMENT",
                        "CHECKLIST_AUTOCONTROLE", "CHECKLIST_PIECES", "TABLEAU_CONTROLE",
                        "PV_RECEPTION", "ATTESTATION_FISCALE", "FORMULAIRE_FOURNISSEUR",
                        "MARCHE", "BON_COMMANDE_CADRE", "CONTRAT_CADRE", "INCONNU"
                    ),
                    description = "Categorie detectee. INCONNU si doute serieux.",
                    nullable = false,
                    baseType = "string"
                ),
                "confidence" to num(
                    "Confiance 0..1 (1 = certain, <0.6 = a considerer comme INCONNU)",
                    minimum = 0, nullable = false
                )
            ),
            required = listOf("categorie", "confidence")
        )
    )

    val FACTURE = ToolSchema(
        name = "extract_facture_data",
        description = "Extrait les donnees structurees d'une facture d'achat marocaine MADAEF. Ne jamais inventer un champ absent du texte : mettre null + warning explicite.",
        inputSchema = obj(
            properties = mapOf(
                "numeroFacture" to str("Numero de la facture tel qu'imprime (ex: 'DEV-2026-0142', 'F/2026/087', '2026-F-12'). Apparait pres du mot 'Facture N' ou 'Invoice No' en en-tete. Preserver les separateurs.", nullable = false),
                "dateFacture" to str("Date d'emission de la facture au format ISO YYYY-MM-DD. L'OCR peut donner 15/03/2026, 15-03-2026, 15.03.2026, '15 mars 2026' : normaliser toujours en YYYY-MM-DD. Refuser les dates impossibles (2099-13-45 -> null + warning).", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "fournisseur" to str("Raison sociale du fournisseur (ex: 'ACME SARL', 'Beta Consulting SA'). C'est l'ENTITE QUI FACTURE MADAEF, JAMAIS MADAEF lui-meme. Apparait generalement en en-tete avec le logo ou apres 'Fournisseur:'. Refuser les placeholders 'N/A', '-', '?'.", nullable = false),
                "client" to str("Raison sociale du client (typiquement MADAEF ou une de ses filiales : MADAEF GOLFS, HRM, CGI). Souvent apres 'Client:' ou 'A l'attention de:'."),
                "ice" to str("ICE (Identifiant Commun Entreprise) : EXACTEMENT 15 chiffres (ex: '001509176000008'). Apparait apres 'ICE:' ou 'I.C.E.'. Commence typiquement par 00, 01, 02, 19. Si OCR bruite, normaliser O->0 et l->1. Si nombre de chiffres != 15 apres normalisation : mettre null + warning.", pattern = "^\\d{15}$"),
                "identifiantFiscal" to str("IF marocain : 6 a 10 chiffres (ex: '40123456'). Apparait apres 'IF:' ou 'Identifiant Fiscal'. Parfois prefixe de la ville."),
                "rc" to str("Numero de Registre de Commerce : alphanumerique court (5-20 car.). Souvent prefixe d'une ville ex: 'Casa 45789', '45789 Casablanca', ou juste '123456'."),
                "rib" to str("RIB marocain principal : EXACTEMENT 24 chiffres. Structure usuelle: 3(banque)+3(ville)+16(compte+cle)+2. Les espaces/tirets dans l'OCR doivent etre supprimes. Si moins de 24 chiffres apres normalisation : null + warning.", pattern = "^\\d{24}$"),
                "ribs" to arrayOf(str(nullable = false), description = "Tous les RIB trouves dans le document (il peut y en avoir plusieurs si le fournisseur liste plusieurs comptes). Liste vide si aucun."),
                "montantHT" to num("Total HT general de la facture (hors taxes), toujours strictement positif. Prendre le TOTAL general, pas les sous-totaux de ligne. Suffixe 'DH', 'MAD', 'dirhams' a ignorer. Format 1 234,56 (FR) ou 1,234.56 (EN) a normaliser en 1234.56.", minimum = 0, nullable = false),
                "montantTVA" to num("Total TVA general de la facture. Somme de TOUTES les TVA si plusieurs taux coexistent (20% + 10% par exemple). Strictement >= 0 (0 si facture exoneree).", minimum = 0, nullable = false),
                "tauxTVA" to enumField(listOf(0, 7, 10, 14, 20), "Taux TVA dominant (applique au plus grand montantHT). Taux legaux Maroc : 0 (exonere), 7, 10, 14, 20. Tout autre taux = erreur OCR ou facture etrangere -> choisir le plus proche et ajouter un warning.", nullable = false),
                "montantTTC" to num("Total TTC general = HT + TVA. Doit etre strictement positif. Priorite : ligne 'NET A PAYER' > 'TOTAL TTC' > 'TOTAL GENERAL' > somme calculee. Verifier HT+TVA ≈ TTC (tolerance 1%).", minimum = 0, nullable = false),
                "devise" to str("Code devise ISO 4217 (MAD, EUR, USD). Detecter via le suffixe des montants ('1 234,56 DH', 'MAD', 'dirhams' = MAD ; 'EUR', '€', 'euros' = EUR ; 'USD', '$' = USD). Defaut : MAD si aucun signal etranger. Une devise non-MAD peut signaler une facture export, a verifier (regle R27 / CGNC + Loi 9-88).", pattern = "^[A-Z]{3}$"),
                "dateReceptionFacture" to str("Date de reception de la facture par MADAEF, distincte de dateFacture (date d'emission par le fournisseur). Apparait sur le cachet d'arrivee, le tampon dateur, ou la mention 'recu le'. Format ISO YYYY-MM-DD. Sert au decompte du delai legal de paiement marche public (60j, decret 2-22-431 art. 159).", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "referenceContrat" to str("Reference du contrat ou du bon de commande cite dans la facture (ex: 'CF SIE 2026-1234', 'Marche 2024/15'). Apparait souvent apres 'Ref. BC:' ou 'Marche N:'."),
                "periode" to str("Periode couverte par la prestation (ex: 'Janvier 2026', '01/01/2026 - 31/03/2026')."),
                "lignes" to arrayOf(obj(
                    properties = mapOf(
                        "codeArticle" to str(),
                        "designation" to str(nullable = false),
                        "quantite" to num(nullable = false),
                        "unite" to str(),
                        "prixUnitaireHT" to num(nullable = false),
                        "montantTotalHT" to num(nullable = false)
                    ),
                    required = listOf("designation", "quantite", "prixUnitaireHT", "montantTotalHT")
                ), description = "Lignes de detail")
            ) + qualityFields(),
            required = listOf("numeroFacture", "dateFacture", "fournisseur", "montantHT", "montantTVA", "tauxTVA", "montantTTC", "_confidence")
        )
    )

    val BON_COMMANDE = ToolSchema(
        name = "extract_bon_commande_data",
        description = "Extrait les donnees d'un bon de commande operationnel MADAEF (NON cadre, NON marche). Ne jamais inventer un champ absent : mettre null + warning.",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str("Reference du BC telle qu'imprimee (ex: 'CF SIE 2026-1234', 'BC-2026-042'). Apparait pres de 'BON DE COMMANDE N' ou 'Ref.'. Preserver les separateurs.", nullable = false),
                "dateBc" to str("Date d'emission du BC au format ISO YYYY-MM-DD. OCR peut donner dd/MM/yyyy, '01 fevrier 2026' : normaliser.", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "fournisseur" to str("Raison sociale du fournisseur (entite livrant la prestation), JAMAIS MADAEF. Refuser 'N/A', '-'.", nullable = false),
                "objet" to str("Description generale/synthetique du BC (ex: 'Entretien espaces verts', 'Fourniture de materiel informatique'). Souvent 1 phrase en en-tete."),
                "montantHT" to num("Total HT general du BC, strictement positif. Formats FR/EN a normaliser.", minimum = 0),
                "montantTVA" to num("Total TVA general du BC (somme si multi-taux). >= 0.", minimum = 0),
                "tauxTVA" to enumField(listOf(0, 7, 10, 14, 20), "Taux TVA dominant. Taux legaux Maroc : 0, 7, 10, 14, 20."),
                "montantTTC" to num("Total TTC general. Doit verifier HT+TVA ≈ TTC a 1%.", minimum = 0, nullable = false),
                "signataire" to str("Nom et fonction du signataire du BC (ex: 'M. Alami, Chef Dept Achats'). Apparait en bas du document."),
                "lignes" to arrayOf(obj(
                    properties = mapOf(
                        "numero" to num(),
                        "codeArticle" to str(),
                        "designation" to str(nullable = false),
                        "quantite" to num(nullable = false),
                        "unite" to str(),
                        "prixUnitaireHT" to num(nullable = false),
                        "montantLigneHT" to num(nullable = false),
                        "tauxTva" to num()
                    ),
                    required = listOf("designation", "quantite", "prixUnitaireHT", "montantLigneHT")
                ))
            ) + qualityFields(),
            required = listOf("reference", "dateBc", "fournisseur", "montantTTC", "_confidence")
        )
    )

    val ORDRE_PAIEMENT = ToolSchema(
        name = "extract_ordre_paiement_data",
        description = "Extrait les donnees d'un ordre de paiement MADAEF (OP) avec TOUTES les retenues a la source (TVA art.117, IS honoraires art.156, garantie). Ne pas inventer.",
        inputSchema = obj(
            properties = mapOf(
                "numeroOp" to str("Numero de l'ordre de paiement (ex: 'OP-2026-0042'). Apparait apres 'ORDRE DE PAIEMENT N'.", nullable = false),
                "dateEmission" to str("Date d'emission de l'OP au format ISO YYYY-MM-DD.", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "emetteur" to str("Entite MADAEF emettrice de l'OP (ex: 'MADAEF', 'MADAEF GOLFS', 'HRM'). Si non mentionne, null."),
                "natureOperation" to str("Nature de l'operation (ex: 'Reglement facture', 'Acompte', 'Avoir', 'Solde marche')."),
                "modePaiement" to enumField(
                    listOf("VIREMENT", "CHEQUE", "ESPECES", "EFFET", "PRELEVEMENT", "AUTRE"),
                    description = "Mode de reglement type. Detecter via les libelles : 'virement bancaire' / 'wire' = VIREMENT ; 'cheque n' = CHEQUE ; 'especes' / 'cash' / 'comptant' = ESPECES ; 'effet de commerce' / 'lettre de change' = EFFET ; 'prelevement' = PRELEVEMENT ; sinon AUTRE. Determinant pour R26 (plafond especes 5kMAD CGI art. 193-ter).",
                    baseType = "string"
                ),
                "devise" to str("Code devise ISO 4217 du paiement (MAD attendu, sinon signaler).", pattern = "^[A-Z]{3}$"),
                "signataireOrdonnateur" to str("Nom + fonction de l'ordonnateur (autorise la depense, distinct du comptable). Apparait dans le cartouche 'Vu et autorise par' / 'Ordonnateur' avec une signature. Decret 2-22-431 art. 21 : separation des pouvoirs obligatoire avec le comptable."),
                "signataireComptable" to str("Nom + fonction du comptable (execute le paiement, distinct de l'ordonnateur). Apparait dans le cartouche 'Comptable' / 'Tresorier' / 'Vise par'. JAMAIS la meme personne que signataireOrdonnateur."),
                "description" to str("Objet detaille/libre de l'operation. Texte explicatif du controleur."),
                "beneficiaire" to str("Raison sociale du beneficiaire = fournisseur a payer. JAMAIS MADAEF.", nullable = false),
                "rib" to str("RIB du beneficiaire : EXACTEMENT 24 chiffres apres suppression espaces/tirets. Sinon null + warning.", pattern = "^\\d{24}$"),
                "ribs" to arrayOf(str(nullable = false), description = "Tous les RIB trouves dans l'OP si plusieurs comptes mentionnes."),
                "banque" to str("Nom de la banque du beneficiaire (ex: 'BMCE Bank', 'Attijariwafa Bank', 'BCP', 'CIH')."),
                "montantBrut" to num("Montant brut AVANT retenues = TTC de la facture payee. Souvent identique a syntheseControleur.montantTTC.", minimum = 0),
                "montantOperation" to num("Montant NET paye au beneficiaire apres toutes retenues. C'est montantBrut - totalRetenues.", minimum = 0, nullable = false),
                "referenceFacture" to str("Numero de la facture payee (doit matcher le numeroFacture du document facture associe)."),
                "referenceBcOuContrat" to str("Reference du BC ou du contrat cite dans l'OP (ex: 'CF SIE 2026-1234')."),
                "referenceSage" to str("Reference comptable Sage (piece comptable generee). Alphanumerique."),
                "retenues" to arrayOf(obj(
                    properties = mapOf(
                        "type" to enumField(
                            listOf("TVA_SOURCE", "IS_HONORAIRES", "GARANTIE", "AUTRE"),
                            nullable = false, baseType = "string"
                        ),
                        "designation" to str(nullable = false),
                        "articleCGI" to str(),
                        "base" to num(nullable = false),
                        "taux" to num(nullable = false),
                        "montant" to num(nullable = false)
                    ),
                    required = listOf("type", "base", "taux", "montant")
                )),
                "syntheseControleur" to obj(
                    properties = mapOf(
                        "montantHT" to num(),
                        "montantTVA" to num(),
                        "montantTTC" to num(),
                        "totalRetenues" to num(),
                        "netAPayer" to num(),
                        "observations" to str()
                    )
                ),
                "piecesJustificatives" to arrayOf(str(nullable = false)),
                "conclusionControleur" to str()
            ) + qualityFields(),
            required = listOf("numeroOp", "dateEmission", "beneficiaire", "montantOperation", "_confidence")
        )
    )

    val CONTRAT_AVENANT = ToolSchema(
        name = "extract_contrat_data",
        description = "Extrait les donnees d'un contrat ou avenant MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "referenceContrat" to str("Reference contrat", nullable = false),
                "numeroAvenant" to str(),
                "dateSignature" to str("Date signature YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "parties" to arrayOf(str(nullable = false), description = "Parties contractantes"),
                "objet" to str("Objet du contrat", nullable = false),
                "dateEffet" to str("Date effet YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "grillesTarifaires" to arrayOf(obj(
                    properties = mapOf(
                        "designation" to str(nullable = false),
                        "prixUnitaireHT" to num(nullable = false),
                        "periodicite" to enumField(
                            listOf("MENSUEL", "TRIMESTRIEL", "ANNUEL", "JOURNALIER"),
                            baseType = "string"
                        ),
                        "entite" to str()
                    ),
                    required = listOf("designation", "prixUnitaireHT")
                ))
            ) + qualityFields(),
            required = listOf("referenceContrat", "dateSignature", "parties", "objet", "_confidence")
        )
    )

    val PV_RECEPTION = ToolSchema(
        name = "extract_pv_reception_data",
        description = "Extrait les donnees d'un PV de reception MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "titre" to str(),
                "dateReception" to str("Date YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "referenceContrat" to str("Reference contrat ou BC", nullable = false),
                "periodeDebut" to str("YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "periodeFin" to str("YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "prestations" to arrayOf(str(nullable = false), description = "Liste prestations recues"),
                "signataireMadaef" to str(),
                "signataireFournisseur" to str()
            ) + qualityFields(),
            required = listOf("dateReception", "referenceContrat", "prestations", "_confidence")
        )
    )

    val ATTESTATION_FISCALE = ToolSchema(
        name = "extract_attestation_fiscale_data",
        description = "Extrait les donnees d'une attestation de regularite fiscale DGI (Direction Generale des Impots, Royaume du Maroc). Ne pas inventer l'ICE.",
        inputSchema = obj(
            properties = mapOf(
                "numero" to str("Numero de l'attestation (format typique 'XXXX/YYYY/NNN' ex: '2140/2026/798'). Apparait apres 'N°' en en-tete.", nullable = false),
                "dateEdition" to str("Date d'edition de l'attestation au format ISO YYYY-MM-DD. Apparait pres de 'Edite le' ou 'Fait a ... le'.", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "raisonSociale" to str("Raison sociale du contribuable concerne par l'attestation. Jamais 'DGI' ni 'Royaume du Maroc'.", nullable = false),
                "identifiantFiscal" to str("IF du contribuable (6 a 10 chiffres)."),
                "ice" to str("ICE du contribuable : EXACTEMENT 15 chiffres. Si OCR incomplet apres normalisation, null + warning.", pattern = "^\\d{15}$"),
                "rc" to str("Numero de Registre de Commerce du contribuable."),
                "estEnRegle" to bool("true si le texte mentionne explicitement 'en situation reguliere', 'quitus fiscal', 'regulier'. false si 'non en regle', 'redressement', 'dette'. null + warning si ambigu."),
                "typeAttestation" to enumField(
                    listOf("REGULARITE_FISCALE", "ATTESTATION_PAIEMENT", "CNSS", "AUTRE"),
                    description = "Type d'attestation : REGULARITE_FISCALE = la plus courante DGI 'attestation de regularite fiscale' (R18 6mo B2B / 3mo marche public). ATTESTATION_PAIEMENT = paiement d'un impot specifique (TVA, IS), validite limitee a la quittance citee. CNSS = regularite sociale (CNSS, hors DGI). AUTRE si format atypique. Distinguer ces types evite d'appliquer R18 a une attestation qui n'a pas la meme regle.",
                    baseType = "string"
                ),
                "codeVerification" to str("Code de verification imprime sous le QR code (apres 'attestation.tax.gov.ma'). 12-32 caracteres hexadecimaux (ex: '18a50bf6baf372bd'). Retourner sans espace ni ponctuation.")
            ) + qualityFields(),
            required = listOf("numero", "dateEdition", "raisonSociale", "_confidence")
        )
    )

    val CHECKLIST_AUTOCONTROLE = ToolSchema(
        name = "extract_checklist_autocontrole_data",
        description = "Extrait les donnees d'une check-list d'autocontrole CCF-EN-04",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str(),
                "nomProjet" to str(),
                "referenceFacture" to str(nullable = false),
                "prestataire" to str(nullable = false),
                "montantFacture" to num(minimum = 0),
                "referenceBc" to str(),
                "points" to arrayOf(obj(
                    properties = mapOf(
                        "numero" to num(nullable = false),
                        "description" to str(nullable = false),
                        "estValide" to bool(),
                        "observation" to str()
                    ),
                    required = listOf("numero", "description")
                ), description = "Points de controle"),
                "signataires" to arrayOf(obj(
                    properties = mapOf(
                        "nom" to str(nullable = false),
                        "fonction" to str(),
                        "date" to str(pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                        "aSignature" to bool()
                    ),
                    required = listOf("nom")
                )),
                "dateEtablissement" to str(pattern = "^\\d{4}-\\d{2}-\\d{2}$")
            ) + qualityFields(),
            required = listOf("referenceFacture", "prestataire", "points", "_confidence")
        )
    )

    val TABLEAU_CONTROLE = ToolSchema(
        name = "extract_tableau_controle_data",
        description = "Extrait les donnees d'un tableau de controle financier MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "societeGeree" to str(),
                "referenceFacture" to str(nullable = false),
                "fournisseur" to str(nullable = false),
                "objetDepense" to str(),
                "montantTTC" to num(minimum = 0),
                "points" to arrayOf(obj(
                    properties = mapOf(
                        "numero" to num(nullable = false),
                        "description" to str(nullable = false),
                        "observation" to enumField(
                            listOf("Conforme", "NA", "Non conforme"),
                            baseType = "string"
                        ),
                        "commentaire" to str()
                    ),
                    required = listOf("numero", "description")
                )),
                "signataire" to str(),
                "dateControle" to str(pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "conclusionGenerale" to str()
            ) + qualityFields(),
            required = listOf("referenceFacture", "fournisseur", "points", "_confidence")
        )
    )

    val CHECKLIST_PIECES = ToolSchema(
        name = "extract_checklist_pieces_data",
        description = "Extrait les donnees d'une check-list de pieces justificatives CCF-EN-01",
        inputSchema = obj(
            properties = mapOf(
                "dateEtablissement" to str(pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
                "fournisseur" to str(),
                "referenceFacture" to str(nullable = false),
                "typeDossier" to enumField(
                    listOf("BC", "CONTRACTUEL"),
                    baseType = "string"
                ),
                "pieces" to arrayOf(obj(
                    properties = mapOf(
                        "designation" to str(nullable = false),
                        "original" to bool(),
                        "estPresent" to bool(),
                        "observation" to str()
                    ),
                    required = listOf("designation")
                )),
                "signataire" to str()
            ) + qualityFields(),
            required = listOf("referenceFacture", "pieces", "_confidence")
        )
    )

    // =====================================================================
    // COUCHE ENGAGEMENT : marches publics et contrats cadres marocains.
    // Cadre legal : decret 2-12-349 (marches publics), art. 5 (BC cadres),
    // CCAG-T (travaux) et CCAG-EMO (etudes et maitrise d'oeuvre).
    // =====================================================================

    val MARCHE = ToolSchema(
        name = "extract_marche_data",
        description = "Extrait les donnees d'un marche public marocain issu d'un appel d'offres (decret 2-12-349, CCAG-T ou CCAG-EMO). Ne pas inventer.",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str("Numero/reference du marche (ex: 'M-2024-001', 'AO/2025/15/MADAEF').", nullable = false),
                "objet" to str("Description des travaux/fournitures/services objet du marche (ex: 'Travaux d'entretien du golf royal', 'Fourniture de materiel informatique').", nullable = false),
                "fournisseur" to str("Raison sociale du titulaire/adjudicataire (entite ayant remporte l'AO). JAMAIS MADAEF.", nullable = false),
                "montantHt" to num("Montant total HT du marche, strictement positif.", minimum = 0),
                "montantTva" to num("Montant total TVA du marche.", minimum = 0),
                "tauxTva" to tauxTvaMaroc(),
                "montantTtc" to num("Montant total TTC du marche. Verifier HT+TVA ≈ TTC.", minimum = 0, nullable = false),
                "dateDocument" to dateIso("Date du document marche au format ISO YYYY-MM-DD.", nullable = false),
                "dateSignature" to dateIso("Date de signature du marche YYYY-MM-DD. null si non signe."),
                "dateNotification" to dateIso("Date de notification du marche au titulaire YYYY-MM-DD."),
                "numeroAo" to str("Numero de l'appel d'offres ayant abouti au marche (ex: 'AO 2024/15')."),
                "dateAo" to dateIso("Date d'ouverture des plis de l'AO YYYY-MM-DD."),
                "categorie" to enumField(
                    listOf("TRAVAUX", "FOURNITURES", "SERVICES"),
                    description = "Categorie du marche. TRAVAUX (BTP, voirie, genie civil), FOURNITURES (materiel, consommables), SERVICES (etudes, maintenance, gardiennage, entretien).",
                    baseType = "string"
                ),
                "delaiExecutionMois" to num("Duree d'execution prevue en mois (convertir si necessaire : 'une annee' -> 12, '6 mois' -> 6).", minimum = 0),
                "retenueGarantiePct" to num("Taux de retenue de garantie en % (souvent 7% pour travaux, 10% max). Liberee apres reception definitive.", minimum = 0),
                "cautionDefinitivePct" to num("Taux de caution definitive en % (souvent 3%).", minimum = 0),
                "penalitesRetardJourPct" to num("Taux de penalite journaliere en % du montant du marche. '1/1000 par jour' -> 0.001, '1% par jour' -> 0.01.", minimum = 0),
                "revisionPrixAutorisee" to bool("true si le CPS contient une clause/formule de revision de prix (indice ICG pour BTP, IPC...). false si prix fermes definitifs.")
            ) + qualityFields(),
            required = listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument", "_confidence")
        )
    )

    val BON_COMMANDE_CADRE = ToolSchema(
        name = "extract_bon_commande_cadre_data",
        description = "Extrait les donnees d'un BC cadre marocain (pluri-annuel avec plafond, art. 5 decret 2-12-349). Distinct d'un BC operationnel.",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str("Reference du BC cadre (ex: 'BCC-2024-001').", nullable = false),
                "objet" to str("Description generale des fournitures/prestations couvertes par le BC cadre.", nullable = false),
                "fournisseur" to str("Raison sociale du titulaire du BC cadre.", nullable = false),
                "montantHt" to num("Montant HT estimatif ou plafond HT du BC cadre.", minimum = 0),
                "montantTva" to num("Montant TVA estimatif.", minimum = 0),
                "tauxTva" to tauxTvaMaroc(),
                "montantTtc" to num("Montant TTC estimatif ou plafond TTC.", minimum = 0, nullable = false),
                "dateDocument" to dateIso("Date du BC cadre YYYY-MM-DD.", nullable = false),
                "dateSignature" to dateIso("Date de signature YYYY-MM-DD."),
                "dateNotification" to dateIso("Date de notification YYYY-MM-DD."),
                "plafondMontant" to num("Plafond maximum de consommation autorise sur la duree du BC (ex: 500 000 MAD HT).", minimum = 0),
                "dateValiditeFin" to dateIso("Derniere date de tirage possible YYYY-MM-DD. Au-dela, le BC cadre est expire."),
                "seuilAntiFractionnement" to num("Seuil anti-fractionnement art. 88 decret (souvent 200 000 MAD HT). null si non mentionne.", minimum = 0)
            ) + qualityFields(),
            required = listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument", "_confidence")
        )
    )

    val CONTRAT_CADRE = ToolSchema(
        name = "extract_contrat_cadre_data",
        description = "Extrait les donnees d'un contrat cadre marocain (prestation recurrente : maintenance, abonnement, assurance).",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str("Numero du contrat cadre (ex: 'CM-2024-015').", nullable = false),
                "objet" to str("Description de la prestation recurrente (ex: 'Contrat de maintenance climatisation', 'Abonnement internet fibre').", nullable = false),
                "fournisseur" to str("Raison sociale du prestataire.", nullable = false),
                "montantHt" to num("Montant HT (par periode ou total contractuel, selon le contrat).", minimum = 0),
                "montantTva" to num("Montant TVA.", minimum = 0),
                "tauxTva" to tauxTvaMaroc(),
                "montantTtc" to num("Montant TTC.", minimum = 0, nullable = false),
                "dateDocument" to dateIso("Date du document contrat YYYY-MM-DD.", nullable = false),
                "dateSignature" to dateIso("Date de signature du contrat YYYY-MM-DD."),
                "dateDebut" to dateIso("Date de debut d'execution YYYY-MM-DD."),
                "dateFin" to dateIso("Date de fin contractuelle YYYY-MM-DD (hors reconduction)."),
                "periodicite" to enumField(
                    listOf("MENSUEL", "TRIMESTRIEL", "SEMESTRIEL", "ANNUEL"),
                    description = "Periodicite de facturation. Deduire de la clause de facturation : 'mensuellement' -> MENSUEL, 'trimestre' -> TRIMESTRIEL, etc.",
                    baseType = "string"
                ),
                "reconductionTacite" to bool("true si clause de reconduction tacite explicite ('reconduction tacite', 'reconduit automatiquement'). false si non ou silence. null si ambigu."),
                "preavisResiliationJours" to num("Preavis de resiliation en jours ('un mois' -> 30, '3 mois' -> 90, '15 jours' -> 15).", minimum = 0),
                "indiceRevision" to str("Nom de l'indice de revision tarifaire si present (ex: 'IPC national', 'indice ICG'). null si pas de clause de revision.")
            ) + qualityFields(),
            required = listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument", "_confidence")
        )
    )

    /**
     * Schema `evaluate_custom_rules_batch` pour l'evaluation groupee de regles
     * personnalisees CUSTOM-XX en mode tool_use. Force Claude a renvoyer un
     * tableau de verdicts bien types — supprime la principale source d'erreur
     * "Reponse IA non JSON" sur le batch (parse fragile en mode texte libre).
     *
     * Chaque entree du tableau correspond a une regle. Les codes references
     * sont contraints par le prompt (CUSTOM-XX) ; le schema force la presence
     * du code, du statut, et du flag needsMoreInfo. evidences/questions/detail
     * restent optionnels pour preserver la flexibilite metier.
     */
    /**
     * Schema `verify_critical_identifiers` pour la self-consistency. Second
     * appel Claude qui ne lit que les identifiants reglementaires (ICE/RIB/IF)
     * et permet de comparer avec l'extraction principale. Une divergence
     * signale une hallucination que le grounding "10 derniers chiffres"
     * peut laisser passer (chiffre invente au milieu du token).
     *
     * Tous les champs sont nullable : si Claude ne trouve pas l'identifiant
     * dans l'OCR il met null + warning, ce qui est attendu et non-bloquant.
     * La discordance se mesure par diff valeur, pas par presence/absence.
     */
    val IDENTIFIER_VERIFICATION = ToolSchema(
        name = "verify_critical_identifiers",
        description = "Re-extrait UNIQUEMENT les identifiants reglementaires (ICE/RIB/IF) du document pour controle croise. Mettre null si l'identifiant n'apparait pas clairement dans le texte OCR. JAMAIS inventer.",
        inputSchema = obj(
            properties = mapOf(
                "ice" to ice("ICE 15 chiffres apres normalisation OCR (O->0, l->1). null si absent / illisible. JAMAIS inventer."),
                "rib" to rib("RIB 24 chiffres apres normalisation OCR. null si absent / illisible. JAMAIS inventer."),
                "identifiantFiscal" to str(
                    "IF 5-15 chiffres. null si absent. JAMAIS inventer.",
                    pattern = "^\\d{5,15}$"
                )
            ) + qualityFields(),
            required = listOf("_confidence")
        )
    )

    val CUSTOM_RULES_BATCH = ToolSchema(
        name = "evaluate_custom_rules_batch",
        description = "Retourne un verdict pour chaque regle CUSTOM-XX evaluee contre le dossier. Une entree par regle, code exact.",
        inputSchema = obj(
            properties = mapOf(
                "verdicts" to arrayOf(obj(
                    properties = mapOf(
                        "code" to str("Code exact de la regle, ex: CUSTOM-01.", nullable = false),
                        "statut" to enumField(
                            listOf("CONFORME", "NON_CONFORME", "AVERTISSEMENT", "NON_APPLICABLE"),
                            description = "Verdict du controleur. AVERTISSEMENT si doute raisonnable, NON_APPLICABLE si la regle ne s'applique pas aux documents presents.",
                            nullable = false,
                            baseType = "string"
                        ),
                        "detail" to str("Explication courte (<= 300 caracteres) factuelle, citant les valeurs observees."),
                        "evidences" to arrayOf(obj(
                            properties = mapOf(
                                "role" to enumField(
                                    listOf("attendu", "trouve", "source", "calcule"),
                                    nullable = false, baseType = "string"
                                ),
                                "champ" to str(nullable = false),
                                "libelle" to str(),
                                "documentId" to str(),
                                "documentType" to str(),
                                "valeur" to str()
                            ),
                            required = listOf("role", "champ")
                        )),
                        "documentIds" to arrayOf(str(nullable = false), description = "IDs des documents impliques dans le verdict."),
                        "needsMoreInfo" to bool("true si la regle requiert des donnees absentes du dossier (-> NON_APPLICABLE).", nullable = false),
                        "questions" to arrayOf(str(nullable = false), description = "Champs/donnees manquants si needsMoreInfo=true.")
                    ),
                    required = listOf("code", "statut", "needsMoreInfo")
                ), description = "Un verdict par regle, code exact CUSTOM-XX.")
            ),
            required = listOf("verdicts")
        )
    )

    private val ALL: Map<TypeDocument, ToolSchema> = mapOf(
        TypeDocument.FACTURE to FACTURE,
        TypeDocument.BON_COMMANDE to BON_COMMANDE,
        TypeDocument.ORDRE_PAIEMENT to ORDRE_PAIEMENT,
        TypeDocument.CONTRAT_AVENANT to CONTRAT_AVENANT,
        TypeDocument.PV_RECEPTION to PV_RECEPTION,
        TypeDocument.ATTESTATION_FISCALE to ATTESTATION_FISCALE,
        TypeDocument.CHECKLIST_AUTOCONTROLE to CHECKLIST_AUTOCONTROLE,
        TypeDocument.CHECKLIST_PIECES to CHECKLIST_PIECES,
        TypeDocument.TABLEAU_CONTROLE to TABLEAU_CONTROLE,
        TypeDocument.MARCHE to MARCHE,
        TypeDocument.BON_COMMANDE_CADRE to BON_COMMANDE_CADRE,
        TypeDocument.CONTRAT_CADRE to CONTRAT_CADRE
    )

    fun forType(type: TypeDocument): ToolSchema? = ALL[type]
}
