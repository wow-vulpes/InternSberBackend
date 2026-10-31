package ru.kkd.api;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import ru.kkd.domain.model.IssueFilter;
import ru.kkd.domain.model.RuleCode;
import ru.kkd.domain.model.Severity;

final class RequestParameters {

    private final Map<String, String> values;

    RequestParameters(MultiValueMap<String, String> input, Set<String> allowed) {
        Map<String, String> parsed = new HashMap<>();
        input.forEach(
                (name, entries) -> {
                    if (!allowed.contains(name)) {
                        throw new InvalidRequestException("Unknown parameter: " + name);
                    }
                    if (entries == null || entries.size() != 1) {
                        throw new InvalidRequestException("Parameter must occur once: " + name);
                    }
                    String value = entries.getFirst();
                    if (value == null || value.isBlank()) {
                        throw new InvalidRequestException("Parameter must not be blank: " + name);
                    }
                    parsed.put(name, value);
                });
        values = Map.copyOf(parsed);
    }

    String required(String name) {
        String value = values.get(name);
        if (value == null) {
            throw new InvalidRequestException("Required parameter is missing: " + name);
        }
        return value;
    }

    IssueFilter issueFilter() {
        RuleCode rule = null;
        Severity severity = null;
        if (values.containsKey("rule")) {
            try {
                rule = RuleCode.valueOf(values.get("rule"));
            } catch (IllegalArgumentException exception) {
                throw new InvalidRequestException("Unknown rule code");
            }
        }
        if (values.containsKey("severity")) {
            try {
                severity = Severity.valueOf(values.get("severity").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new InvalidRequestException("Unknown severity");
            }
        }
        int limit = integer("limit", IssueFilter.DEFAULT_LIMIT);
        int offset = integer("offset", 0);
        try {
            return new IssueFilter(
                    required("run_id"), values.get("unit_id"), rule, severity, limit, offset);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(exception.getMessage());
        }
    }

    private int integer(String name, int defaultValue) {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.matches("[0-9]+")) {
            throw new InvalidRequestException("Parameter must be a non-negative integer: " + name);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("Parameter exceeds the integer range: " + name);
        }
    }
}
