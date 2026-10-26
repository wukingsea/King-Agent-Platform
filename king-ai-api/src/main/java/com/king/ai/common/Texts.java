package com.king.ai.common;

public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    public static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public static String oneLine(String value, int max) {
        if (value == null) {
            return "";
        }
        String line = value.replaceAll("\\s+", " ").trim();
        return line.length() <= max ? line : line.substring(0, max);
    }

    public static String maskSecret(String secret) {
        if (isBlank(secret)) {
            return "";
        }
        String value = secret.trim();
        if (value.length() <= 8) {
            return "••••••••";
        }
        return value.substring(0, 3) + "••••••••" + value.substring(value.length() - 4);
    }

    public static boolean isValidHttpUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isValidVisitorId(String visitorId) {
        return visitorId != null && visitorId.matches("[A-Za-z0-9_-]{8,64}");
    }
}
