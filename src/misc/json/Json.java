package misc.json;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(Reader reader) throws IOException {
        return new Parser(reader).parse();
    }

    public static JsonObject parseObject(Reader reader) throws IOException {
        Object value = parse(reader);
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new JsonParseException("Expected JSON object root");
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(builder, string);
        } else if (value instanceof Character character) {
            writeString(builder, character.toString());
        } else if (value instanceof Boolean bool) {
            builder.append(bool);
        } else if (value instanceof Number number) {
            writeNumber(builder, number);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(builder, map);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(builder, iterable);
        } else {
            writeString(builder, value.toString());
        }
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            writeValue(builder, iterator.next());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    private static void writeNumber(StringBuilder builder, Number number) {
        if (number instanceof Double d && !Double.isFinite(d)) {
            throw new IllegalArgumentException("JSON does not support non-finite double values: " + d);
        }
        if (number instanceof Float f && !Float.isFinite(f)) {
            throw new IllegalArgumentException("JSON does not support non-finite float values: " + f);
        }
        builder.append(number);
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private static final int EOF = -1;

        private final Reader reader;
        private int current;
        private int position = 0;

        private Parser(Reader reader) throws IOException {
            this.reader = reader;
            advance();
        }

        private Object parse() throws IOException {
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (current != EOF) {
                throw error("Unexpected trailing character");
            }
            return value;
        }

        private Object readValue() throws IOException {
            return switch (current) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readNumber();
                case EOF -> throw error("Unexpected end of input");
                default -> throw error("Unexpected character '" + (char) current + "'");
            };
        }

        private JsonObject readObject() throws IOException {
            expect('{');
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (current == '}') {
                advance();
                return object;
            }
            while (true) {
                skipWhitespace();
                if (current != '"') {
                    throw error("Expected object key string");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, readValue());
                skipWhitespace();
                if (current == '}') {
                    advance();
                    return object;
                }
                expect(',');
            }
        }

        private JsonArray readArray() throws IOException {
            expect('[');
            JsonArray array = new JsonArray();
            skipWhitespace();
            if (current == ']') {
                advance();
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(readValue());
                skipWhitespace();
                if (current == ']') {
                    advance();
                    return array;
                }
                expect(',');
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (current != '"') {
                if (current == EOF) {
                    throw error("Unterminated string");
                }
                if (current < 0x20) {
                    throw error("Unescaped control character in string");
                }
                if (current == '\\') {
                    advance();
                    builder.append(readEscapedCharacter());
                } else {
                    builder.append((char) current);
                    advance();
                }
            }
            expect('"');
            return builder.toString();
        }

        private char readEscapedCharacter() throws IOException {
            char escaped = switch (current) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> readUnicodeEscape();
                case EOF -> throw error("Unterminated escape sequence");
                default -> throw error("Invalid escape character '" + (char) current + "'");
            };
            if (current != EOF) {
                advance();
            }
            return escaped;
        }

        private char readUnicodeEscape() throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                advance();
                int digit = Character.digit(current, 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object readLiteral(String literal, Object value) throws IOException {
            for (int i = 0; i < literal.length(); i++) {
                if (current != literal.charAt(i)) {
                    throw error("Expected '" + literal + "'");
                }
                advance();
            }
            return value;
        }

        private Number readNumber() throws IOException {
            StringBuilder builder = new StringBuilder();
            if (current == '-') {
                builder.append((char) current);
                advance();
            }

            if (current == '0') {
                builder.append('0');
                advance();
                if (isDigit(current)) {
                    throw error("Leading zero in number");
                }
            } else if (isDigitOneToNine(current)) {
                do {
                    builder.append((char) current);
                    advance();
                } while (isDigit(current));
            } else {
                throw error("Expected digit");
            }

            boolean floatingPoint = false;
            if (current == '.') {
                floatingPoint = true;
                builder.append('.');
                advance();
                if (!isDigit(current)) {
                    throw error("Expected digit after decimal point");
                }
                do {
                    builder.append((char) current);
                    advance();
                } while (isDigit(current));
            }

            if (current == 'e' || current == 'E') {
                floatingPoint = true;
                builder.append((char) current);
                advance();
                if (current == '+' || current == '-') {
                    builder.append((char) current);
                    advance();
                }
                if (!isDigit(current)) {
                    throw error("Expected exponent digit");
                }
                do {
                    builder.append((char) current);
                    advance();
                } while (isDigit(current));
            }

            String number = builder.toString();
            if (floatingPoint) {
                return Double.parseDouble(number);
            }
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException ignored) {
                BigInteger bigInteger = new BigInteger(number);
                if (bigInteger.bitLength() < Long.SIZE) {
                    return bigInteger.longValue();
                }
                return new BigDecimal(bigInteger);
            }
        }

        private void skipWhitespace() throws IOException {
            while (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                advance();
            }
        }

        private void expect(char expected) throws IOException {
            if (current != expected) {
                throw error("Expected '" + expected + "'");
            }
            advance();
        }

        private void advance() throws IOException {
            current = reader.read();
            position++;
        }

        private JsonParseException error(String message) {
            return new JsonParseException(message + " at character " + Math.max(0, position - 1));
        }

        private boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        private boolean isDigitOneToNine(int c) {
            return c >= '1' && c <= '9';
        }
    }
}
