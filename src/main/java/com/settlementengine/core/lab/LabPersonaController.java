package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@LabController
@RequestMapping("/lab/personas")
public class LabPersonaController {

    private final LabPersonaService personas;

    public LabPersonaController(LabPersonaService personas) {
        this.personas = personas;
    }

    @GetMapping
    public List<LabPersonaService.Persona> list() {
        return personas.personas();
    }

    @PostMapping("/{role}/token")
    public LabPersonaService.PersonaToken token(@PathVariable String role) {
        return personas.token(role);
    }
}
