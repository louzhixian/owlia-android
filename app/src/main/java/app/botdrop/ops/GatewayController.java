package app.botdrop.ops;

public interface GatewayController {

    interface Callback {
        void onResult(boolean success, String message);
    }

    void restart(Callback callback);
}
