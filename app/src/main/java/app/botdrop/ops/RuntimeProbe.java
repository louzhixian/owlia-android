package app.botdrop.ops;

public class RuntimeProbe {

    public final boolean gatewayProcessRunning;
    public final boolean gatewayHttpReachable;
    public final String recentGatewayErrors;

    public RuntimeProbe(boolean gatewayProcessRunning, boolean gatewayHttpReachable, String recentGatewayErrors) {
        this.gatewayProcessRunning = gatewayProcessRunning;
        this.gatewayHttpReachable = gatewayHttpReachable;
        this.recentGatewayErrors = recentGatewayErrors;
    }
}
