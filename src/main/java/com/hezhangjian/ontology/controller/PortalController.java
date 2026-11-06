package com.hezhangjian.ontology.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class PortalController {
    @GetMapping({"/data/**", "/ontology/**"})
    public String portal() {
        return "forward:/index.html";
    }
}
