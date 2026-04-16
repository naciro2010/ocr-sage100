package com.madaef.recondoc.service

import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.repository.dossier.DossierRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class FournisseurService(
    private val dossierRepo: DossierRepository
) {

    @Transactional(readOnly = true)
    fun listFournisseurs(query: String? = null): List<FournisseurSummaryResponse> {
        val rows = dossierRepo.aggregateByFournisseur(query?.takeIf { it.isNotBlank() })
        val identities = loadIdentities()
        return rows.map { row ->
            val nom = row[0] as String
            val ident = identities[nom.lowercase()]
            FournisseurSummaryResponse(
                nom = nom,
                ice = ident?.ice,
                identifiantFiscal = ident?.identifiantFiscal,
                rib = ident?.rib,
                nbDossiers = (row[1] as Number).toLong(),
                nbBrouillons = (row[2] as Number).toLong(),
                nbEnVerification = (row[3] as Number).toLong(),
                nbValides = (row[4] as Number).toLong(),
                nbRejetes = (row[5] as Number).toLong(),
                montantTotalTtc = toBigDecimal(row[6]),
                montantValide = toBigDecimal(row[7]),
                dernierDossier = row[8] as? LocalDateTime,
                premierDossier = row[9] as? LocalDateTime
            )
        }
    }

    @Transactional(readOnly = true)
    fun getFournisseurDetail(nom: String): FournisseurDetailResponse {
        val row = dossierRepo.aggregateOneFournisseur(nom)
            ?: throw NoSuchElementException("Fournisseur introuvable: $nom")
        val resolvedNom = (row[0] as? String) ?: nom
        val dossiers = dossierRepo.findByFournisseurIgnoreCaseOrderByDateCreationDesc(resolvedNom)
        val ident = dossierRepo.findFactureIdentitiesByFournisseur(resolvedNom).firstOrNull()
        return FournisseurDetailResponse(
            nom = resolvedNom,
            ice = ident?.getOrNull(1) as? String,
            identifiantFiscal = ident?.getOrNull(2) as? String,
            rc = ident?.getOrNull(3) as? String,
            rib = ident?.getOrNull(4) as? String,
            nbDossiers = (row[1] as Number).toLong(),
            nbBrouillons = (row[2] as Number).toLong(),
            nbEnVerification = (row[3] as Number).toLong(),
            nbValides = (row[4] as Number).toLong(),
            nbRejetes = (row[5] as Number).toLong(),
            montantTotalTtc = toBigDecimal(row[6]),
            montantTotalHt = toBigDecimal(row[7]),
            montantTotalTva = toBigDecimal(row[8]),
            montantValide = toBigDecimal(row[9]),
            montantEnCours = toBigDecimal(row[10]),
            dernierDossier = row[11] as? LocalDateTime,
            premierDossier = row[12] as? LocalDateTime,
            dossiers = dossiers.map { it.toListResponse() }
        )
    }

    @Transactional(readOnly = true)
    fun getStats(): FournisseursStatsResponse {
        val all = listFournisseurs(null)
        val actifs = all.count { it.nbEnVerification > 0 || it.nbBrouillons > 0 }.toLong()
        val montantTotal = all.fold(BigDecimal.ZERO) { acc, f -> acc + f.montantTotalTtc }
        return FournisseursStatsResponse(
            totalFournisseurs = all.size.toLong(),
            fournisseursActifs = actifs,
            montantTotalEngage = montantTotal,
            topFournisseurs = all.sortedByDescending { it.montantTotalTtc }.take(5)
        )
    }

    private data class Identity(val ice: String?, val identifiantFiscal: String?, val rib: String?)

    private fun loadIdentities(): Map<String, Identity> {
        return dossierRepo.findAllFactureIdentities().associate { row ->
            val nom = (row[0] as? String)?.lowercase() ?: ""
            nom to Identity(
                ice = row.getOrNull(1) as? String,
                identifiantFiscal = row.getOrNull(2) as? String,
                rib = row.getOrNull(3) as? String
            )
        }
    }

    private fun toBigDecimal(v: Any?): BigDecimal = when (v) {
        null -> BigDecimal.ZERO
        is BigDecimal -> v
        is Number -> BigDecimal.valueOf(v.toDouble())
        else -> BigDecimal.ZERO
    }
}
