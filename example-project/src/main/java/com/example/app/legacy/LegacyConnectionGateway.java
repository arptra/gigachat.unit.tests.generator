package com.example.app.legacy;

public final class LegacyConnectionGateway {

    private LegacyConnectionGateway() {
    }

    public static void openRequiredChannel(String channelName) {
        throw new IllegalStateException("Real DB connection attempt for " + channelName);
    }
}
