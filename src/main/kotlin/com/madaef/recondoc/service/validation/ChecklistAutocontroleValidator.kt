package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.ChecklistAutocontrole
import com.madaef.recondoc.entity.dossier.PointControle
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.dossier.TypeDocument
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Implementation des sous-regles R12.01..R12.10 (checklist d'autocontrole
 * CCF-EN-04). Extrait de [ValidationEngine] : la logique est purement
 * deterministe et ne depend que du [ValidationContext] et du mapping
 * "numero de point -> documents source", donc pas besoin d'injection Spring.
 *
 * La signature et la semantique sont strictement identiques a l'ancienne
 * methode privee : meme ordre des points, memes evidences, meme mergeStatut
 * (qui combine le verdict systeme avec l'autocontrole humain quand il est
 * present). Les golden tests valident la stabilite des verdicts.
 *
 * Les helpers utilises (`docAmount`, `docStr`, `evidence`, `matchReference`,
 * `normalizeRib`, `parseBooleanish`, `mergeStatut`) sont des fonctions
 * top-level du meme package, declarees dans `ValidationHelpers.kt`.
 */
internal fun executeChecklistPoints(
    ctx: ValidationContext,
    ckDocMapping: Map<Int, List<TypeDocument>>
): List<ResultatValidation> {
    val (dossier, facture, _, bc, op, contrat, pv, arf, checklist, _, tol) = ctx
    val ckResults = mutableListOf<ResultatValidation>()

    fun docIds(num: Int): String? {
        val types = ckDocMapping[num] ?: return null
        return dossier.documents.filter { it.typeDocument in types }
            .mapNotNull { it.id?.toString() }.joinToString(",").ifBlank { null }
    }

    fun ckPoint(pt: PointControle?): Pair<Boolean?, String?> =
        if (pt != null) (pt.estValide to pt.observation) else (null to null)

    val fDoc = facture?.document
    val bcDoc = bc?.document
    val opDoc = op?.document

    val fTtc = facture?.montantTtc ?: docAmount(fDoc, "montantTTC")
    val fHt = facture?.montantHt ?: docAmount(fDoc, "montantHT")
    val fTva = facture?.montantTva ?: docAmount(fDoc, "montantTVA")
    val bcTtc = bc?.montantTtc ?: docAmount(bcDoc, "montantTTC")

    val checklistDoc = dossier.documents.find { it.typeDocument == TypeDocument.CHECKLIST_AUTOCONTROLE }
    val entityPoints = checklist?.points?.sortedBy { it.numero } ?: emptyList()

    val points: List<PointControle> = if (entityPoints.isNotEmpty()) {
        entityPoints
    } else {
        @Suppress("UNCHECKED_CAST")
        val jsonPoints = checklistDoc?.donneesExtraites?.get("points") as? List<Map<String, Any?>> ?: emptyList()
        jsonPoints.mapNotNull { p ->
            val num = (p["numero"] as? Number)?.toInt() ?: return@mapNotNull null
            PointControle(
                checklist = checklist ?: ChecklistAutocontrole(dossier = dossier, document = checklistDoc ?: fDoc ?: dossier.documents.first()),
                numero = num,
                description = p["description"] as? String,
                estValide = parseBooleanish(p["estValide"]),
                observation = p["observation"] as? String
            )
        }
    }

    run {
        val pt = points.find { it.numero == 1 }
        val (ckValide, obs) = ckPoint(pt)
        val hasBcOrContrat = bc != null || contrat != null
        val hasFacture = facture != null
        val montantMatch = when {
            facture == null -> false
            bc != null -> fTtc != null && bcTtc != null &&
                fTtc.subtract(bcTtc).abs() <= tol
            contrat != null && contrat.grillesTarifaires.isNotEmpty() -> true
            else -> false
        }
        val sysStatut = when {
            !hasFacture || !hasBcOrContrat -> StatutCheck.AVERTISSEMENT
            montantMatch -> StatutCheck.CONFORME
            else -> StatutCheck.NON_CONFORME
        }
        val detail = buildString {
            if (!hasFacture) append("Facture manquante. ")
            if (!hasBcOrContrat) append("BC/Contrat manquant. ")
            if (hasFacture && hasBcOrContrat) {
                if (montantMatch) append("Montants concordants. ")
                else append("Montants discordants (facture vs BC/contrat). ")
            }
            if (obs != null) append("Autocontrole: $obs")
        }
        val finalStatut = mergeStatut(sysStatut, ckValide)
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.01",
            libelle = pt?.description ?: "Concordance facture / modalites contractuelles / livrables",
            statut = finalStatut, detail = detail.trim(),
            source = "CHECKLIST",
            valeurAttendue = if (bc != null) "BC: ${bcTtc?.toPlainString()}" else contrat?.referenceContrat,
            valeurTrouvee = fTtc?.toPlainString(),
            documentIds = docIds(1),
            evidences = listOfNotNull(
                fTtc?.let { evidence("trouve", "montantTTC", "TTC de la facture", fDoc, it) },
                bcTtc?.let { evidence("attendu", "montantTTC", "TTC du bon de commande", bcDoc, it) },
                contrat?.referenceContrat?.let { evidence("source", "referenceContrat", "Reference du contrat", contrat.document, it) }
            ).ifEmpty { null }
        )
    }

    // CK02: Verification arithmetique des montants
    run {
        val pt = points.find { it.numero == 2 }
        val (ckValide, obs) = ckPoint(pt)
        val htOk = fHt != null && fTva != null && fTtc != null &&
            fHt.add(fTva).subtract(fTtc).abs() <= tol
        val sysStatut = when {
            facture == null -> StatutCheck.AVERTISSEMENT
            htOk -> StatutCheck.CONFORME
            fHt == null || fTva == null || fTtc == null -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        val detail = if (htOk) "HT + TVA = TTC verifie" else if (facture == null) "Facture manquante" else
            "HT(${fHt}) + TVA(${fTva}) != TTC(${fTtc})"
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.02",
            libelle = pt?.description ?: "Verification arithmetique des montants",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(detail, obs?.let { "Autocontrole: $it" }).joinToString(". "),
            source = "CHECKLIST",
            valeurAttendue = if (fHt != null && fTva != null) "${fHt} + ${fTva}" else null,
            valeurTrouvee = fTtc?.toPlainString(),
            documentIds = docIds(2),
            evidences = listOfNotNull(
                fHt?.let { evidence("source", "montantHT", "HT de la facture", fDoc, it) },
                fTva?.let { evidence("source", "montantTVA", "TVA de la facture", fDoc, it) },
                fTtc?.let { evidence("trouve", "montantTTC", "TTC de la facture", fDoc, it) }
            ).ifEmpty { null }
        )
    }

    // CK03: Respect du delai d'execution
    run {
        val pt = points.find { it.numero == 3 }
        val (ckValide, obs) = ckPoint(pt)
        val dateBcContrat = bc?.dateBc ?: contrat?.dateSignature
        val dateFactureVal = facture?.dateFacture
        val sysStatut = when {
            dateBcContrat == null || dateFactureVal == null -> StatutCheck.AVERTISSEMENT
            !dateFactureVal.isBefore(dateBcContrat) -> StatutCheck.CONFORME
            else -> StatutCheck.NON_CONFORME
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.03",
            libelle = pt?.description ?: "Respect du delai d'execution",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                if (dateBcContrat != null && dateFactureVal != null) "BC/Contrat: $dateBcContrat, Facture: $dateFactureVal" else "Dates manquantes",
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            valeurAttendue = dateBcContrat?.toString(),
            valeurTrouvee = dateFactureVal?.toString(),
            documentIds = docIds(3)
        )
    }

    // CK04: Modifications / avenants
    run {
        val pt = points.find { it.numero == 4 }
        val (ckValide, obs) = ckPoint(pt)
        val hasAvenant = contrat?.numeroAvenant != null
        val sysStatut = if (hasAvenant) StatutCheck.AVERTISSEMENT else StatutCheck.CONFORME
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.04",
            libelle = pt?.description ?: "Modifications / avenants (plafonds et variations)",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                if (hasAvenant) "Avenant detecte: ${contrat?.numeroAvenant}" else "Aucun avenant detecte",
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            documentIds = docIds(4)
        )
    }

    // CK05: Retenues de garantie et penalites
    run {
        val pt = points.find { it.numero == 5 }
        val (ckValide, obs) = ckPoint(pt)
        val hasRetenues = op != null && op.retenues.isNotEmpty()
        val retenuesOk = if (hasRetenues) {
            op!!.retenues.all { it.base != null && it.taux != null && it.montant != null &&
                it.base!!.multiply(it.taux).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                    .subtract(it.montant).abs() <= tol }
        } else true
        val sysStatut = when {
            op == null -> StatutCheck.AVERTISSEMENT
            !hasRetenues -> StatutCheck.CONFORME
            retenuesOk -> StatutCheck.CONFORME
            else -> StatutCheck.NON_CONFORME
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.05",
            libelle = pt?.description ?: "Retenues de garantie et penalites de retard",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                if (hasRetenues) "${op!!.retenues.size} retenue(s) detectee(s), calcul ${if (retenuesOk) "correct" else "incorrect"}"
                else "Aucune retenue",
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            documentIds = docIds(5)
        )
    }

    // CK06: Signatures et visas
    run {
        val pt = points.find { it.numero == 6 }
        val (ckValide, obs) = ckPoint(pt)
        val pvSigne = pv != null && (pv.signataireMadaef != null || pv.signataireFournisseur != null)
        val bcSigne = bc?.signataire != null
        val sysStatut = when {
            ckValide == true -> StatutCheck.CONFORME
            ckValide == false -> StatutCheck.NON_CONFORME
            pvSigne || bcSigne -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.AVERTISSEMENT
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.06",
            libelle = pt?.description ?: "Signatures et visas des personnes habilitees",
            statut = sysStatut,
            detail = listOfNotNull(
                if (pvSigne) "PV signe" else null,
                if (bcSigne) "BC signe par ${bc?.signataire}" else null,
                if (!pvSigne && !bcSigne) "Aucune signature detectee" else null,
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            documentIds = docIds(6)
        )
    }

    // CK07: Conformite reglementaire (ICE, IF, RC, CNSS)
    run {
        val pt = points.find { it.numero == 7 }
        val (ckValide, obs) = ckPoint(pt)
        val fIce = facture?.ice ?: docStr(fDoc, "ice")
        val fIf = facture?.identifiantFiscal ?: docStr(fDoc, "identifiantFiscal")
        val fRc = facture?.rc ?: docStr(fDoc, "rc")
        val iceOk = fIce?.isNotBlank() == true
        val ifOk = fIf?.isNotBlank() == true
        val rcOk = fRc?.isNotBlank() == true
        val arfOk = arf?.estEnRegle == true || docStr(arf?.document, "estEnRegle") == "true"
        val nbPresents = listOf(iceOk, ifOk, rcOk).count { it }
        val sysStatut = when {
            facture == null -> StatutCheck.AVERTISSEMENT
            nbPresents == 3 && arfOk -> StatutCheck.CONFORME
            nbPresents >= 2 -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.07",
            libelle = pt?.description ?: "Conformite reglementaire de la facture",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                "ICE: ${if (iceOk) fIce else "absent"}",
                "IF: ${if (ifOk) fIf else "absent"}",
                "RC: ${if (rcOk) fRc else "absent"}",
                if (arf != null) "Attestation: ${if (arfOk) "en regle" else "non conforme"}" else null,
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            valeurAttendue = "ICE + IF + RC presents",
            valeurTrouvee = "$nbPresents/3 identifiants",
            documentIds = docIds(7)
        )
    }

    // CK08: Conformite RIB contractuel vs facture
    run {
        val pt = points.find { it.numero == 8 }
        val (ckValide, obs) = ckPoint(pt)
        val factureRib = normalizeRib(facture?.rib ?: docStr(fDoc, "rib"))
        val opRib = normalizeRib(op?.rib ?: docStr(opDoc, "rib"))
        val sysStatut = when {
            factureRib == null || opRib == null -> StatutCheck.AVERTISSEMENT
            factureRib == opRib -> StatutCheck.CONFORME
            else -> StatutCheck.NON_CONFORME
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.08",
            libelle = pt?.description ?: "Conformite du RIB contractuel vs facture",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                "RIB Facture: ${factureRib ?: "absent"}, RIB OP: ${opRib ?: "absent"}",
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            valeurAttendue = opRib,
            valeurTrouvee = factureRib,
            documentIds = docIds(8)
        )
    }

    // CK09: Conformite BL / PV de reception
    run {
        val pt = points.find { it.numero == 9 }
        val (ckValide, obs) = ckPoint(pt)
        val hasPv = pv != null
        val pvRefMatch = if (pv?.referenceContrat != null && contrat?.referenceContrat != null)
            matchReference(pv.referenceContrat, contrat.referenceContrat) else null
        val sysStatut = when {
            !hasPv -> StatutCheck.AVERTISSEMENT
            pvRefMatch == true -> StatutCheck.CONFORME
            pvRefMatch == false -> StatutCheck.NON_CONFORME
            else -> StatutCheck.AVERTISSEMENT
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.09",
            libelle = pt?.description ?: "Conformite BL / PV de reception",
            statut = mergeStatut(sysStatut, ckValide),
            detail = listOfNotNull(
                if (hasPv) "PV present" else "PV manquant",
                if (pvRefMatch != null) "Reference ${if (pvRefMatch) "coherente" else "incoherente"}" else null,
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            documentIds = docIds(9)
        )
    }

    // CK10: Habilitations des signataires des receptions
    run {
        val pt = points.find { it.numero == 10 }
        val (ckValide, obs) = ckPoint(pt)
        val hasPvSignataires = pv != null && (pv.signataireMadaef != null || pv.signataireFournisseur != null)
        val sysStatut = when {
            ckValide == true -> StatutCheck.CONFORME
            ckValide == false -> StatutCheck.NON_CONFORME
            hasPvSignataires -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.AVERTISSEMENT
        }
        ckResults += ResultatValidation(
            dossier = dossier, regle = "R12.10",
            libelle = pt?.description ?: "Habilitations des signataires des receptions",
            statut = sysStatut,
            detail = listOfNotNull(
                if (hasPvSignataires) "Signataires: ${listOfNotNull(pv?.signataireMadaef, pv?.signataireFournisseur).joinToString(", ")}"
                else "Aucun signataire detecte dans le PV",
                obs?.let { "Autocontrole: $it" }
            ).joinToString(". "),
            source = "CHECKLIST",
            documentIds = docIds(10)
        )
    }

    return ckResults
}
