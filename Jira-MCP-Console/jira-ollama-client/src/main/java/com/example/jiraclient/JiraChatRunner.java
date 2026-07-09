package com.example.jiraclient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Component
public class JiraChatRunner implements CommandLineRunner {

    private final ChatClient chatClient;

    public JiraChatRunner(ChatClient.Builder chatClientBuilder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Jira assistant ready (model talks to jira-mcp-server on :8080). Type a question, or 'exit'.");
        System.out.print("> ");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                System.out.print("> ");
                continue;
            }
            if (line.trim().equalsIgnoreCase("exit")) {
                break;
            }
            String response = chatClient.prompt(line).call().content();
            System.out.println(response);
            System.out.print("> ");
        }
    }
}
