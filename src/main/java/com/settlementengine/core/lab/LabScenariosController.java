package com.settlementengine.core.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;

/** Serves lab/seed/scenarios.json (the catalog the UI renders) from the classpath. */
@LabController
@RequestMapping("/lab/scenarios")
public class LabScenariosController {

    private final JsonNode catalog;

    public LabScenariosController(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("lab/scenarios.json").getInputStream()) {
            this.catalog = mapper.readTree(in);
        }
    }

    @GetMapping
    public JsonNode scenarios() {
        return catalog;
    }
}
