package com.madaef.recondoc.service

import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class DocumentProgress(
    val documentId: String,
    val nomFichier: String,
    val step: String,
    val statut: String,
    val detail: String? = null
)

@Service
class DocumentProgressService {

    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(dossierId: UUID): SseEmitter {
        val emitter = SseEmitter(120_000L)
        emitters.computeIfAbsent(dossierId) { CopyOnWriteArrayList() }.add(emitter)
        emitter.onCompletion { removeEmitter(dossierId, emitter) }
        emitter.onTimeout { removeEmitter(dossierId, emitter) }
        emitter.onError { removeEmitter(dossierId, emitter) }
        return emitter
    }

    fun emit(dossierId: UUID, progress: DocumentProgress) {
        val list = emitters[dossierId] ?: return
        val dead = mutableListOf<SseEmitter>()
        for (e in list) {
            try {
                e.send(SseEmitter.event()
                    .name("progress")
                    .data(progress))
            } catch (_: Exception) {
                dead.add(e)
            }
        }
        list.removeAll(dead.toSet())
    }

    private fun removeEmitter(dossierId: UUID, emitter: SseEmitter) {
        emitters[dossierId]?.remove(emitter)
    }
}
