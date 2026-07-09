package com.example.jiramcp.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The resource server validates tokens using the SAME in-process {@link JWKSource} the
 * authorization server signs with, rather than the property-driven issuer-uri, which
 * would make Spring fetch this app's own OIDC discovery metadata over HTTPS at startup -
 * a chicken-and-egg deadlock, since the app can't serve that endpoint until it has
 * finished starting.
 */
@Configuration
public class ResourceServerConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    @Order(2)
    SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http, JWKSource<SecurityContext> jwkSource) throws Exception {
        JwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        http.securityMatcher("/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcpAuthorization -> {
                    mcpAuthorization.authorizationServer(issuerUri);
                    mcpAuthorization.jwtDecoder(jwtDecoder);
                    mcpAuthorization.validateAudienceClaim(false);
                });
        return http.build();
    }
}
