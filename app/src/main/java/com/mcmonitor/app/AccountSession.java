package com.mcmonitor.app;

public final class AccountSession {
    public String accessToken;
    public String refreshToken;
    public String userId;
    public String username;

    public AccountSession(String accessToken, String refreshToken, String userId, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.username = username;
    }
}
