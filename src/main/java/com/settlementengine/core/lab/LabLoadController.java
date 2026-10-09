package com.settlementengine.core.lab;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@LabController
@RequestMapping("/lab/load")
public class LabLoadController {

    private final LabLoadService load;
    private final LabSseSupport sse;

    public LabLoadController(LabLoadService load, LabSseSupport sse) {
        this.load = load;
        this.sse = sse;
    }

    @PostMapping("/start")
    public LabLoadRunView start(@RequestBody LoadPlan plan) {
        return load.start(plan);
    }

    @GetMapping("/history")
    public List<LabLoadRunView> history() {
        return load.history();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LabLoadRunView> get(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean download) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lab-load-run-" + id + ".json\"");
        }
        return response.body(load.get(id));
    }

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable UUID id, HttpServletRequest request) {
        load.get(id); // 404 before a slot is taken
        return sse.open(request, emitter -> load.subscribe(id, sample -> {
            try {
                emitter.send(SseEmitter.event().name("sample").data(sample));
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            }
        }, () -> {
            try {
                emitter.send(SseEmitter.event().name("done").data(load.get(id)));
            } catch (IOException | IllegalStateException ignored) {
                // client already gone
            }
            emitter.complete();
        }));
    }

    @PostMapping("/{id}/cancel")
    public LabLoadRunView cancel(@PathVariable UUID id) {
        return load.cancel(id);
    }
}
