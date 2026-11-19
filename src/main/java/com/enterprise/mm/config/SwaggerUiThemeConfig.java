package com.enterprise.mm.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

@Configuration
public class SwaggerUiThemeConfig {

    @Bean
    @Primary
    SwaggerIndexTransformer darkModeSwaggerIndexTransformer(
        SwaggerUiConfigProperties swaggerUiConfig,
        SwaggerUiOAuthProperties swaggerUiOAuth,
        SwaggerUiConfigParameters swaggerUiConfigParameters,
        SwaggerWelcomeCommon swaggerWelcomeCommon,
        ObjectMapperProvider objectMapperProvider
    ) {
        SwaggerIndexPageTransformer delegate = new SwaggerIndexPageTransformer(
            swaggerUiConfig,
            swaggerUiOAuth,
            swaggerUiConfigParameters,
            swaggerWelcomeCommon,
            objectMapperProvider
        );
        return new DarkModeSwaggerIndexTransformer(delegate);
    }

    private static final class DarkModeSwaggerIndexTransformer implements SwaggerIndexTransformer {
        private static final String LIGHT_HTML_ROOT = "<html lang=\"en\">";
        private static final String DARK_HTML_ROOT = "<html lang=\"en\" class=\"dark-mode\">";

        private final SwaggerIndexTransformer delegate;

        private DarkModeSwaggerIndexTransformer(SwaggerIndexTransformer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Resource transform(
            HttpServletRequest request,
            Resource resource,
            ResourceTransformerChain transformerChain
        ) throws IOException {
            Resource transformed = delegate.transform(request, resource, transformerChain);
            String content = transformed.getContentAsString(StandardCharsets.UTF_8);
            if (!content.contains(LIGHT_HTML_ROOT) || content.contains(DARK_HTML_ROOT)) {
                return transformed;
            }
            byte[] darkModeContent = content.replace(LIGHT_HTML_ROOT, DARK_HTML_ROOT)
                .getBytes(StandardCharsets.UTF_8);
            return new TransformedResource(transformed, darkModeContent);
        }
    }
}
