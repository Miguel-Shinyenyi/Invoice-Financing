package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@LabController
@RequestMapping("/lab")
public class LabStatusController {

    private final LabStatusService status;
    private final LabSystemService system;

    public LabStatusController(LabStatusService status, LabSystemService system) {
        this.status = status;
        this.system = system;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        return system.snapshot();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return status.snapshot();
    }
}
