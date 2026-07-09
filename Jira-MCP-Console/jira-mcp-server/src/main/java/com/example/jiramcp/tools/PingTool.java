package com.example.jiramcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class PingTool {

    @McpTool(name = "ping", description = "Health check tool. Echoes back the given message so a client can confirm the MCP server is reachable.")
    public String ping(
            @McpToolParam(description = "Message to echo back", required = false) String message) {
        return "pong: " + (message == null ? "hello" : message);
    }
}
