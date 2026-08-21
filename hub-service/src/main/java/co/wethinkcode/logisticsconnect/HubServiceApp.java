package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HubServiceApp {

    record Hub(String hubId, String province, String sortingCenter, boolean active) {}

    private static final String INGESTION_URL = "http://localhost:7050/hubs";

    public static void main(String[] args) throws Exception {
        Map<String, Hub> hubsById = loadHubsFromIngestion();
        System.out.println("hub-service: loaded " + hubsById.size() + " hubs from ingestion-service");

        Javalin app = Javalin.create().start(7051);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs", ctx -> ctx.json(hubsById.values()));

        app.get("/hubs/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId").toUpperCase();
            Hub hub = hubsById.get(hubId);
            if (hub == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "hub not found", "hubId", hubId));
                return;
            }
            ctx.json(hub);
        });
    }

    static Map<String, Hub> loadHubsFromIngestion() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(INGESTION_URL)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "ingestion-service returned " + response.statusCode() + " — is it running on :7050?");
        }

        ObjectMapper mapper = new ObjectMapper();
        List<Hub> hubs = mapper.readValue(response.body(), mapper.getTypeFactory()
                .constructCollectionType(List.class, Hub.class));

        Map<String, Hub> byId = new LinkedHashMap<>();
        for (Hub h : hubs) {
            byId.put(h.hubId().toUpperCase(), h);
        }
        return byId;
    }
}