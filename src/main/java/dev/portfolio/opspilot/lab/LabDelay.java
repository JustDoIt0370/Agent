package dev.portfolio.opspilot.lab;

/** Invoked by H2, so SQL timing includes the injected wait inside the JDBC statement. */
public final class LabDelay {
    private LabDelay() {}
    public static int pause(int milliseconds) throws InterruptedException {
        Thread.sleep(Math.clamp(milliseconds, 0, 2000));
        return milliseconds;
    }
}
