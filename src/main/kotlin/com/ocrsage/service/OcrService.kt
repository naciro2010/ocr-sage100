package com.ocrsage.service

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream

@Service
class OcrService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()

    fun extractText(inputStream: InputStream, fileName: String): String {
        log.info("Extracting text from: {}", fileName)
        val metadata = Metadata()
        metadata.set(Metadata.RESOURCE_NAME_KEY, fileName)
        val text = tika.parseToString(inputStream, metadata)
        log.info("Extracted {} characters from {}", text.length, fileName)
        return text.trim()
    }
}
