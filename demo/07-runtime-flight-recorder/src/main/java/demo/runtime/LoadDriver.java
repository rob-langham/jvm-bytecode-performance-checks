package demo.runtime;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.TreeMap;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Drives both engines under load and reads the recorder over JMX after each round.
 *
 * <p>Both classes pass the static checker with zero findings. Only the counts tell them apart:
 * one stops, the other never does.
 */
public final class LoadDriver {

    private static final int ROUNDS = 6;
    private static final int OPERATIONS_PER_ROUND = 500_000;

    private LoadDriver() {
    }

    public static void main(String[] args) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName recorder = new ObjectName("com.staticallocationchecker:type=AllocationFlightRecorder");

        if (!server.isRegistered(recorder)) {
            System.err.println("The agent is not attached. Run with:");
            System.err.println("  ./gradlew :07-runtime-flight-recorder:run");
            System.exit(1);
        }

        PricingEngine pricing = new PricingEngine();
        ResizingCache cache = new ResizingCache();

        System.out.printf("%-8s %-14s %-12s %s%n", "round", "operations", "recorded", "per-site counts");
        long operations = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            for (int i = 0; i < OPERATIONS_PER_ROUND; i++) {
                pricing.price(operations);
                cache.sum(operations);
                operations++;
            }
            System.out.printf("%-8d %-14d %-12s %s%n",
                    round, operations, server.getAttribute(recorder, "TotalAllocations"),
                    perSite(server, recorder));
        }

        System.out.println();
        System.out.println("PricingEngine stopped allocating after the first round.");
        System.out.println("ResizingCache is still going - a warmup site that never warmed up.");
    }

    /** Site keys shortened to {@code Class#method}, so the shape is readable at a glance. */
    private static String perSite(MBeanServer server, ObjectName recorder) throws Exception {
        TabularData sites = (TabularData) server.getAttribute(recorder, "Sites");
        Map<String, Long> counts = new TreeMap<>();
        for (Object row : sites.values()) {
            CompositeData entry = (CompositeData) row;
            String key = String.valueOf(entry.get("key"));
            long count = ((Number) ((CompositeData) entry.get("value")).get("count")).longValue();
            counts.merge(shorten(key), count, Long::sum);
        }
        StringBuilder text = new StringBuilder();
        counts.forEach((site, count) -> text.append(site).append('=').append(count).append("  "));
        return text.toString().trim();
    }

    private static String shorten(String siteKey) {
        String withoutPackage = siteKey.substring(siteKey.lastIndexOf('.') + 1);
        int colon = withoutPackage.indexOf(':');
        return colon < 0 ? withoutPackage : withoutPackage.substring(0, colon);
    }
}
