import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, strict JSON reader/writer helpers.
 *
 * The older flat parser in ApiServer cannot read nested data such as a bill
 * with a list of items, so the invoice endpoints use this one.
 *
 * parse() returns: Map (object), List (array), String, BigDecimal (number),
 * Boolean, or null.
 */
public final class Json {

    private Json() {}

    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    // ---------------- reading ----------------

    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("Empty request");
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.readValue(0);
        parser.skipSpace();
        if (!parser.atEnd()) throw new JsonException("Unexpected data after JSON");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) throws JsonException {
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new JsonException("A JSON object was expected");
    }

    public static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return "";
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
        return String.valueOf(value);
    }

    /** Returns the number at key, or null when missing/not numeric. */
    public static BigDecimal num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                return new BigDecimal(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---------------- writing ----------------

    public static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Plain decimal text such as 123.45, never scientific notation. */
    public static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    // ---------------- parser ----------------

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object readValue(int depth) throws JsonException {
            if (depth > 20) throw new JsonException("JSON is nested too deeply");
            if (atEnd()) throw new JsonException("Unexpected end of JSON");

            char c = s.charAt(i);

            if (c == '{') return readObject(depth);
            if (c == '[') return readArray(depth);
            if (c == '"') return readString();
            if (c == 't') return readWord("true", Boolean.TRUE);
            if (c == 'f') return readWord("false", Boolean.FALSE);
            if (c == 'n') return readWord("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return readNumber();

            throw new JsonException("Unexpected character '" + c + "' in JSON");
        }

        Object readWord(String word, Object result) throws JsonException {
            if (!s.startsWith(word, i)) throw new JsonException("Invalid JSON value");
            i += word.length();
            return result;
        }

        Map<String, Object> readObject(int depth) throws JsonException {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipSpace();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipSpace();
                if (atEnd() || s.charAt(i) != '"') throw new JsonException("Object key expected");
                String key = readString();
                skipSpace();
                if (atEnd() || s.charAt(i) != ':') throw new JsonException("':' expected");
                i++;
                skipSpace();
                map.put(key, readValue(depth + 1));
                skipSpace();
                if (atEnd()) throw new JsonException("Unexpected end of JSON");
                char c = s.charAt(i++);
                if (c == '}') return map;
                if (c != ',') throw new JsonException("',' or '}' expected");
            }
        }

        List<Object> readArray(int depth) throws JsonException {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipSpace();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(readValue(depth + 1));
                skipSpace();
                if (atEnd()) throw new JsonException("Unexpected end of JSON");
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') throw new JsonException("',' or ']' expected");
            }
        }

        String readString() throws JsonException {
            StringBuilder out = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (atEnd()) throw new JsonException("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) throw new JsonException("Unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new JsonException("Bad unicode escape");
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Bad unicode escape");
                        }
                        i += 4;
                        break;
                    default:
                        throw new JsonException("Bad escape in string");
                }
            }
        }

        BigDecimal readNumber() throws JsonException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number");
            }
        }
    }
}
