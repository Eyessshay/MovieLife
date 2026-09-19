package com.movielife.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class ReviewContentSanitizer {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[A-Z]{2,}(?![\\p{L}\\p{N}.-])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern CHINA_ID = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?![0-9Xx])");

    public String sanitize(String raw, int maxCodePoints) {
        if (maxCodePoints < 0) {
            throw new IllegalArgumentException("maxCodePoints must not be negative");
        }
        String value = raw == null ? "" : raw;
        value = Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        value = value.trim();
        value = EMAIL.matcher(value).replaceAll("[EMAIL]");
        value = PHONE.matcher(value).replaceAll("[PHONE]");
        value = CHINA_ID.matcher(value).replaceAll("[ID]");
        return truncate(value, maxCodePoints);
    }

    public String truncate(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || maxCodePoints == 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    public int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
