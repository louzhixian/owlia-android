package app.botdrop.ops;

import org.json.JSONObject;

import java.util.List;

/**
 * High-level entrypoint for future UI and LLM integration.
 */
public class OpsOrchestrator {

    private final ConfigRepository configRepository;
    private final DoctorEngine doctorEngine;
    private final SafeExecutor safeExecutor;
    private final GatewayController gatewayController;

    public OpsOrchestrator(ConfigRepository configRepository,
                           DoctorEngine doctorEngine,
                           SafeExecutor safeExecutor,
                           GatewayController gatewayController) {
        this.configRepository = configRepository;
        this.doctorEngine = doctorEngine;
        this.safeExecutor = safeExecutor;
        this.gatewayController = gatewayController;
    }

    public DoctorReport runDoctor(RuntimeProbe runtimeProbe) {
        JSONObject config = configRepository.read();
        return doctorEngine.diagnose(config, runtimeProbe);
    }

    public SafeExecutor.ExecutionResult applyFixes(List<FixAction> actions) {
        return safeExecutor.applyFixes(actions);
    }

    public SafeExecutor.PreviewResult previewFixes(List<FixAction> actions) {
        return safeExecutor.previewFixes(actions);
    }

    public void restartGateway(GatewayController.Callback callback) {
        gatewayController.restart(callback);
    }
}
