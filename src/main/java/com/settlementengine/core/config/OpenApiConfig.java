package com.settlementengine.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI settlementEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Settlement Engine API")
                        .description("Idempotent settlement and reconciliation engine: ledger accounts, "
                                + "idempotency-key protected settlements, and the settlement state machine "
                                + "(PENDING -> CONFIRMED/FAILED/UNKNOWN -> REVERSED).")
                        .version("v1")
                        .license(new License().name("MIT")));
    }
}
