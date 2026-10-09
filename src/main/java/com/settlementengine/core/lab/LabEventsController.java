package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@LabController
@RequestMapping("/lab/events")
public class LabEventsController {

    private final LabEventsService events;

    public LabEventsController(LabEventsService events) {
        this.events = events;
    }

    @GetMapping
    public Map<String, Object> events(@RequestParam(defaultValue = "50") int limit) {
        return events.events(limit);
    }
}
