package pixelclipboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec, keeping this desktop app free of third-party dependencies. */
final class Json {
    private final String text;
    private int position;

    private Json(String text) { this.text = text; }

    static Object parse(String text) {
        Json parser = new Json(text.startsWith("\uFEFF") ? text.substring(1) : text);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != parser.text.length()) throw parser.error("Unexpected trailing text");
        return result;
    }

    private Object value(int depth) {
        if (depth > 64) throw error("JSON is too deeply nested");
        space();
        if (position >= text.length()) throw error("Expected a value");
        char next = text.charAt(position);
        if (next == '"') return string();
        if (next == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (take('}')) return map;
            do {
                space();
                if (position >= text.length() || text.charAt(position) != '"') throw error("Expected an object key");
                String key = string();
                space();
                require(':');
                if (map.containsKey(key)) throw error("Duplicate object key");
                map.put(key, value(depth + 1));
                space();
                if (take('}')) return map;
                require(',');
            } while (true);
        }
        if (next == '[') {
            position++;
            List<Object> list = new ArrayList<>();
            space();
            if (take(']')) return list;
            do {
                list.add(value(depth + 1));
                space();
                if (take(']')) return list;
                require(',');
            } while (true);
        }
        for (String literal : new String[] { "true", "false", "null" }) {
            if (text.startsWith(literal, position)) {
                position += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = position;
        take('-');
        if (!take('0')) {
            if (!digit() || text.charAt(position) == '0') throw error("Expected a number");
            while (digit()) position++;
        }
        if (take('.')) {
            if (!digit()) throw error("Expected digits after decimal point");
            while (digit()) position++;
        }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            if (!digit()) throw error("Expected exponent digits");
            while (digit()) position++;
        }
        try {
            return new java.math.BigDecimal(text.substring(start, position));
        } catch (NumberFormatException exception) {
            throw error("Invalid number");
        }
    }

    private String string() {
        require('"');
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char next = text.charAt(position++);
            if (next == '"') return result.toString();
            if (next < 0x20) throw error("Unescaped control character");
            if (next != '\\') { result.append(next); continue; }
            if (position == text.length()) throw error("Unfinished escape");
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) throw error("Incomplete Unicode escape");
                    String digits = text.substring(position, position + 4);
                    if (!digits.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                    result.append((char) Integer.parseInt(digits, 16));
                    position += 4;
                }
                default -> throw error("Unknown escape");
            }
        }
        throw error("Unterminated string");
    }

    private boolean digit() { return position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9'; }
    private void space() { while (position < text.length() && " \r\n\t".indexOf(text.charAt(position)) >= 0) position++; }
    private boolean take(char wanted) {
        if (position < text.length() && text.charAt(position) == wanted) { position++; return true; }
        return false;
    }
    private void require(char wanted) { if (!take(wanted)) throw error("Expected '" + wanted + "'"); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at character " + position); }

    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            StringBuilder result = new StringBuilder("\"");
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                switch (c) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> {
                        if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                        else result.append(c);
                    }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(write(key.toString()) + ":" + write(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object item : list) entries.add(write(item));
            return "[" + String.join(",", entries) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }
}
