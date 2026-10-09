package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@LabController
@RequestMapping("/lab/events")
public class LabEventsController {

    private final LabEventsService events;

    private final LabOutOfOrderService outOfOrder;

    public LabEventsController(LabEventsService events, LabOutOfOrderService outOfOrder) {
        this.events = events;
        this.outOfOrder = outOfOrder;
    }

    @PostMapping("/out-of-order")
    public Map<String, Object> outOfOrder() throws Exception {
        return outOfOrder.run();
    }

    @GetMapping
    public Map<String, Object> events(@RequestParam(defaultValue = "50") int limit) {
        return events.events(limit);
    }
}
