package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.Escapes;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent options, passed as {@code -javaagent:census-agent.jar=key=value,key=value}.
 *
 * <p>Values are percent-decoded, so a path containing {@code ,} {@code =} or spaces is written as
 * {@code %2C} {@code %3D} {@code %20} (the Maven plugin does this automatically). Unknown keys,
 * duplicates and invalid values are reported as warnings; the agent never refuses to start over them.
 *
 * <ul>
 *   <li>{@code output} - CBOM file to write on exit (default {@code cryptocensus-cbom.json})</li>
 *   <li>{@code dir} - directory to write {@code cbom-<pid>-<random>.json} into instead; use this when
 *       several JVMs (forked tests) share one output location</li>
 *   <li>{@code app} - application name recorded in the CBOM metadata</li>
 *   <li>{@code summary} - print a findings table to stderr on exit (default {@code true})</li>
 *   <li>{@code tls} - record negotiated TLS sessions (default {@code true})</li>
 *   <li>{@code maxAssets} - distinct assets kept before dropping (default 500)</li>
 *   <li>{@code maxSites} - call sites kept per asset before folding into an overflow bucket (default 50)</li>
 *   <li>{@code debug} - log instrumentation errors (default {@code false})</li>
 * </ul>
 */
final class AgentConfig {

    private static final Set<String> KEYS = Set.of("output", "dir", "app", "summary", "tls", "maxAssets", "maxSites", "debug");

    final Path output;
    final String app;
    final boolean summary;
    final boolean tls;
    final boolean debug;
    final int maxAssets;
    final int maxSites;
    final List<String> warnings;

    private AgentConfig(Map<String, String> options, List<String> warnings) {
        this.output = outputPath(options, warnings);
        this.app = options.get("app");
        this.summary = bool(options, "summary", true, warnings);
        this.tls = bool(options, "tls", true, warnings);
        this.debug = bool(options, "debug", false, warnings);
        this.maxAssets = positiveInt(options, "maxAssets", 500, warnings);
        this.maxSites = positiveInt(options, "maxSites", 50, warnings);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    private static Path outputPath(Map<String, String> options, List<String> warnings) {
        String dir = options.get("dir");
        try {
            return dir != null
                    ? Paths.get(dir, "cbom-" + ProcessHandle.current().pid() + "-"
                            + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE) + ".json")
                    : Paths.get(options.getOrDefault("output", "cryptocensus-cbom.json"));
        } catch (InvalidPathException e) {
            warnings.add("invalid output path (" + e.getMessage() + "); using cryptocensus-cbom.json");
            return Paths.get("cryptocensus-cbom.json");
        }
    }

    static AgentConfig parse(String args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (args != null && !args.isBlank()) {
            for (String pair : args.split(",")) {
                String[] kv = pair.split("=", 2);
                String key = kv[0].trim();
                if (kv.length != 2 || key.isEmpty()) {
                    warnings.add("ignoring malformed option '" + pair + "'");
                } else if (!KEYS.contains(key)) {
                    warnings.add("ignoring unknown option '" + key + "'");
                } else if (options.containsKey(key)) {
                    warnings.add("option '" + key + "' given more than once; keeping the first value");
                } else {
                    try {
                        options.put(key, Escapes.decode(kv[1].trim()));
                    } catch (IllegalArgumentException e) {
                        warnings.add("ignoring option '" + key + "': " + e.getMessage());
                    }
                }
            }
        }
        if (options.containsKey("dir") && options.containsKey("output")) {
            warnings.add("both 'dir' and 'output' given; using 'dir'");
        }
        return new AgentConfig(options, warnings);
    }

    private static boolean bool(Map<String, String> options, String key, boolean dflt, List<String> warnings) {
        String v = options.get(key);
        if (v == null) return dflt;
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        warnings.add("option '" + key + "' must be true or false, got '" + v + "'; using " + dflt);
        return dflt;
    }

    private static int positiveInt(Map<String, String> options, String key, int dflt, List<String> warnings) {
        String v = options.get(key);
        if (v == null) return dflt;
        try {
            int n = Integer.parseInt(v);
            if (n > 0) return n;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        warnings.add("option '" + key + "' must be a positive integer, got '" + v + "'; using " + dflt);
        return dflt;
    }
}
