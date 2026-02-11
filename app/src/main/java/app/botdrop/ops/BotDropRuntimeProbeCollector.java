package app.botdrop.ops;

import app.botdrop.BotDropService;

/**
 * Reads lightweight runtime health signals from the existing BotDropService.
 */
public class BotDropRuntimeProbeCollector implements RuntimeProbeCollector {

    private final BotDropService service;

    public BotDropRuntimeProbeCollector(BotDropService service) {
        this.service = service;
    }

    @Override
    public void collect(Callback callback) {
        service.isGatewayRunning(statusResult -> {
            boolean running = statusResult.success && "running".equals(statusResult.stdout.trim());
            service.executeCommand("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18789/", httpResult -> {
                boolean reachable = httpResult.success && "200".equals(httpResult.stdout.trim());
                service.executeCommand(
                    "if [ -f ~/.openclaw/gateway.log ]; then grep -i 'error\\|fatal\\|failed' ~/.openclaw/gateway.log | tail -20; fi",
                    logResult -> {
                        String errors = logResult.stdout == null ? "" : logResult.stdout.trim();
                        if (callback != null) callback.onProbe(new RuntimeProbe(running, reachable, errors));
                    }
                );
            });
        });
    }
}
