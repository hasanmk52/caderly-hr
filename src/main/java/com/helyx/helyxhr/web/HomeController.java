package com.helyx.helyxhr.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {

    /** Any signed-in user of this tenant; the role hierarchy makes EMPLOYEE the floor. */
    @GetMapping("/")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String home() {
        return "home";
    }
}
