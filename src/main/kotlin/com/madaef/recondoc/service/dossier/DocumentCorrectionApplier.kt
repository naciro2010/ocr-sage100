package com.madaef.recondoc.service.dossier

import com.madaef.recondoc.entity.dossier.AttestationFiscale
import com.madaef.recondoc.entity.dossier.BonCommande
import com.madaef.recondoc.entity.dossier.ContratAvenant
import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DocumentCorrection
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.Facture
import com.madaef.recondoc.entity.dossier.OrdrePaiement
import com.madaef.recondoc.entity.dossier.PvReception
import com.madaef.recondoc.repository.dossier.DocumentCorrectionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Applique les corrections humaines (DocumentCorrection) sur le dossier en
 * memoire avant chaque evaluation de regle. Met a jour :
 *  - `Document.donneesExtraites` (clef = champ corrige)
 *  - les entites typees rattachees au document (Facture, BC, OP, ARF, PV...)
 *
 * Strategie : les corrections sont declaratives. Pour chaque (typeDocument,
 * champ), on connait le slot a mettre a jour. Inconnu -> on touche
 * uniquement le map donneesExtraites (les regles qui passent par
 * `docAmount` / `docStr` verront la correction quand meme).
 *
 * IMPORTANT : on muse les entites JPA managees ici. Les regles lisent ensuite
 * directement ces entites. La modification est volontaire et auditeable
 * (DocumentCorrection conserve la valeur originale + auteur + date).
 */
@Component
class DocumentCorrectionApplier(
    private val correctionRepository: DocumentCorrectionRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Applique toutes les corrections rattachees aux documents du dossier.
     * Idempotent : appelable plusieurs fois sans effet de bord supplementaire.
     */
    fun apply(dossier: DossierPaiement) {
        val docIds: List<UUID> = dossier.documents.mapNotNull { it.id }
        if (docIds.isEmpty()) return
        val corrections = correctionRepository.findByDocumentIdIn(docIds)
        if (corrections.isEmpty()) return

        val byDoc = corrections.groupBy { it.document.id }
        var applied = 0
        for (doc in dossier.documents) {
            val docId = doc.id ?: continue
            val docCorrections = byDoc[docId] ?: continue
            applyToDocument(dossier, doc, docCorrections)
            applied += docCorrections.size
        }
        log.debug("Applied {} document correction(s) on dossier {}", applied, dossier.reference)
    }

    private fun applyToDocument(dossier: DossierPaiement, doc: Document, corrections: List<DocumentCorrection>) {
        // 1) Met a jour donneesExtraites (immutable Map<String,Any?> -> recopie).
        val current: Map<String, Any?> = doc.donneesExtraites ?: emptyMap()
        val merged = LinkedHashMap<String, Any?>(current)
        for (c in corrections) {
            // null -> on conserve la clef avec valeur null pour signaler
            // "champ explicitement vide" (different de "non extrait").
            merged[c.champ] = c.valeurCorrigee
        }
        doc.donneesExtraites = merged

        // 2) Mappe sur l'entite typee si on en connait une.
        for (c in corrections) {
            applyTypedField(dossier, doc, c.champ, c.valeurCorrigee)
        }
    }

    /**
     * Pour chaque (champ, valeur) recherche le slot d'entite typee correspondant
     * au document et y reflete la valeur. Les conversions sont tolerantes
     * (memes parseurs que ValidationHelpers).
     */
    private fun applyTypedField(dossier: DossierPaiement, doc: Document, champ: String, valeur: String?) {
        val docId = doc.id ?: return
        // Facture (1-n par dossier) : on cherche la facture liee au document.
        dossier.factures.firstOrNull { it.document.id == docId }?.let { applyFacture(it, champ, valeur); return }
        dossier.bonCommande?.takeIf { it.document.id == docId }?.let { applyBonCommande(it, champ, valeur); return }
        dossier.contratAvenant?.takeIf { it.document.id == docId }?.let { applyContrat(it, champ, valeur); return }
        dossier.ordrePaiement?.takeIf { it.document.id == docId }?.let { applyOrdrePaiement(it, champ, valeur); return }
        dossier.attestationFiscale?.takeIf { it.document.id == docId }?.let { applyAttestation(it, champ, valeur); return }
        dossier.pvReception?.takeIf { it.document.id == docId }?.let { applyPvReception(it, champ, valeur); return }
    }

    private fun applyFacture(f: Facture, champ: String, v: String?) = when (champ) {
        "numeroFacture" -> f.numeroFacture = v
        "dateFacture" -> f.dateFacture = parseDate(v)
        "dateReceptionFacture" -> f.dateReceptionFacture = parseDate(v)
        "fournisseur" -> f.fournisseur = v
        "client" -> f.client = v
        "ice" -> f.ice = v
        "identifiantFiscal", "if" -> f.identifiantFiscal = v
        "rc" -> f.rc = v
        "rib" -> f.rib = v
        "montantHT", "montantHt" -> f.montantHt = parseBd(v)
        "montantTVA", "montantTva" -> f.montantTva = parseBd(v)
        "tauxTVA", "tauxTva" -> f.tauxTva = parseBd(v)
        "montantTTC", "montantTtc" -> f.montantTtc = parseBd(v)
        "devise" -> f.devise = v
        "referenceContrat" -> f.referenceContrat = v
        "periode" -> f.periode = v
        else -> Unit
    }

    private fun applyBonCommande(bc: BonCommande, champ: String, v: String?) = when (champ) {
        "reference" -> bc.reference = v
        "dateBc" -> bc.dateBc = parseDate(v)
        "fournisseur" -> bc.fournisseur = v
        "objet" -> bc.objet = v
        "montantHT", "montantHt" -> bc.montantHt = parseBd(v)
        "montantTVA", "montantTva" -> bc.montantTva = parseBd(v)
        "tauxTVA", "tauxTva" -> bc.tauxTva = parseBd(v)
        "montantTTC", "montantTtc" -> bc.montantTtc = parseBd(v)
        "signataire" -> bc.signataire = v
        else -> Unit
    }

    private fun applyContrat(c: ContratAvenant, champ: String, v: String?) {
        // Reflexion legere via les setters Kotlin : seuls les champs simples
        // sont supportes pour eviter d'introduire un mapper de plus.
        when (champ) {
            "reference" -> setKotlin(c, "reference", v)
            "dateSignature" -> setKotlin(c, "dateSignature", parseDate(v))
            "dateDebut" -> setKotlin(c, "dateDebut", parseDate(v))
            "dateFin" -> setKotlin(c, "dateFin", parseDate(v))
            "fournisseur" -> setKotlin(c, "fournisseur", v)
            "objet" -> setKotlin(c, "objet", v)
            "montantHT", "montantHt" -> setKotlin(c, "montantHt", parseBd(v))
            "montantTVA", "montantTva" -> setKotlin(c, "montantTva", parseBd(v))
            "montantTTC", "montantTtc" -> setKotlin(c, "montantTtc", parseBd(v))
            else -> Unit
        }
    }

    private fun applyOrdrePaiement(op: OrdrePaiement, champ: String, v: String?) = when (champ) {
        "numeroOp", "numeroOP" -> op.numeroOp = v
        "dateEmission" -> op.dateEmission = parseDate(v)
        "emetteur" -> op.emetteur = v
        "natureOperation" -> op.natureOperation = v
        "description" -> op.description = v
        "beneficiaire" -> op.beneficiaire = v
        "rib" -> op.rib = v
        "banque" -> op.banque = v
        "modePaiement" -> op.modePaiement = v
        "devise" -> op.devise = v
        "montantOperation" -> op.montantOperation = parseBd(v)
        "referenceFacture" -> op.referenceFacture = v
        "referenceBcOuContrat" -> op.referenceBcOuContrat = v
        "signataireOrdonnateur" -> op.signataireOrdonnateur = v
        "signataireComptable" -> op.signataireComptable = v
        else -> Unit
    }

    private fun applyAttestation(arf: AttestationFiscale, champ: String, v: String?) = when (champ) {
        "numero" -> arf.numero = v
        "dateEdition" -> arf.dateEdition = parseDate(v)
        "raisonSociale" -> arf.raisonSociale = v
        "identifiantFiscal", "if" -> arf.identifiantFiscal = v
        "ice" -> arf.ice = v
        "rc" -> arf.rc = v
        "estEnRegle" -> arf.estEnRegle = parseBool(v)
        "dateValidite" -> arf.dateValidite = parseDate(v)
        "typeAttestation" -> arf.typeAttestation = v
        "codeVerification" -> arf.codeVerification = v
        else -> Unit
    }

    private fun applyPvReception(pv: PvReception, champ: String, v: String?) {
        when (champ) {
            "dateReception" -> setKotlin(pv, "dateReception", parseDate(v))
            "objet" -> setKotlin(pv, "objet", v)
            "fournisseur" -> setKotlin(pv, "fournisseur", v)
            "signataire" -> setKotlin(pv, "signataire", v)
            else -> Unit
        }
    }

    private fun parseBd(v: String?): BigDecimal? {
        if (v.isNullOrBlank()) return null
        val cleaned = v.replace(Regex("[^0-9.,\\-]"), "")
        if (cleaned.isEmpty()) return null
        val lc = cleaned.lastIndexOf(','); val ld = cleaned.lastIndexOf('.')
        return runCatching {
            if (lc > ld) BigDecimal(cleaned.replace(".", "").replace(",", "."))
            else BigDecimal(cleaned.replace(",", ""))
        }.getOrNull()
    }

    private fun parseDate(v: String?): LocalDate? {
        if (v.isNullOrBlank()) return null
        // ISO en priorite : c'est ce que l'extracteur Claude doit produire.
        runCatching { return LocalDate.parse(v) }
        for (fmt in DATE_FORMATTERS) runCatching { return LocalDate.parse(v, fmt) }
        return null
    }

    private fun parseBool(v: String?): Boolean? = when (v?.lowercase()?.trim()) {
        null, "" -> null
        "true", "oui", "yes", "o", "y", "conforme" -> true
        "false", "non", "no", "n", "non conforme" -> false
        else -> null
    }

    /**
     * Setter via reflexion Kotlin pour les entites dont nous ne voulons pas
     * dependre directement (PvReception / ContratAvenant ont des champs
     * specifiques qui ne sont pas tous exposes ici). On echoue silencieusement
     * si le champ est absent : la correction est conservee dans
     * donneesExtraites donc les regles `docStr`/`docAmount` la verront.
     */
    private fun setKotlin(target: Any, prop: String, value: Any?) {
        try {
            val cls = target.javaClass
            val setter = cls.methods.firstOrNull { it.name == "set${prop.replaceFirstChar { c -> c.uppercase() }}" && it.parameterCount == 1 }
            setter?.invoke(target, value)
        } catch (e: Exception) {
            log.debug("Could not reflect set {}.{} = {}: {}", target.javaClass.simpleName, prop, value, e.message)
        }
    }

    companion object {
        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
        )
    }
}
