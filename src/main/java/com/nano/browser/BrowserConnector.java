package com.nano.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
