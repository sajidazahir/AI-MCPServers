package com.example.jiramcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jira")
public record JiraProperties(String baseUrl, String email, String apiToken) {
}
