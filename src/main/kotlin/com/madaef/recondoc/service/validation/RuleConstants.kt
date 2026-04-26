package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.TypeDocument

/**
 * Constantes partagees par le moteur de regles, le catalogue expose au
 * frontend (`RuleCatalog`), et la resolution de pieces obligatoires (R20).
 *
 * Extraites de `ValidationEngine.companion object` pour qu'elles puissent
 * etre reutilisees par les futures classes de regles ([ValidationRule])
 * sans dependance circulaire vers le moteur.
 */
object RuleConstants {

    val TYPE_LABELS: Map<TypeDocument, String> = mapOf(
        TypeDocument.FACTURE to "Facture",
        TypeDocument.BON_COMMANDE to "Bon de commande",
        TypeDocument.CONTRAT_AVENANT to "Contrat / Avenant",
        TypeDocument.ORDRE_PAIEMENT to "Ordre de paiement",
        TypeDocument.CHECKLIST_AUTOCONTROLE to "Checklist autocontrole",
        TypeDocument.CHECKLIST_PIECES to "Checklist des pieces",
        TypeDocument.TABLEAU_CONTROLE to "Tableau de controle",
        TypeDocument.PV_RECEPTION to "PV de reception",
        TypeDocument.ATTESTATION_FISCALE to "Attestation fiscale",
        TypeDocument.FORMULAIRE_FOURNISSEUR to "Formulaire fournisseur",
        TypeDocument.INCONNU to "A classer"
    )

    val DEFAULT_REQUIRED_BY_TYPE: Map<DossierType, List<TypeDocument>> = mapOf(
        DossierType.BC to listOf(
            TypeDocument.FACTURE,
            TypeDocument.BON_COMMANDE,
            TypeDocument.CHECKLIST_AUTOCONTROLE,
            TypeDocument.TABLEAU_CONTROLE,
            TypeDocument.ORDRE_PAIEMENT
        ),
        DossierType.CONTRACTUEL to listOf(
            TypeDocument.FACTURE,
            TypeDocument.CONTRAT_AVENANT,
            TypeDocument.PV_RECEPTION,
            TypeDocument.CHECKLIST_AUTOCONTROLE,
            TypeDocument.ORDRE_PAIEMENT
        )
    )

    /**
     * Graphe des dependances entre regles. Si [regle] est modifiee manuellement,
     * toutes les regles de [RULE_DEPENDENCIES][regle] doivent etre relancees
     * pour rester coherentes (p.ex. une correction de montant TTC sur la facture
     * impacte R01, R02, R03, R04, R05, R16...).
     */
    val RULE_DEPENDENCIES: Map<String, Set<String>> = mapOf(
        "R01" to setOf("R02", "R03", "R03b"),
        "R02" to setOf("R01", "R03", "R03b"),
        "R03" to setOf("R01", "R02", "R03b", "R30"),
        "R03b" to setOf("R01", "R02", "R03", "R30"),
        "R04" to setOf("R05", "R16"),
        "R05" to setOf("R04", "R06", "R16"),
        "R06" to setOf("R05", "R06b"),
        "R06b" to setOf("R06"),
        "R07" to setOf("R08"),
        "R08" to setOf("R07"),
        "R09" to setOf("R09b", "R10"),
        "R09b" to setOf("R09"),
        "R10" to setOf("R09", "R09b"),
        "R11" to setOf("R14"),
        "R14" to setOf("R11"),
        "R15" to setOf("R16", "R04"),
        "R16" to setOf("R04", "R05", "R15", "R16b", "R16c"),
        "R16b" to setOf("R16", "R16c"),
        "R16c" to setOf("R16", "R16b", "R01f"),
        "R01f" to setOf("R01", "R02", "R16c", "R01g"),
        "R01g" to setOf("R01", "R02", "R01f", "R15"),
        "R14b" to setOf("R09", "R09b", "R10", "R14"),
        "R17a" to setOf("R17b"),
        "R17b" to setOf("R17a"),
        "R18" to setOf("R23"),
        "R19" to emptySet(),
        "R20" to emptySet(),
        "R21" to emptySet(),
        "R22" to setOf("R25"),
        "R23" to setOf("R18"),
        "R24" to emptySet(),
        "R25" to setOf("R22"),
        "R26" to emptySet(),
        "R27" to emptySet(),
        "R30" to setOf("R03", "R03b"),
        "R12" to emptySet(),
        "R13" to emptySet(),
    )
}
