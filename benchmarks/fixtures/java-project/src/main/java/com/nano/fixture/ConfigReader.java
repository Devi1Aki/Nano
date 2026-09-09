package com.nano.fixture;

public final class ConfigReader {
    public int readPort(String value) {
        return Integer.parseInt(value.trim());
    }
}
