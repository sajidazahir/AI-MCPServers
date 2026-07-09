package com.example.jiraclient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AskController {

    private final ChatClient chatClient;

    public AskController(ChatClient.Builder chatClientBuilder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
    }

    public record AskRequest(String question) {
    }

    public record AskResponse(String answer) {
    }

    @GetMapping("/api/health")
    public String health() {
        return "ok";
    }

    @PostMapping("/api/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }
        String answer = chatClient.prompt(request.question()).call().content();
        return new AskResponse(answer);
    }
}
