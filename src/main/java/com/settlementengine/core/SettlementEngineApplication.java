package com.settlementengine.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

// VIA_DTO: paginated list endpoints (GET /settlements, /invoices, /accounts/{id}/settlements)
// serialize Page<T> as Spring Data's PagedModel instead of Jackson's default PageImpl
// serialization, which Spring Data itself warns isn't a stable JSON contract across versions.
@SpringBootApplication
@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class SettlementEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementEngineApplication.class, args);
    }
}
