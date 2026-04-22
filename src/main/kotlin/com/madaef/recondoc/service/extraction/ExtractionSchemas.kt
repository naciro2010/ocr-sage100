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
        description = "Extrait les donnees structurees d'une facture d'achat marocaine MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "numeroFacture" to str("Numero de la facture", nullable = false),
                "dateFacture" to str("Date au format YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "fournisseur" to str("Raison sociale fournisseur", nullable = false),
                "client" to str("Raison sociale client"),
                "ice" to str("ICE 15 chiffres", pattern = "^\\d{15}$"),
                "identifiantFiscal" to str("IF (5 a 15 chiffres)"),
                "rc" to str("Registre commerce"),
                "rib" to str("RIB principal (24 chiffres)", pattern = "^\\d{24}$"),
                "ribs" to arrayOf(str(nullable = false), description = "Tous les RIB trouves"),
                "montantHT" to num("Total HT", minimum = 0, nullable = false),
                "montantTVA" to num("Total TVA", minimum = 0, nullable = false),
                "tauxTVA" to enumField(listOf(0, 7, 10, 14, 20), "Taux TVA dominant", nullable = false),
                "montantTTC" to num("Total TTC", minimum = 0, nullable = false),
                "referenceContrat" to str("Reference contrat ou BC cite"),
                "periode" to str("Periode couverte"),
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
        description = "Extrait les donnees d'un bon de commande MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "reference" to str("Reference BC (ex: CF SIE 2026-XXXX)", nullable = false),
                "dateBc" to str("Date au format YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "fournisseur" to str("Raison sociale fournisseur", nullable = false),
                "objet" to str("Description generale du BC"),
                "montantHT" to num("Total HT", minimum = 0),
                "montantTVA" to num("Total TVA", minimum = 0),
                "tauxTVA" to enumField(listOf(0, 7, 10, 14, 20), "Taux TVA dominant"),
                "montantTTC" to num("Total TTC", minimum = 0, nullable = false),
                "signataire" to str(),
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
        description = "Extrait les donnees d'un ordre de paiement MADAEF",
        inputSchema = obj(
            properties = mapOf(
                "numeroOp" to str("Numero OP", nullable = false),
                "dateEmission" to str("Date au format YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "emetteur" to str(),
                "natureOperation" to str(),
                "description" to str(),
                "beneficiaire" to str("Nom complet beneficiaire", nullable = false),
                "rib" to str("RIB beneficiaire 24 chiffres", pattern = "^\\d{24}$"),
                "ribs" to arrayOf(str(nullable = false)),
                "banque" to str(),
                "montantBrut" to num("Montant avant retenues = TTC facture", minimum = 0),
                "montantOperation" to num("Montant net paye", minimum = 0, nullable = false),
                "referenceFacture" to str(),
                "referenceBcOuContrat" to str(),
                "referenceSage" to str(),
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
        description = "Extrait les donnees d'une attestation de regularite fiscale DGI",
        inputSchema = obj(
            properties = mapOf(
                "numero" to str("Numero attestation", nullable = false),
                "dateEdition" to str("YYYY-MM-DD", pattern = "^\\d{4}-\\d{2}-\\d{2}$", nullable = false),
                "raisonSociale" to str(nullable = false),
                "identifiantFiscal" to str("IF (5-15 chiffres)"),
                "ice" to str("ICE 15 chiffres", pattern = "^\\d{15}$"),
                "rc" to str(),
                "estEnRegle" to bool(),
                "codeVerification" to str("Code sous le QR code (12-32 chars hex)")
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

    private val ALL: Map<TypeDocument, ToolSchema> = mapOf(
        TypeDocument.FACTURE to FACTURE,
        TypeDocument.BON_COMMANDE to BON_COMMANDE,
        TypeDocument.ORDRE_PAIEMENT to ORDRE_PAIEMENT,
        TypeDocument.CONTRAT_AVENANT to CONTRAT_AVENANT,
        TypeDocument.PV_RECEPTION to PV_RECEPTION,
        TypeDocument.ATTESTATION_FISCALE to ATTESTATION_FISCALE,
        TypeDocument.CHECKLIST_AUTOCONTROLE to CHECKLIST_AUTOCONTROLE,
        TypeDocument.CHECKLIST_PIECES to CHECKLIST_PIECES,
        TypeDocument.TABLEAU_CONTROLE to TABLEAU_CONTROLE
    )

    fun forType(type: TypeDocument): ToolSchema? = ALL[type]
}
