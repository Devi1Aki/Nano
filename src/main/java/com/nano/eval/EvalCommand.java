package com.nano.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EvalCommand {
    public enum Action {
        HELP, LIST, RUN, REPORTS, COMPARE
    }

    public record Request(Action action, String caseId, String category, String mode,
                          boolean allowMultiple, String baseline, String candidate) {
    }

    private EvalCommand() {
    }

    public static Request parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return new Request(Action.HELP, null, null, null, false, null, null);
        }
        List<String> tokens = tokenize(payload);
        String action = tokens.get(0).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            return selection(Action.LIST, tokens.subList(1, tokens.size()));
        }
        if ("run".equals(action)) {
            return selection(Action.RUN, tokens.subList(1, tokens.size()));
        }
        if ("reports".equals(action)) {
            return new Request(Action.REPORTS, null, null, null, false, null, null);
        }
        if ("compare".equals(action)) {
            String baseline = tokens.size() > 1 ? tokens.get(1) : null;
            String candidate = tokens.size() > 2 ? tokens.get(2) : null;
            return new Request(Action.COMPARE, null, null, null, false, baseline, candidate);
        }
        return selection(Action.RUN, tokens);
    }

    public static List<BenchmarkCase> select(List<BenchmarkCase> cases, Request request) {
        return cases.stream()
                .filter(item -> request.caseId() == null || item.id().equalsIgnoreCase(request.caseId()))
                .filter(item -> request.category() == null || item.category().equalsIgnoreCase(request.category()))
                .filter(item -> request.mode() == null || item.mode().equalsIgnoreCase(request.mode()))
                .toList();
    }

    private static Request selection(Action action, List<String> tokens) {
        String caseId = null;
        String category = null;
        String mode = null;
        boolean allowMultiple = false;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--all".equalsIgnoreCase(token)) {
                allowMultiple = true;
            } else if (token.startsWith("--category=")) {
                category = token.substring("--category=".length()).trim();
            } else if ("--category".equalsIgnoreCase(token) && i + 1 < tokens.size()) {
                category = tokens.get(++i);
            } else if (token.startsWith("--mode=")) {
                mode = token.substring("--mode=".length()).trim();
            } else if ("--mode".equalsIgnoreCase(token) && i + 1 < tokens.size()) {
                mode = tokens.get(++i);
            } else if (!token.startsWith("--") && caseId == null) {
                caseId = token;
            }
        }
        return new Request(action, blankToNull(caseId), blankToNull(category), blankToNull(mode),
                allowMultiple, null, null);
    }

    private static List<String> tokenize(String payload) {
        List<String> values = new ArrayList<>();
        for (String token : payload.trim().split("\\s+")) {
            if (!token.isBlank()) {
                values.add(token);
            }
        }
        return values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
