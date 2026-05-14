package com.yiming.mc_ai_player.monitor;

/**
 * Thread-safe holder for the single active MonitoringSession.
 * All access is expected from the server thread; volatile for visibility.
 */
public class MonitoringState {

    private static volatile MonitoringSession activeSession;

    public static MonitoringSession getActiveSession() {
        return activeSession;
    }

    public static void setActiveSession(MonitoringSession session) {
        activeSession = session;
    }

    public static void clearSession() {
        activeSession = null;
    }
}
