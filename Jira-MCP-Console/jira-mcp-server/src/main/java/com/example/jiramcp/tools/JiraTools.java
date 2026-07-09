package com.example.jiramcp.tools;

import com.example.jiramcp.jira.JiraClient;
import com.example.jiramcp.jira.dto.JiraIssueSummary;
import com.example.jiramcp.jira.dto.JiraProjectSummary;
import com.example.jiramcp.jira.dto.JiraSearchResult;
import com.example.jiramcp.jira.dto.TransitionOption;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JiraTools {

    private final JiraClient jiraClient;

    public JiraTools(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    @McpTool(name = "jira_list_projects",
            description = "List all Jira projects visible to the authenticated user, with their key and name.")
    public List<JiraProjectSummary> listProjects() {
        return jiraClient.listProjects();
    }

    @McpTool(name = "jira_search_issues",
            description = "Search Jira issues using JQL (Jira Query Language), e.g. \"project = ABC AND status = 'To Do'\". Returns matching issues with key, summary, status, type and assignee.")
    public JiraSearchResult searchIssues(
            @McpToolParam(description = "JQL query string", required = true) String jql,
            @McpToolParam(description = "Maximum number of issues to return (default 25)", required = false) Integer maxResults) {
        return jiraClient.searchIssues(jql, maxResults == null ? 25 : maxResults);
    }

    @McpTool(name = "jira_get_issue",
            description = "Get full details of a single Jira issue by its key, e.g. ABC-123, including its description.")
    public JiraIssueSummary getIssue(
            @McpToolParam(description = "Issue key, e.g. ABC-123", required = true) String issueKey) {
        return jiraClient.getIssue(issueKey);
    }

    @McpTool(name = "jira_create_issue",
            description = "Create a new Jira issue in the given project.")
    public String createIssue(
            @McpToolParam(description = "Project key, e.g. ABC", required = true) String projectKey,
            @McpToolParam(description = "Issue summary/title", required = true) String summary,
            @McpToolParam(description = "Issue description (plain text)", required = false) String description,
            @McpToolParam(description = "Issue type name, e.g. Bug, Task, Story", required = true) String issueType) {
        return jiraClient.createIssue(projectKey, summary, description, issueType);
    }

    @McpTool(name = "jira_add_comment",
            description = "Add a plain-text comment to an existing Jira issue.")
    public String addComment(
            @McpToolParam(description = "Issue key, e.g. ABC-123", required = true) String issueKey,
            @McpToolParam(description = "Comment text", required = true) String comment) {
        jiraClient.addComment(issueKey, comment);
        return "Comment added to " + issueKey;
    }

    @McpTool(name = "jira_list_transitions",
            description = "List the workflow transitions currently available for an issue (e.g. 'Start Progress', 'Done').")
    public List<TransitionOption> listTransitions(
            @McpToolParam(description = "Issue key, e.g. ABC-123", required = true) String issueKey) {
        return jiraClient.listTransitions(issueKey);
    }

    @McpTool(name = "jira_transition_issue",
            description = "Move an issue to a new status by transition name, e.g. 'Done', 'In Progress'.")
    public String transitionIssue(
            @McpToolParam(description = "Issue key, e.g. ABC-123", required = true) String issueKey,
            @McpToolParam(description = "Exact name of the transition to perform", required = true) String transitionName) {
        jiraClient.transitionIssue(issueKey, transitionName);
        return issueKey + " transitioned via '" + transitionName + "'";
    }
}
