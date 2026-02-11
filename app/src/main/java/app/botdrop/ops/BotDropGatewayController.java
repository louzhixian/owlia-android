package app.botdrop.ops;

import app.botdrop.BotDropService;

public class BotDropGatewayController implements GatewayController {

    private final BotDropService service;

    public BotDropGatewayController(BotDropService service) {
        this.service = service;
    }

    @Override
    public void restart(Callback callback) {
        service.restartGateway(result -> {
            if (callback == null) return;
            String message = result.success ? result.stdout : (result.stderr == null ? "" : result.stderr);
            callback.onResult(result.success, message);
        });
    }
}
