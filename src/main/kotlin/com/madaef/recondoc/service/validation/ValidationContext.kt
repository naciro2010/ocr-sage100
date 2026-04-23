package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.AttestationFiscale
import com.madaef.recondoc.entity.dossier.BonCommande
import com.madaef.recondoc.entity.dossier.ChecklistAutocontrole
import com.madaef.recondoc.entity.dossier.ContratAvenant
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.Facture
import com.madaef.recondoc.entity.dossier.OrdrePaiement
import com.madaef.recondoc.entity.dossier.PvReception
import com.madaef.recondoc.entity.dossier.TableauControle
import java.math.BigDecimal

/**
 * Snapshot immuable d'un dossier pret a etre evalue par les regles.
 * Toutes les relations "a un" sont resolues en amont, evitant les
 * appels repetes aux collections JPA depuis chaque regle.
 */
data class ValidationContext(
    val dossier: DossierPaiement,
    val facture: Facture?,
    val allFactures: List<Facture>,
    val bc: BonCommande?,
    val op: OrdrePaiement?,
    val contrat: ContratAvenant?,
    val pv: PvReception?,
    val arf: AttestationFiscale?,
    val checklist: ChecklistAutocontrole?,
    val tableau: TableauControle?,
    val tol: BigDecimal
) {
    companion object {
        fun of(dossier: DossierPaiement, tolerance: BigDecimal): ValidationContext = ValidationContext(
            dossier = dossier,
            facture = dossier.factures.firstOrNull(),
            allFactures = dossier.factures.toList(),
            bc = dossier.bonCommande,
            op = dossier.ordrePaiement,
            contrat = dossier.contratAvenant,
            pv = dossier.pvReception,
            arf = dossier.attestationFiscale,
            checklist = dossier.checklistAutocontrole,
            tableau = dossier.tableauControle,
            tol = tolerance
        )
    }
}
