package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.Locale;
import java.util.Optional;

public final class CompiledMatchRule {

    private static final int MAX_REGEX_LENGTH = 500;

    private final RuleType type;
    private final Operator operator;
    private final String key;
    private final String expected;
    private final boolean caseSensitive;
    private final CompiledJsonPath jsonPath;
    private final RegexSource regexSource;
    private final Pattern regex;

    public CompiledMatchRule(String type, String operator, String key, String expected, boolean caseSensitive) {
        this.type = RuleType.valueOf(type.toUpperCase(Locale.ROOT));
        this.operator = Operator.valueOf(operator.toUpperCase(Locale.ROOT));
        this.key = key;
        this.expected = expected;
        this.caseSensitive = caseSensitive;
        if (this.type == RuleType.REGEX && this.operator != Operator.REGEX) {
            throw new IllegalArgumentException("REGEX rule type requires REGEX operator");
        }
        if (this.type == RuleType.HEADER && RuntimeRequest.isSensitiveHeader(key)) {
            throw new IllegalArgumentException("Sensitive headers cannot be used by Match Rules");
        }
        this.jsonPath = this.type == RuleType.JSON_PATH ? CompiledJsonPath.compile(key) : null;
        this.regexSource = this.type == RuleType.REGEX ? RegexSource.compile(key) : null;
        if (this.type == RuleType.REGEX || this.operator == Operator.REGEX) {
            if (expected == null || expected.length() > MAX_REGEX_LENGTH) {
                throw new IllegalArgumentException("Regex is missing or exceeds 500 characters");
            }
            try {
                this.regex = Pattern.compile(caseSensitive ? expected : "(?i:" + expected + ")");
            } catch (PatternSyntaxException failure) {
                throw new IllegalArgumentException("Regex is not valid RE2/J syntax", failure);
            }
        } else {
            this.regex = null;
        }
    }

    public boolean matches(RuntimeRequest request, JsonNode body, String businessNo) {
        Optional<String> actual = switch (type) {
            case HEADER -> request.firstHeader(key);
            case QUERY -> request.firstQuery(key);
            case JSON_PATH -> jsonPath.read(body).map(CompiledMatchRule::asComparableText);
            case BUSINESS_NO -> Optional.ofNullable(businessNo);
            case REGEX -> regexSource.read(request, body, businessNo);
        };
        if (type == RuleType.REGEX) {
            return actual.map(candidate -> regex.matches(candidate)).orElse(false);
        }
        return evaluate(actual);
    }

    private boolean evaluate(Optional<String> actual) {
        if (operator == Operator.EXISTS) {
            return actual.isPresent();
        }
        if (actual.isEmpty()) {
            return false;
        }
        String candidate = actual.get();
        String target = expected == null ? "" : expected;
        if (!caseSensitive && operator != Operator.REGEX) {
            candidate = candidate.toLowerCase(Locale.ROOT);
            target = target.toLowerCase(Locale.ROOT);
        }
        return switch (operator) {
            case EQ -> candidate.equals(target);
            case NE -> !candidate.equals(target);
            case CONTAINS -> candidate.contains(target);
            case PREFIX -> candidate.startsWith(target);
            case REGEX -> regex.matches(candidate);
            case EXISTS -> true;
        };
    }

    private static String asComparableText(JsonNode value) {
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private enum RuleType { HEADER, QUERY, JSON_PATH, REGEX, BUSINESS_NO }
    private enum Operator { EQ, NE, CONTAINS, PREFIX, REGEX, EXISTS }

    private record RegexSource(Source type, String key, CompiledJsonPath jsonPath) {

        static RegexSource compile(String source) {
            if (source == null) {
                throw new IllegalArgumentException("REGEX rule key is required");
            }
            int separator = source.indexOf(':');
            if (separator <= 0 || separator == source.length() - 1) {
                throw new IllegalArgumentException(
                        "REGEX rule key must be HEADER:name, QUERY:name, JSON_PATH:$.path, or BUSINESS_NO:value");
            }
            Source type = Source.valueOf(source.substring(0, separator).toUpperCase(Locale.ROOT));
            String key = source.substring(separator + 1);
            if (type == Source.HEADER && RuntimeRequest.isSensitiveHeader(key)) {
                throw new IllegalArgumentException("Sensitive headers cannot be used by REGEX rules");
            }
            return new RegexSource(
                    type,
                    key,
                    type == Source.JSON_PATH ? CompiledJsonPath.compile(key) : null);
        }

        Optional<String> read(RuntimeRequest request, JsonNode body, String businessNo) {
            return switch (type) {
                case HEADER -> request.firstHeader(key);
                case QUERY -> request.firstQuery(key);
                case JSON_PATH -> jsonPath.read(body).map(CompiledMatchRule::asComparableText);
                case BUSINESS_NO -> Optional.ofNullable(businessNo);
            };
        }

        private enum Source { HEADER, QUERY, JSON_PATH, BUSINESS_NO }
    }
}
