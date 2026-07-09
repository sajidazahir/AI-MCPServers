package com.example.jiramcp.jira.dto;

public record JiraIssueSummary(
        String key,
        String summary,
        String status,
        String issueType,
        String assignee,
        String description) {
}
