package com.ocrsage.config

import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaForwardController : ErrorController {

    @GetMapping("/error")
    fun handleError(): String {
        return "forward:/index.html"
    }
}
