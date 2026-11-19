package com.enterprise.mm.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;

@Configuration
@OpenAPIDefinition(
        info = @io.swagger.v3.oas.annotations.info.Info(
                contact = @Contact(
                        name = "Materials Management Support",
                        email = "info@mm.uz",
                        url = "mm.uz"
                ),
                description = "OpenApi documentation for Spring Security",
                title = "MM API Documentation",
                version = "1.0.0",
                license = @io.swagger.v3.oas.annotations.info.License(
                        name = "MM Docs",
                        url = "https://navoiy-azot-mm.tenzorsoft.uz/docs"
                ),
                termsOfService = "Terms of services"
        ),
        servers = {
                @Server(
                        description = "Prod ENV",
                        url = "https://api-navoiy-azot-mm.tenzorsoft.uz"
                ),
                @Server(
                        description = "Local ENV",
                        url = "http://localhost:8080"
                )
        },
        security = {
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"),
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "basicAuth")
        }
)
@SecuritySchemes({
        @io.swagger.v3.oas.annotations.security.SecurityScheme(
                name = "bearerAuth",
                description = "JWT auth",
                scheme = "bearer",
                type = SecuritySchemeType.HTTP,
                bearerFormat = "JWT",
                in = SecuritySchemeIn.HEADER
        ),
        @io.swagger.v3.oas.annotations.security.SecurityScheme(
                name = "basicAuth",
                description = "HTTP Basic auth (username/password)",
                scheme = "basic",
                type = SecuritySchemeType.HTTP,
                in = SecuritySchemeIn.HEADER
        )
})
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";
    public static final String TENANT_HEADER = "tenantHeader";
    public static final String COMPANY_HEADER = "companyHeader";

    @Bean
    OpenAPI mmOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Enterprise ERP MM API")
                .version("0.1.0")
                .description("Material Management SaaS/Enterprise API: master data, suppliers, MRP, procurement, inventory, valuation, reports, integrations, and management opportunity analytics. Management opportunity analytics produces management estimates only and creates no accounting postings.")
                .license(new License().name("Proprietary")))
            .schemaRequirement(BEARER_AUTH, new SecurityScheme()
                .name(BEARER_AUTH)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"))
            .schemaRequirement(TENANT_HEADER, new SecurityScheme()
                .name("X-Tenant-Id")
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER))
            .schemaRequirement(COMPANY_HEADER, new SecurityScheme()
                .name("X-Company-Id")
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER))
            .addSecurityItem(new SecurityRequirement()
                .addList(BEARER_AUTH)
                .addList(TENANT_HEADER)
                .addList(COMPANY_HEADER));
    }

    @Bean
    OperationCustomizer globalHeaders() {
        StringSchema languageSchema = new StringSchema()
            ._default("en")
            .addEnumItem("uz")
            .addEnumItem("ru")
            .addEnumItem("en");
        return (operation, handlerMethod) -> {
            operation.addParametersItem(new Parameter()
                .name("Accept-Language")
                .description("Configure response language (uz, ru, en)")
                .schema(languageSchema)
                .in("header")
                .required(false));
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer schemaExamples() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(this::cleanSchemaExamples);
        };
    }

    private void cleanSchemaExamples(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }
        properties.forEach((name, property) -> {
            if (property == null) {
                return;
            }
            switch (name) {
                case "status" -> {
                    if (property.getExample() == null) {
                        property.setExample("ACTIVE");
                    }
                    if (property.getDescription() == null) {
                        property.setDescription("Optional record status. Use ACTIVE for normal master data unless the endpoint documents a workflow-specific status.");
                    }
                }
                case "code" -> {
                    if (property.getExample() == null) {
                        property.setExample("MAT-001");
                    }
                    if (property.getDescription() == null) {
                        property.setDescription("Business code. If omitted, some endpoints generate it automatically.");
                    }
                }
                case "name" -> {
                    if (property.getExample() == null) {
                        property.setExample("Main warehouse inventory commission");
                    }
                }
                case "companyId" -> {
                    if (property.getExample() == null) {
                        property.setExample("00000000-0000-0000-0000-000000000001");
                    }
                    if (property.getDescription() == null) {
                        property.setDescription("Company scope. Optional for normal company-scoped users; useful for admin/global users.");
                    }
                }
                case "description" -> {
                    if (property.getExample() == null) {
                        property.setExample("Optional notes");
                    }
                }
                default -> {
                    if (name.endsWith("Id") && property.getExample() == null) {
                        property.setExample("3fa85f64-5717-4562-b3fc-2c963f66afa6");
                    }
                }
            }
        });
    }
}
