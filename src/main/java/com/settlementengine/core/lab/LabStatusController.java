package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@LabController
@RequestMapping("/lab/status")
public class LabStatusController {

    private final LabStatusService status;

    public LabStatusController(LabStatusService status) {
        this.status = status;
    }

    @GetMapping
    public Map<String, Object> status() {
        return status.snapshot();
    }
}
