package com.nano.trace;

final class TraceSanitizer {
    private TraceSanitizer() {
    }

    static String sanitizeAndTruncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String sanitized = value
                .replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***")
                .replaceAll("(?i)(\\b(?:api[_-]?key|token|password|secret|authorization)\\b\\s*[:=]\\s*[\"]?)([^\\s,}\"]+)", "$1***")
                .replaceAll("(?i)(sk-[A-Za-z0-9_-]{12,})", "sk-***");
        int limit = Math.max(0, maxChars);
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit) + "...(truncated)";
    }
}
