package com.madaef.recondoc.controller.dossier

import com.madaef.recondoc.dto.dossier.FournisseurDetailResponse
import com.madaef.recondoc.dto.dossier.FournisseurSummaryResponse
import com.madaef.recondoc.dto.dossier.FournisseursStatsResponse
import com.madaef.recondoc.service.FournisseurService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/api/fournisseurs")
class FournisseurController(
    private val fournisseurService: FournisseurService
) {

    @GetMapping
    fun list(@RequestParam(required = false) q: String?): ResponseEntity<List<FournisseurSummaryResponse>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=15")
            .body(fournisseurService.listFournisseurs(q))
    }

    @GetMapping("/stats")
    fun stats(): ResponseEntity<FournisseursStatsResponse> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=30")
            .body(fournisseurService.getStats())
    }

    @GetMapping("/{nom}")
    fun detail(@PathVariable nom: String): ResponseEntity<FournisseurDetailResponse> {
        val decoded = URLDecoder.decode(nom, StandardCharsets.UTF_8)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=5")
            .body(fournisseurService.getFournisseurDetail(decoded))
    }
}
