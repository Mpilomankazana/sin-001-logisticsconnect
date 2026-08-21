package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    record StageUpdate(int stage) {}
    record DelayStage(String hubId, int stage) {}

    private static final Map<String, Integer> stagesByHub = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7052);

        app.get("/health", ctx -> ctx.result("OK"));

        // GET /delay-stage/{hubId} — current stage, defaulting to 0 if unseen
        app.get("/delay-stage/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").toUpperCase();
            int stage = stagesByHub.getOrDefault(hubId, 0);
            ctx.json(new DelayStage(hubId, stage));
        });

        // POST /delay-stage/{hubId} — set the stage, body: { "stage": N }
        app.post("/delay-stage/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").toUpperCase();
            StageUpdate update = ctx.bodyAsClass(StageUpdate.class);

            if (update.stage() < 0 || update.stage() > 8) {
                ctx.status(HttpStatus.BAD_REQUEST)
                   .json(Map.of("error", "stage must be between 0 and 8", "received", update.stage()));
                return;
            }

            stagesByHub.put(hubId, update.stage());
            // Stage 3 TODO: publish to package-status-topic here once MQ is wired in

            ctx.json(new DelayStage(hubId, update.stage()));
        });
    }
}
