package com.madaef.recondoc.service

import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.repository.dossier.DossierRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

private const val TOP_FOURNISSEURS_COUNT = 5

@Service
class FournisseurService(
    private val dossierRepo: DossierRepository
) {

    @Transactional(readOnly = true)
    fun listFournisseurs(query: String? = null): List<FournisseurSummaryResponse> {
        val rows = dossierRepo.aggregateByFournisseur(query?.takeIf { it.isNotBlank() })
        val identities = loadIdentities()
        return rows.map { row -> toSummary(row, identities[(row[0] as String).lowercase()]) }
    }

    @Transactional(readOnly = true)
    fun getFournisseurDetail(nom: String): FournisseurDetailResponse {
        val row = dossierRepo.aggregateOneFournisseur(nom)
            ?: throw NoSuchElementException("Fournisseur introuvable: $nom")
        val resolvedNom = (row[0] as? String) ?: nom
        val dossiers = dossierRepo.findByFournisseurIgnoreCaseOrderByDateCreationDesc(resolvedNom)
        val ident = dossierRepo.findFactureIdentitiesByFournisseur(resolvedNom, PageRequest.of(0, 1)).firstOrNull()
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
        val rows = dossierRepo.aggregateByFournisseur(null)
        val summaries = rows.map { toSummary(it, null) }
        val actifs = summaries.count { it.nbEnVerification > 0 || it.nbBrouillons > 0 }.toLong()
        val montantTotal = summaries.fold(BigDecimal.ZERO) { acc, f -> acc + f.montantTotalTtc }
        val topIdentities = loadIdentities()
        val top = summaries.sortedByDescending { it.montantTotalTtc }
            .take(TOP_FOURNISSEURS_COUNT)
            .map { s ->
                val id = topIdentities[s.nom.lowercase()]
                if (id == null) s else s.copy(ice = id.ice, identifiantFiscal = id.identifiantFiscal, rib = id.rib)
            }
        return FournisseursStatsResponse(
            totalFournisseurs = summaries.size.toLong(),
            fournisseursActifs = actifs,
            montantTotalEngage = montantTotal,
            topFournisseurs = top
        )
    }

    private fun toSummary(row: Array<Any?>, ident: Identity?): FournisseurSummaryResponse =
        FournisseurSummaryResponse(
            nom = row[0] as String,
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
        is String -> v.toBigDecimalOrNull() ?: BigDecimal.ZERO
        else -> BigDecimal.ZERO
    }
}
