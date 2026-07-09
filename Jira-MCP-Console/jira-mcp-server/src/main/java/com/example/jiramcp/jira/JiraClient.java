package com.example.jiramcp.jira;

import com.example.jiramcp.jira.dto.JiraIssueSummary;
import com.example.jiramcp.jira.dto.JiraProjectSummary;
import com.example.jiramcp.jira.dto.JiraSearchResult;
import com.example.jiramcp.jira.dto.TransitionOption;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class JiraClient {

    private final RestClient restClient;

    public JiraClient(RestClient jiraRestClient) {
        this.restClient = jiraRestClient;
    }

    public List<JiraProjectSummary> listProjects() {
        ProjectSearchResponse response = restClient.get()
                .uri("/rest/api/3/project/search")
                .retrieve()
                .body(ProjectSearchResponse.class);
        if (response == null) {
            return List.of();
        }
        return response.values().stream()
                .map(p -> new JiraProjectSummary(p.key(), p.name()))
                .toList();
    }

    public JiraSearchResult searchIssues(String jql, int maxResults) {
        Map<String, Object> body = Map.of(
                "jql", jql,
                "maxResults", maxResults,
                "fields", List.of("summary", "status", "issuetype", "assignee")
        );
        SearchResponse response = restClient.post()
                .uri("/rest/api/3/search/jql")
                .body(body)
                .retrieve()
                .body(SearchResponse.class);
        if (response == null) {
            return new JiraSearchResult(List.of(), true);
        }
        List<JiraIssueSummary> issues = response.issues().stream()
                .map(this::toSummary)
                .toList();
        return new JiraSearchResult(issues, response.isLast());
    }

    public JiraIssueSummary getIssue(String issueKey) {
        IssueRaw raw = restClient.get()
                .uri("/rest/api/3/issue/{key}?fields=summary,status,issuetype,assignee,description", issueKey)
                .retrieve()
                .body(IssueRaw.class);
        return toSummary(raw);
    }

    public String createIssue(String projectKey, String summary, String description, String issueType) {
        Map<String, Object> fields = Map.of(
                "project", Map.of("key", projectKey),
                "summary", summary,
                "issuetype", Map.of("name", issueType),
                "description", AdfUtil.fromPlainText(description)
        );
        CreateIssueResponse response = restClient.post()
                .uri("/rest/api/3/issue")
                .body(Map.of("fields", fields))
                .retrieve()
                .body(CreateIssueResponse.class);
        return response == null ? null : response.key();
    }

    public void addComment(String issueKey, String commentText) {
        restClient.post()
                .uri("/rest/api/3/issue/{key}/comment", issueKey)
                .body(Map.of("body", AdfUtil.fromPlainText(commentText)))
                .retrieve()
                .toBodilessEntity();
    }

    public List<TransitionOption> listTransitions(String issueKey) {
        TransitionsResponse response = restClient.get()
                .uri("/rest/api/3/issue/{key}/transitions", issueKey)
                .retrieve()
                .body(TransitionsResponse.class);
        if (response == null) {
            return List.of();
        }
        return response.transitions().stream()
                .map(t -> new TransitionOption(t.id(), t.name()))
                .toList();
    }

    public void transitionIssue(String issueKey, String transitionName) {
        TransitionOption match = listTransitions(issueKey).stream()
                .filter(t -> t.name().equalsIgnoreCase(transitionName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No transition named '" + transitionName + "' is available for " + issueKey));
        restClient.post()
                .uri("/rest/api/3/issue/{key}/transitions", issueKey)
                .body(Map.of("transition", Map.of("id", match.id())))
                .retrieve()
                .toBodilessEntity();
    }

    private JiraIssueSummary toSummary(IssueRaw raw) {
        if (raw == null) {
            return null;
        }
        IssueFields f = raw.fields();
        String status = f.status() == null ? null : f.status().name();
        String type = f.issuetype() == null ? null : f.issuetype().name();
        String assignee = f.assignee() == null ? null : f.assignee().displayName();
        return new JiraIssueSummary(raw.key(), f.summary(), status, type, assignee, AdfUtil.toPlainText(f.description()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectRaw(String id, String key, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectSearchResponse(List<ProjectRaw> values) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatusRaw(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueTypeRaw(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserRaw(String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueFields(String summary, StatusRaw status, IssueTypeRaw issuetype, UserRaw assignee, Object description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IssueRaw(String id, String key, IssueFields fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(List<IssueRaw> issues, String nextPageToken, boolean isLast) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TransitionRaw(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TransitionsResponse(List<TransitionRaw> transitions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateIssueResponse(String id, String key) {
    }
}
