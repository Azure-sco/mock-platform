package com.xuntian.mock.common;

public final class Masking {

    private Masking() {
    }

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return repeat('*', value.length());
        }
        return value.substring(0, 2)
                + repeat('*', value.length() - 4)
                + value.substring(value.length() - 2);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
