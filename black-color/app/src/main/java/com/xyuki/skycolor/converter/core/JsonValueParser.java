package com.xyuki.skycolor.converter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON parser used by the converter core.
 *
 * <p>The Android application deliberately has no JSON library dependency. Keeping the parser
 * here also makes the format validation runnable in ordinary JVM unit tests.</p>
 */
public final class JsonValueParser {
    private final String input;
    private int position;

    private JsonValueParser(String input) {
        this.input = input;
    }

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 内容不能为空");
        }
        JsonValueParser parser = new JsonValueParser(json);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("JSON 末尾存在多余内容");
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (atEnd()) {
            throw error("JSON 内容为空");
        }
        char character = input.charAt(position);
        switch (character) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                readLiteral("true");
                return Boolean.TRUE;
            case 'f':
                readLiteral("false");
                return Boolean.FALSE;
            case 'n':
                readLiteral("null");
                return null;
            default:
                if (character == '-' || (character >= '0' && character <= '9')) {
                    return readNumber();
                }
                throw error("无法识别的 JSON 值");
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return result;
        }
        while (true) {
            skipWhitespace();
            if (atEnd() || input.charAt(position) != '"') {
                throw error("对象键必须是字符串");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            result.put(key, readValue());
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (!atEnd()) {
            char character = input.charAt(position++);
            if (character == '"') {
                return result.toString();
            }
            if (character == '\\') {
                if (atEnd()) {
                    throw error("字符串转义不完整");
                }
                char escape = input.charAt(position++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        result.append(escape);
                        break;
                    case 'b':
                        result.append('\b');
                        break;
                    case 'f':
                        result.append('\f');
                        break;
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case 'u':
                        result.append(readUnicodeEscape());
                        break;
                    default:
                        throw error("不支持的字符串转义");
                }
            } else {
                if (character < 0x20) {
                    throw error("字符串中不能直接包含控制字符");
                }
                result.append(character);
            }
        }
        throw error("字符串没有闭合引号");
    }

    private char readUnicodeEscape() {
        if (position + 4 > input.length()) {
            throw error("Unicode 转义不完整");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) {
                throw error("Unicode 转义包含非法字符");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Number readNumber() {
        int start = position;
        if (consume('-')) {
            if (atEnd()) {
                throw error("数字缺少整数部分");
            }
        }
        if (consume('0')) {
            if (!atEnd() && Character.isDigit(input.charAt(position))) {
                throw error("数字不能包含多余的前导零");
            }
        } else {
            requireDigit();
            while (!atEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
        }
        boolean decimal = false;
        if (consume('.')) {
            decimal = true;
            requireDigit();
            while (!atEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
        }
        if (!atEnd() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
            decimal = true;
            position++;
            if (!atEnd() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                position++;
            }
            requireDigit();
            while (!atEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
        }
        String number = input.substring(start, position);
        try {
            if (!decimal) {
                return Long.valueOf(number);
            }
            return Double.valueOf(number);
        } catch (NumberFormatException exception) {
            throw error("数字超出支持范围");
        }
    }

    private void readLiteral(String literal) {
        if (!input.regionMatches(position, literal, 0, literal.length())) {
            throw error("非法 JSON 字面量");
        }
        position += literal.length();
    }

    private void requireDigit() {
        if (atEnd() || !Character.isDigit(input.charAt(position))) {
            throw error("数字缺少数字位");
        }
    }

    private void skipWhitespace() {
        while (!atEnd()) {
            char character = input.charAt(position);
            if (character == ' ' || character == '\t' || character == '\r' || character == '\n') {
                position++;
            } else {
                return;
            }
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (atEnd() || input.charAt(position) != expected) {
            throw error("期望字符 '" + expected + "'");
        }
        position++;
    }

    private boolean consume(char expected) {
        if (!atEnd() && input.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private boolean atEnd() {
        return position >= input.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + "（位置 " + position + "）");
    }
}
