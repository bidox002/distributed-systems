package api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON codec for the small, known API payloads used by this project. */
public final class Json {
    private Json() { }

    public static Map<String, Object> object(String json) {
        Object value = new Parser(json).parseValue();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return '"' + escape((String) value) + '"';
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                out.append(stringify(String.valueOf(entry.getKey()))).append(':').append(stringify(entry.getValue()));
                first = false;
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                out.append(stringify(item));
                first = false;
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < length; i++) items.add(java.lang.reflect.Array.get(value, i));
            return stringify(items);
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) { this.text = text; }

        private Object parseValue() {
            skipSpace();
            if (pos >= text.length()) throw new IllegalArgumentException("Empty JSON");
            char next = text.charAt(pos);
            if (next == '{') return parseObject();
            if (next == '[') return parseArray();
            if (next == '"') return parseString();
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipSpace();
            if (consume('}')) return result;
            do {
                skipSpace();
                String key = parseString();
                skipSpace(); expect(':');
                result.put(key, parseValue());
                skipSpace();
            } while (consume(','));
            expect('}');
            return result;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipSpace();
            if (consume(']')) return result;
            do { result.add(parseValue()); skipSpace(); } while (consume(','));
            expect(']');
            return result;
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (pos < text.length() && text.charAt(pos) != '"') {
                char c = text.charAt(pos++);
                if (c == '\\' && pos < text.length()) {
                    char escaped = text.charAt(pos++);
                    if (escaped == 'n') result.append('\n');
                    else if (escaped == 'r') result.append('\r');
                    else if (escaped == 't') result.append('\t');
                    else result.append(escaped);
                } else result.append(c);
            }
            expect('"');
            return result.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            String number = text.substring(start, pos);
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.valueOf(number);
                }
                try {
                    return Integer.valueOf(number);
                } catch (NumberFormatException tooLargeForInteger) {
                    return Long.valueOf(number);
                }
            }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid number at position " + start); }
        }

        private void skipSpace() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private boolean consume(char expected) { if (pos < text.length() && text.charAt(pos) == expected) { pos++; return true; } return false; }
        private void expect(char expected) { skipSpace(); if (!consume(expected)) throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos); }
    }
}
