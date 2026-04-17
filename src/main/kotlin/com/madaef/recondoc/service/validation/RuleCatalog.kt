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
        Def("R10", "Coherence IF", "Verifie que l'identifiant fiscal est identique entre documents", "identifiants"),
        Def("R11", "Coherence RIB", "Verifie que le RIB de la facture correspond a celui de l'OP", "identifiants"),
        Def("R14", "Coherence fournisseur", "Verifie que le nom du fournisseur est coherent entre tous les documents", "identifiants"),
        Def("R12", "Checklist autocontrole", "Agregat des 10 points CCF-EN-04", "documents"),
        Def("R13", "Tableau de controle", "Verifie que tous les points du TC sont Conforme ou NA", "documents"),
        Def("R17a", "Chronologie BC/Contrat → Facture", "Verifie que date BC/Contrat <= date facture", "dates"),
        Def("R17b", "Chronologie Facture → OP", "Verifie que date facture <= date OP", "dates"),
        Def("R18", "Validite attestation fiscale", "Verifie que l'attestation fiscale a moins de 6 mois", "dates"),
        Def("R19", "QR code attestation fiscale", "Scanne le QR de l'attestation fiscale DGI et verifie qu'il correspond au code imprime", "dates")
    ) + (1..10).map { i ->
        val num = "%02d".format(i)
        Def("R12.$num", "Point checklist $num", "Controle CK$num de la checklist autocontrole CCF-EN-04", "documents", categorie = "checklist")
    }

    fun all(): List<RuleCatalogEntry> = DEFS.map { d ->
        RuleCatalogEntry(
            code = d.code, libelle = d.libelle, description = d.description,
            groupe = d.groupe, categorie = d.categorie,
            appliesToBC = d.appliesToBC, appliesToContractuel = d.appliesToContractuel,
            dependances = ValidationEngine.RULE_DEPENDENCIES[d.code]?.toList() ?: emptyList()
        )
    }

    fun cascade(code: String): List<String> {
        val base = mutableSetOf(code)
        ValidationEngine.RULE_DEPENDENCIES[code]?.let { base.addAll(it) }
        if (code == "R12" || code.startsWith("R12.")) {
            base.add("R12")
            for (i in 1..10) base.add("R12.%02d".format(i))
        }
        return base.toList().sorted()
    }
}
