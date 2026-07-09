package com.example.jiramcp.jira.dto;

import java.util.List;

public record JiraSearchResult(List<JiraIssueSummary> issues, boolean isLast) {
}
