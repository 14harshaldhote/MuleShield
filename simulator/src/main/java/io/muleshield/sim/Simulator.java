package io.muleshield.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Command line:
 * <pre>
 *   train     run a world with no interventions and log feature rows for model training
 *   evaluate  replay one world under several strategies (in parallel) and write results.json
 * </pre>
 * Options: --pack, --seed, --customers, --days, --warmup, --start, --scam-rate, --adaptive,
 * --pessimistic, --strategies, --payment-model, --mule-model, --out
 */
public final class Simulator {

    private Simulator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: simulator train|evaluate [--option value ...]");
            System.exit(2);
        }
        String mode = args[0];
        Map<String, String> opt = options(Arrays.copyOfRange(args, 1, args.length));
        Path out = Path.of(opt.getOrDefault("out", "target/sim"));
        Files.createDirectories(out);

        if (mode.equals("train")) {
            SimConfig c = config(opt, Strategy.NONE, out.resolve("training"));
            long t0 = System.currentTimeMillis();
            new World(c).run();
            System.out.printf("training rows written to %s in %.1fs%n", out.resolve("training"), (System.currentTimeMillis() - t0) / 1000.0);
            return;
        }
        if (!mode.equals("evaluate")) {
            throw new IllegalArgumentException("Unknown mode " + mode);
        }
        List<Strategy> strategies = Arrays.stream(opt.getOrDefault("strategies", String.join(",",
                Arrays.stream(Strategy.values()).map(Enum::name).toList())).split(",")).map(Strategy::valueOf).toList();
        int threads = Integer.parseInt(opt.getOrDefault("threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        List<Map<String, Object>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Map<String, Object>>> runs = new ArrayList<>();
            for (Strategy s : strategies) {
                SimConfig c = config(opt, s, null);
                runs.add(pool.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    Map<String, Object> summary = new World(c).run().summary(c);
                    System.out.printf("%-24s done in %.1fs%n", s, (System.currentTimeMillis() - t0) / 1000.0);
                    return summary;
                }));
            }
            for (Future<Map<String, Object>> f : runs) {
                results.add(f.get());
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("options", opt);
        doc.put("assumptions", opt.containsKey("pessimistic") ? Assumptions.pessimistic() : Assumptions.central());
        doc.put("runs", results);
        String name = opt.getOrDefault("name", "results");
        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(out.resolve(name + ".json"), json.writeValueAsString(doc));
        System.out.println("wrote " + out.resolve(name + ".json"));
    }

    static SimConfig config(Map<String, String> opt, Strategy strategy, Path trainingOut) {
        return new SimConfig(
                opt.getOrDefault("pack", "IN-RBI-2026"),
                Long.parseLong(opt.getOrDefault("seed", "1")),
                Integer.parseInt(opt.getOrDefault("customers", "20000")),
                Integer.parseInt(opt.getOrDefault("days", "75")),
                LocalDate.parse(opt.getOrDefault("start", "2026-06-01")),
                strategy,
                Double.parseDouble(opt.getOrDefault("scam-rate", "3")),
                opt.containsKey("adaptive"),
                Integer.parseInt(opt.getOrDefault("warmup", "15")),
                opt.containsKey("pessimistic") ? Assumptions.pessimistic() : Assumptions.central(),
                opt.containsKey("payment-model") ? Path.of(opt.get("payment-model")) : null,
                opt.containsKey("mule-model") ? Path.of(opt.get("mule-model")) : null,
                trainingOut);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option, got " + args[i]);
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opt.put(key, args[++i]);
            } else {
                opt.put(key, "true");
            }
        }
        return opt;
    }
}
