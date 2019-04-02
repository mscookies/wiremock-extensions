package com.ninecookies.wiremock.extensions;

import static com.ninecookies.wiremock.extensions.Objects.describe;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tomakehurst.wiremock.common.Json;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;

/**
 * Provides convenient methods to parse, populate and replace placeholders in JSON strings.
 *
 * @author M.Scheepers
 * @since 0.0.6
 */
public class Placeholders {

    private static final Logger LOG = LoggerFactory.getLogger(Placeholders.class);
    private static final UnaryOperator<String> QUOTES = s -> String.format("\"%s\"", s);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\(.*?\\)");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\$\\(!(" +
            Stream.of(Keyword.keywords()).map(Keyword::keyword).collect(Collectors.joining("|"))
            + ")(.*)\\)");
    private static final ParseContext PARSE_CONTEXT = JsonPath.using(Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
            .options(Option.SUPPRESS_EXCEPTIONS).build());

    /**
     * Indicates whether the specified {@code string} is a placeholder.
     *
     * @param string the {@link String} to check.
     * @return {@code true} if the specified {@code string} is a placeholder; otherwise {@code false}.
     */
    public static boolean isPlaceholder(String string) {
        return PLACEHOLDER_PATTERN.asPredicate().test(string);
    }

    /**
     * Creates a {@link DocumentContext} for the specified {@code json} string.
     *
     * @param json the JSON {@link String} to create the {@link DocumentContext} for.
     * @return the {@link DocumentContext} for the specified {@code json} string or {@code null} if {@code json} is
     *         {@code null} or empty ({@code ""}.
     */
    public static DocumentContext documentContextOf(String json) {
        DocumentContext result = (json != null && json.trim().length() > 0) ? PARSE_CONTEXT.parse(json) : null;
        LOG.debug("documentContextOf('{}') -> '{}'", json, describe(result));
        return result;
    }

    /**
     * Parses the specified {@code json} for placeholder patterns.<br>
     * Note: placeholders for keywords will get their values immediately.
     *
     * @param json the JSON {@link String} that may contain placeholders.
     * @return a {@link Map} containing entries for all found placeholders.
     */
    public static Map<String, Object> parseJsonBody(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(json);
        while (matcher.find()) {
            String placeholder = matcher.group();
            if (result.containsKey(placeholder)) {
                continue;
            }
            result.put(placeholder, populatePlaceholder(placeholder));
        }
        LOG.debug("parseJsonBody('{}') -> '{}'", json, result);
        return result;
    }

    /**
     * Traverses the specified {@code placeholders} and parses the specified {@code json} for each placeholder who's
     * value is {@code null}.
     *
     * @param placeholders the {@link Map} of placeholders to parse values for.
     * @param json the JSON {@link String} to look for values.
     */
    public static void parsePlaceholderValues(Map<String, Object> placeholders, String json) {
        // if placeholders is null or empty or all values are set we are already done
        if (placeholders == null || placeholders.isEmpty() || !placeholders.containsValue(null)) {
            return;
        }
        DocumentContext documentContext = documentContextOf(json);
        for (Entry<String, Object> placeholder : placeholders.entrySet()) {
            // just look for placeholders who don't have a value yet
            if (placeholder.getValue() == null) {
                placeholder.setValue(populatePlaceholder(placeholder.getKey(), documentContext));
            }
        }
        LOG.debug("parsePlaceholderValues('{}', {})", placeholders, json);
    }

    public static String replaceValuesInJson(Map<String, Object> placeholders, String json) {
        String result = json;
        for (Entry<String, Object> placeholder : placeholders.entrySet()) {
            String pattern = placeholder.getKey();
            String value = String.valueOf(placeholder.getValue());
            String quotedPattern = QUOTES.apply(pattern);
            String jsonValue = Json.write(placeholder.getValue());
            // first replace all occurrences of "$(property.path)" with it's JSON value
            // and then check for in string replacements like "arbitrary text with $(embedded) placeholder"
            result = result.replace(quotedPattern, jsonValue).replace(pattern, value);
        }
        LOG.debug("replaceValuesInJson('{}', '{}') -> '{}'", placeholders, json, result);
        return result;
    }

    private static String patternToJsonPath(String pattern) {
        // change $( to // $. and remove trailing )
        String result = pattern.replaceFirst("\\$\\(", "\\$\\.").substring(0, pattern.length() - 1);
        LOG.debug("patternToJsonPath('{}') -> '{}'", pattern, result);
        return result;
    }

    public static Object populatePlaceholder(String pattern) {
        return populatePlaceholder(pattern, null);
    }

    public static Object populatePlaceholder(String pattern, DocumentContext documentContext) {
        Object result = null;
        Matcher isKey = KEYWORD_PATTERN.matcher(pattern);
        if (isKey.matches()) {
            LOG.debug(describe(isKey));
            Keyword keyword = Keyword.of(isKey.group(1));
            result = keyword.value(isKey.group(2));
        } else if (documentContext != null) {
            result = documentContext.read(patternToJsonPath(pattern));
        }
        LOG.debug("populatePlaceholder('{}', '{}') -> '{}'", pattern, describe(documentContext), describe(result));
        return result;
    }

    private static abstract class Keyword {

        public abstract String keyword();

        public abstract Object value(String arguments);

        private static final Random RANDOM_GENERATOR = new Random();
        private static final Pattern IS_CALCULATED = Pattern.compile("\\.plus\\[([HhMmSs]{1})([0-9\\-\\+]+)\\]");

        private static ChronoUnit stringToChronoUnit(String unit) {
            switch (unit.toLowerCase(Locale.ROOT)) {
                case "h":
                    return ChronoUnit.HOURS;
                case "m":
                    return ChronoUnit.MINUTES;
                case "s":
                    return ChronoUnit.SECONDS;
                default:
                    throw new IllegalArgumentException("Invalid unit for duration '" + unit + "'.");
            }
        }

        private static final Function<String, Instant> INSTANT_PROVIDER = s -> {
            Instant result = Instant.now();
            Matcher arguments = IS_CALCULATED.matcher(s);
            if (arguments.matches()) {
                return result
                        .plus(Duration.of(Long.parseLong(arguments.group(2)), stringToChronoUnit(arguments.group(1))));
            }
            if (s.startsWith(".plus[")) {
                // unmatched calculation pattern
                throw new IllegalArgumentException("invalid time calcuation pattern: '" + s + "'");
            }
            return result;
        };

        private static final Keyword UUID = new SimpleKeyword("UUID", s -> java.util.UUID.randomUUID().toString());
        private static final Keyword RANDOM = new SimpleKeyword("Random", s -> RANDOM_GENERATOR.nextInt());
        private static final Keyword INSTANT = new SimpleKeyword("Instant", s -> INSTANT_PROVIDER.apply(s).toString());
        private static final Keyword TIMESTAMP =
                new SimpleKeyword("Timestamp", s -> INSTANT_PROVIDER.apply(s).toEpochMilli());

        private static final Map<String, Keyword> VALUES = Collections.unmodifiableMap(Stream
                .of(UUID, RANDOM, INSTANT, TIMESTAMP)
                .collect(Collectors.toMap(Keyword::keyword, k -> k)));

        private static Keyword[] keywords() {
            return VALUES.values().toArray(new Keyword[VALUES.size()]);
        }

        private static Keyword of(String key) {
            return VALUES.get(key);
        }

        private static final class SimpleKeyword extends Keyword {
            private String keyword;
            private Function<String, Object> valueProvider;

            SimpleKeyword(String keyword, Function<String, Object> valueProvider) {
                this.keyword = keyword;
                this.valueProvider = valueProvider;
            }

            @Override
            public String keyword() {
                return keyword;
            }

            @Override
            public Object value(String arguments) {
                return valueProvider.apply(arguments);
            }
        }
    }

    protected Placeholders() {
    }
}
