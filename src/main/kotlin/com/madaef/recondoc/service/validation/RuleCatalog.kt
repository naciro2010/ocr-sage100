package com.madaef.recondoc.service.validation

import com.madaef.recondoc.dto.dossier.RuleCatalogEntry

/**
 * Single source of truth for rule metadata exposed via API.
 * Frontend pulls this list instead of duplicating labels/descriptions.
 */
object RuleCatalog {

    private data class Def(
        val code: String, val libelle: String, val description: String,
        val groupe: String, val categorie: String = "system",
        val appliesToBC: Boolean = true, val appliesToContractuel: Boolean = true
    )

    private val DEFS: List<Def> = listOf(
        // === Couche Engagement (R-E communes + R-M Marche + R-B BC + R-C Contrat) ===
        Def("R-E01", "Plafond engagement respecte", "Somme des dossiers rattaches <= plafond engagement (tolerance 2%)", "engagement", categorie = "engagement"),
        Def("R-E02", "Coherence fournisseur engagement", "Fournisseur engagement = fournisseur dossier (matching canonique)", "engagement", categorie = "engagement"),
        Def("R-E03", "Engagement actif", "L'engagement est ACTIF au moment du paiement", "engagement", categorie = "engagement"),
        Def("R-E04", "Reference engagement citee", "La reference engagement est citee dans l'OP ou la facture", "engagement", categorie = "engagement"),
        Def("R-E05", "Rattachement autorise", "Aucun rattachement possible si l'engagement est CLOTURE", "engagement", categorie = "engagement"),

        Def("R-M01", "Delai d'execution marche", "Date facture/decompte dans [dateNotification ; +delaiExecution]", "engagement-marche", categorie = "engagement"),
        Def("R-M02", "Retenue de garantie", "Retenue de garantie appliquee au taux du marche", "engagement-marche", categorie = "engagement"),
        Def("R-M03", "Penalites de retard", "Penalites de retard calculees sur les jours de depassement", "engagement-marche", categorie = "engagement"),
        Def("R-M04", "Numero AO cite", "Numero de l'appel d'offres cite dans le dossier", "engagement-marche", categorie = "engagement"),
        Def("R-M05", "Revision de prix respectee", "Revision de prix conforme au CPS", "engagement-marche", categorie = "engagement"),
        Def("R-M06", "Chronologie des decomptes", "Decomptes ordonnes chronologiquement", "engagement-marche", categorie = "engagement"),
        Def("R-M07", "Caution definitive", "Caution definitive mentionnee si taux > 0", "engagement-marche", categorie = "engagement"),

        Def("R-B01", "Validite BC", "Date facture <= dateValiditeFin du BC", "engagement-bc", categorie = "engagement"),
        Def("R-B02", "Anti-fractionnement", "Cumul BC fournisseur 12 mois <= seuil legal (art. 88)", "engagement-bc", categorie = "engagement"),
        Def("R-B03", "Une livraison par dossier", "Un dossier = une livraison (alerte si factures multiples)", "engagement-bc", categorie = "engagement"),
        Def("R-B04", "Pas de garantie sur BC", "Pas de retenue de garantie attendue (reservee aux marches)", "engagement-bc", categorie = "engagement"),

        Def("R-C01", "Periodicite respectee", "Intervalle entre paiements conforme a la periodicite (± 20%)", "engagement-contrat", categorie = "engagement"),
        Def("R-C02", "Duree contrat respectee", "Pas de paiement apres dateFin sauf reconduction tacite", "engagement-contrat", categorie = "engagement"),
        Def("R-C03", "Nombre paiements coherent", "Nombre de dossiers <= duree/periodicite", "engagement-contrat", categorie = "engagement"),
        Def("R-C04", "Revision tarifaire", "Variation de prix <= indice contractuel", "engagement-contrat", categorie = "engagement"),
        Def("R-C05", "Montant coherent echeancier", "Montant facture ± 5% du montant unitaire prevu", "engagement-contrat", categorie = "engagement"),

        // === Couche Dossier (R01-R22, existantes) ===
        Def("R20", "Completude du dossier", "Verifie que toutes les pieces obligatoires sont presentes", "completude"),
        Def("R16", "Verification arithmetique HT+TVA=TTC", "Verifie que HT + TVA = TTC sur la facture", "montants"),
        Def("R01", "Concordance montant TTC", "Compare le TTC de la facture avec le BC", "montants", appliesToContractuel = false),
        Def("R02", "Concordance montant HT", "Compare le HT de la facture avec le BC", "montants", appliesToContractuel = false),
        Def("R03", "Concordance TVA", "Compare la TVA de la facture avec le BC", "montants", appliesToContractuel = false),
        Def("R03b", "Coherence taux TVA", "Detecte un taux de TVA different entre facture et BC", "montants", appliesToContractuel = false),
        Def("R04", "Montant OP = TTC (sans retenues)", "Verifie que le montant de l'OP correspond au TTC de la facture", "montants"),
        Def("R05", "Montant OP = TTC - retenues", "Verifie le montant OP apres deduction des retenues a la source", "montants"),
        Def("R06", "Calcul arithmetique des retenues", "Verifie que base × taux = montant retenue", "montants"),
        Def("R15", "Grille tarifaire × duree", "Somme des prix mensuels de l'avenant × nombre de mois = HT facture", "montants", appliesToBC = false),
        Def("R07", "Reference facture dans l'OP", "Verifie que le numero de facture est cite dans l'ordre de paiement", "references"),
        Def("R08", "Reference BC/contrat dans l'OP", "Verifie que le numero de BC ou contrat est cite dans l'OP", "references"),
        Def("R09", "Coherence ICE", "Verifie que l'ICE est identique entre facture et attestation fiscale", "identifiants"),
        Def("R09b", "Format ICE (15 chiffres)", "Verifie que l'ICE respecte le format legal OMPIC (decret 2-11-13) : exactement 15 chiffres", "identifiants"),
        Def("R10", "Coherence IF", "Verifie que l'identifiant fiscal est identique entre documents", "identifiants"),
        Def("R11", "Coherence RIB", "Verifie que le RIB de la facture correspond a celui de l'OP", "identifiants"),
        Def("R14", "Coherence fournisseur", "Verifie que le nom du fournisseur est coherent entre tous les documents", "identifiants"),
        Def("R14b", "Attestation fiscale = fournisseur facture", "Non conforme si la raison sociale, l'ICE ou l'IF de l'attestation ne correspondent pas a la facture", "identifiants"),
        Def("R01g", "Matching ligne par ligne facture ↔ BC/contrat", "Apparie chaque ligne de facture avec une ligne du BC (ou grille tarifaire du contrat) et compare quantite, PU HT et montant", "montants"),
        Def("R12", "Checklist autocontrole", "Agregat des 10 points CCF-EN-04", "documents"),
        Def("R13", "Tableau de controle", "Verifie que tous les points du TC sont Conforme ou NA", "documents"),
        Def("R17a", "Chronologie BC/Contrat → Facture", "Verifie que date BC/Contrat <= date facture", "dates"),
        Def("R17b", "Chronologie Facture → OP", "Verifie que date facture <= date OP", "dates"),
        Def("R18", "Validite attestation fiscale", "Verifie l'attestation fiscale (3 mois marche public, 6 mois B2B - Circulaire DGI 717)", "dates"),
        Def("R19", "QR code attestation fiscale", "Scanne le QR, verifie l'origine DGI (attestation.tax.gov.ma) et bloque les QR dangereux (javascript:, http non chiffre, domaine inattendu)", "dates"),
        Def("R23", "Regularite fiscale", "Verifie que l'attestation confirme que la societe est en situation reguliere (champ estEnRegle)", "documents"),
        Def("R24", "Completude lignes facture", "Au-dessus d'un seuil de montant (defaut 50 000 MAD TTC), la facture doit comporter au moins une ligne detaillee. Sinon avertissement — soit l'extraction a manque le tableau, soit la facture est elle-meme peu detaillee pour un montant significatif", "completude"),
        Def("R25", "Delai paiement marche public", "Decret 2-22-431 art. 159 : delai global de paiement <= 60 jours a compter de la constatation du service fait. Specifique aux engagements de type Marche.", "dates"),
        Def("R26", "Plafond paiement especes", "CGI art. 193-ter : tout reglement en especes > 5 000 MAD est non deductible et expose le fournisseur a une amende de 6 %.", "montants"),
        Def("R27", "Devise MAD obligatoire", "CGNC + Loi 9-88 art. 1 : la facture marocaine doit etre libellee en MAD. EUR/USD non conforme sans contre-valeur officielle.", "identifiants"),
        Def("R30", "Taux TVA legal", "CGI 2026 art. 87-100 : seuls les taux 0 / 7 / 10 / 14 / 20 % sont legaux. Tout autre taux signale une erreur d'extraction ou une fraude.", "montants"),
        Def("R06b", "Taux retenue legal", "CGI : TVA marches publics = 75 % (art. 117), IR honoraires = 10 % (art. 73-II-G). Verifie que le taux declare correspond au taux legal applicable au type de retenue.", "montants")
    ) + (1..10).map { i ->
        val num = "%02d".format(i)
        Def("R12.$num", "Point checklist $num", "Controle CK$num de la checklist autocontrole CCF-EN-04", "documents", categorie = "checklist")
    }

    fun all(): List<RuleCatalogEntry> = DEFS.map { d ->
        RuleCatalogEntry(
            code = d.code, libelle = d.libelle, description = d.description,
            groupe = d.groupe, categorie = d.categorie,
            appliesToBC = d.appliesToBC, appliesToContractuel = d.appliesToContractuel,
            dependances = RuleConstants.RULE_DEPENDENCIES[d.code]?.toList() ?: emptyList()
        )
    }

    fun cascade(code: String): List<String> {
        val base = mutableSetOf(code)
        RuleConstants.RULE_DEPENDENCIES[code]?.let { base.addAll(it) }
        if (code == "R12" || code.startsWith("R12.")) {
            base.add("R12")
            for (i in 1..10) base.add("R12.%02d".format(i))
        }
        return base.toList().sorted()
    }
}
