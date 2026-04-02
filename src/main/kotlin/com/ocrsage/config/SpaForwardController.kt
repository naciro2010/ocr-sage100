package com.ocrsage.config

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaForwardController {

    @GetMapping("/{path:^(?!api|actuator).*$}/**")
    fun forward(): String {
        return "forward:/index.html"
    }
}
