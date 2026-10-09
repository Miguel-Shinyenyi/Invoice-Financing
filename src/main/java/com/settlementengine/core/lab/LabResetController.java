package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@LabController
@RequestMapping("/lab/reset")
public class LabResetController {

    private final LabResetService reset;

    public LabResetController(LabResetService reset) {
        this.reset = reset;
    }

    @PostMapping
    public LabResetState reset() {
        return reset.resetOnDemand();
    }
}
