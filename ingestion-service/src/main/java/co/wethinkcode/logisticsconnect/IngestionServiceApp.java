package co.wethinkcode.logisticsconnect;

import com.opencsv.CSVReader;
import io.javalin.Javalin;

import java.io.FileReader;
import java.io.Reader;
import java.util.*;

public class IngestionServiceApp {

    record Hub(String hubId, String province, String sortingCenter, boolean active) {}

    public static void main(String[] args) throws Exception {
        List<Hub> hubs = loadAndClean("src/main/resources/hubs-global.csv");

        Javalin app = Javalin.create().start(7050);
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", ctx -> ctx.json(hubs));
    }

    static List<Hub> loadAndClean(String path) throws Exception {
        List<Hub> raw = new ArrayList<>();

        try (Reader reader = new FileReader(path); CSVReader csv = new CSVReader(reader)) {
            String[] row;
            csv.readNext(); // skip header
            while ((row = csv.readNext()) != null) {
                if (row.length < 4) continue; // skip malformed/blank lines

                String hubId = clean(row[0]).toUpperCase();
                String province = normalizeProvince(clean(row[1]));
                String sortingCenter = collapseSpaces(clean(row[2]));
                boolean active = parseBoolean(clean(row[3]));

                if (hubId.isEmpty() || sortingCenter.isEmpty()) continue; // no usable identity
                raw.add(new Hub(hubId, province, sortingCenter, active));
            }
        }

        return deduplicate(raw);
    }

    // --- field cleaning -----------------------------------------------

    static String clean(String s) {
        return s == null ? "" : collapseSpaces(s.trim());
    }

    static String collapseSpaces(String s) {
        return s.trim().replaceAll("\\s{2,}", " ");
    }

    static String normalizeProvince(String raw) {
        String key = raw.toLowerCase().replace("-", " ").trim();
        if (key.isEmpty()) return "Unknown";
        // fold regional spelling/format variants onto one canonical name
        return switch (key) {
            case "gauteng" -> "Gauteng";
            case "western cape" -> "Western Cape";
            case "kwazulu natal", "kwa zulu natal" -> "KwaZulu-Natal";
            case "eastern cape" -> "Eastern Cape";
            case "free state" -> "Free State";
            case "limpopo" -> "Limpopo";
            case "north west" -> "North West";
            case "mpumalanga" -> "Mpumalanga";
            case "northern cape" -> "Northern Cape";
            default -> capitalize(raw.trim());
        };
    }

    static String capitalize(String s) {
        String[] words = s.toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    static boolean parseBoolean(String raw) {
        String v = raw.toLowerCase().trim();
        return switch (v) {
            case "y", "yes", "true", "1" -> true;
            case "n", "no", "false", "0" -> false;
            // "unknown", "n/a", "", "tbd", "-", "nan" -> no signal either way;
            // default to false (fail-closed: don't advertise a hub as active
            // without evidence — safer for downstream routing/ETA decisions)
            default -> false;
        };
    }

    // --- deduplication --------------------------------------------------

    /**
     * Groups records describing the same real-world hub (same province +
     * sorting center, case/spacing already normalized above) and collapses
     * them into one record.
     *
     * Tie-break rule for conflicting `active` flags: majority vote among the
     * duplicates: if most copies say active, treat the hub as active.
     * This assumes stale/erroneous entries are the minority, which held for
     * every duplicate group in this dataset (H-500/H-504/H-510/H-515 →
     * 3 active vs 1 not). The canonical hub_id kept is the lowest ID in the
     * group, since lower IDs appeared first in the legacy export.
     */
    static List<Hub> deduplicate(List<Hub> raw) {
        Map<String, List<Hub>> groups = new LinkedHashMap<>();
        for (Hub h : raw) {
            String key = h.province() + "|" + h.sortingCenter().toLowerCase();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
        }

        List<Hub> result = new ArrayList<>();
        for (List<Hub> group : groups.values()) {
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }
            String canonicalId = group.stream()
                    .map(Hub::hubId)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            long activeVotes = group.stream().filter(Hub::active).count();
            boolean resolvedActive = activeVotes * 2 >= group.size();

            Hub winner = group.get(0);
            result.add(new Hub(canonicalId, winner.province(), winner.sortingCenter(), resolvedActive));
        }
        return result;
    }
}
