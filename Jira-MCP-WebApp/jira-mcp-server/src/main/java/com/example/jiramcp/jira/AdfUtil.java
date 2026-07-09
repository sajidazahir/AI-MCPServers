package com.example.jiramcp.jira;

import java.util.List;
import java.util.Map;

/**
 * Jira Cloud's v3 API stores rich text (issue descriptions, comments) as Atlassian
 * Document Format (ADF) - a JSON document tree - rather than plain strings. These
 * helpers convert plain text to the minimal ADF shape Jira expects, and flatten an
 * ADF tree back down to plain text for reading.
 */
public final class AdfUtil {

    private AdfUtil() {
    }

    public static Map<String, Object> fromPlainText(String text) {
        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(
                        Map.of(
                                "type", "paragraph",
                                "content", List.of(
                                        Map.of("type", "text", "text", text == null ? "" : text)
                                )
                        )
                )
        );
    }

    public static String toPlainText(Object adfNode) {
        if (adfNode == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collectText(adfNode, sb);
        return sb.toString().trim();
    }

    private static void collectText(Object node, StringBuilder sb) {
        if (node instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text instanceof String s) {
                sb.append(s);
            }
            Object content = map.get("content");
            if (content instanceof List<?> list) {
                for (Object child : list) {
                    collectText(child, sb);
                }
                if (!list.isEmpty()) {
                    sb.append('\n');
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                collectText(child, sb);
            }
        }
    }
}
