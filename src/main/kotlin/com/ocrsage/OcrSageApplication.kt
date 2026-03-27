package com.ocrsage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OcrSageApplication

fun main(args: Array<String>) {
    runApplication<OcrSageApplication>(*args)
}
