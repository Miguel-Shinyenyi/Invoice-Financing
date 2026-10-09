package com.settlementengine.core.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@LabController
@RequestMapping("/lab/kafka")
public class LabKafkaController {

    private final LabKafkaService kafka;

    public LabKafkaController(LabKafkaService kafka) {
        this.kafka = kafka;
    }

    @GetMapping
    public LabKafkaService.Overview overview() {
        return kafka.overview();
    }

    @GetMapping("/topics/{topic}/messages")
    public List<LabKafkaService.Message> messages(@PathVariable String topic, @RequestParam(defaultValue = "50") int limit) {
        return kafka.lastMessages(topic, limit);
    }
}
