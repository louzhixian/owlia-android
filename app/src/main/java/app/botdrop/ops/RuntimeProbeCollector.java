package app.botdrop.ops;

public interface RuntimeProbeCollector {

    interface Callback {
        void onProbe(RuntimeProbe probe);
    }

    void collect(Callback callback);
}
