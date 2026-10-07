package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class TransitServiceApp {

    record Hub(String hubId, String province, String sortingCenter, boolean active) {}
    record DelayStage(String hubId, int stage) {}
    record EtaResponse(String hubId, String sortingCenter, int delayStage, double etaHours) {}

    private static final String HUB_SERVICE_URL = "http://localhost:7051/hubs/";
    private static final String DELAY_STAGE_SERVICE_URL = "http://localhost:7052/delay-stage/";

    private static final double BASE_HOURS = 4.0;
    private static final double HOURS_PER_STAGE = 1.5;

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7053);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/eta/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").toUpperCase();

            Hub hub = fetchHub(hubId);
            if (hub == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "hub not found", "hubId", hubId));
                return;
            }

            int stage = fetchDelayStage(hubId);
            double eta = BASE_HOURS + (stage * HOURS_PER_STAGE);

            ctx.json(new EtaResponse(hub.hubId(), hub.sortingCenter(), stage, eta));
        });
    }

    static Hub fetchHub(String hubId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(HUB_SERVICE_URL + hubId)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "hub-service returned " + response.statusCode() + " — is it running on :7051?");
        }
        return mapper.readValue(response.body(), Hub.class);
    }

    /**
     * Stage 3 TODO: replace this call with a read from an in-memory map kept
     * up to date via an ActiveMQ subscription to package-status-topic.
     */
    static int fetchDelayStage(String hubId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(DELAY_STAGE_SERVICE_URL + hubId)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "delay-stage-service returned " + response.statusCode() + " — is it running on :7052?");
        }
        DelayStage ds = mapper.readValue(response.body(), DelayStage.class);
        return ds.stage();
    }
}