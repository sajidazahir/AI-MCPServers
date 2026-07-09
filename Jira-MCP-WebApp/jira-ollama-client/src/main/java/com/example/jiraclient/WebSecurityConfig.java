package com.example.jiraclient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// This app's own HTTP endpoints are a local-only dev tool (the hub page calling /api/**),
// separate from the outbound OAuth2 client-credentials flow used to call jira-mcp-server.
// spring-boot-starter-oauth2-client + spring-boot-starter-web otherwise auto-configures a
// default login-required filter chain, which would 302 every /api request to /login.
@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
