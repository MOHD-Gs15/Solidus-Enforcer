package com.solidus.enforcer.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnforcerCompatGuardTest — the anti-regression gate for the WS-C C-2ج
 * mixin-resilience rules (transplant of Core's CompatArchitectureGuardTest
 * mechanics). It turns the C-2ج contract into a BUILD BREAKER instead of a
 * comment future contributors can scroll past.
 *
 * <p>Rules:</p>
 * <ol>
 *   <li><b>require = 0 on every @Inject</b> in com/solidus/enforcer/mixin/ —
 *       a missing/reshaped Minecraft target must degrade to a log warning,
 *       never a startup crash (the exact defaultRequire=1 failure mode
 *       C-2ج removed).</li>
 *   <li><b>the mixin config declares the observation plugin</b> and a
 *       non-lethal {@code defaultRequire} — new injectors added without an
 *       explicit require must not inherit the old crash-on-missing default.</li>
 *   <li><b>EnforcerCompatState is Minecraft-free</b> — the state must stay
 *       readable from tests, the admin command and any thread without
 *       initializing a Minecraft class.</li>
 *   <li><b>the probes touch Minecraft only through reflection strings</b> —
 *       no direct {@code import net.minecraft} in EnforcerCompatProbes, so
 *       a unit-test JVM never bootstraps Minecraft while probing
 *       (Core's palette-clinit lesson, applied).</li>
 * </ol>
 *
 * <p>Escape hatch: a line may carry {@code // guard:allow <reason>} to be
 * skipped — used sparingly and always reviewable (the test prints every
 * allowed line).</p>
 *
 * @since 2.4.0
 */
@DisplayName("Enforcer compat guard (C-2ج): require=0 injectors, plugin wiring, MC-free state, reflection-only probes")
class EnforcerCompatGuardTest {

    private static final Path MAIN_JAVA = Paths.get(
        System.getProperty("user.dir"), "src", "main", "java");

    private static final Path MAIN_RESOURCES = Paths.get(
        System.getProperty("user.dir"), "src", "main", "resources");

    private static final String ALLOW_MARKER = "guard:allow";

    @Test
    @DisplayName("every @Inject in the mixin package carries require = 0")
    void everyInjectorIsNonFatal() throws IOException {
        Path mixinDir = MAIN_JAVA.resolve(Paths.get("com", "solidus", "enforcer", "mixin"));
        assertTrue(Files.isDirectory(mixinDir), "mixin package must exist");

        List<String> violations = new ArrayList<>();
        int injectorCount = 0;
        try (Stream<Path> paths = Files.walk(mixinDir)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN_JAVA.relativize(file).toString();
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(ALLOW_MARKER)) {
                        continue;
                    }
                    if (line.contains("@Inject(") && !line.contains("require = 0")) {
                        violations.add(relative + ":" + (i + 1) + " "
                            + "[injector without require = 0 — a reshaped MC target must warn, not crash] "
                            + line.trim());
                    }
                    if (line.contains("@Inject(")) {
                        injectorCount++;
                    }
                }
            }
        }
        assertTrue(injectorCount >= 2,
            "guard must see the two Enforcer injectors (found " + injectorCount + ")");
        assertTrue(violations.isEmpty(),
            () -> "C-2ج injector rule violated (" + violations.size() + "):\n  "
                + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("mixin config wires the plugin and a non-lethal defaultRequire")
    void mixinConfigWiresResilience() throws IOException {
        Path config = MAIN_RESOURCES.resolve("solidus-enforcer.mixins.json");
        assertTrue(Files.isRegularFile(config), "solidus-enforcer.mixins.json must exist");
        String json = Files.readString(config, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"plugin\": \"com.solidus.enforcer.compat.EnforcerMixinPlugin\"")
                || json.matches("(?s).*\"plugin\"\\s*:\\s*\"com\\.solidus\\.enforcer\\.compat\\.EnforcerMixinPlugin\".*"),
            "the mixin config must wire the observation plugin (C-2ج)");
        assertTrue(json.matches("(?s).*\"defaultRequire\"\\s*:\\s*0\\s*.*"),
            "defaultRequire must be 0 — the old crash-on-missing default is what C-2ج removed");
        assertFalse(json.matches("(?s).*\"defaultRequire\"\\s*:\\s*1\\s*.*"),
            "defaultRequire must never go back to 1 (startup crash on any injector miss)");
    }

    @Test
    @DisplayName("EnforcerCompatState stays Minecraft-free and the probes are reflection-only")
    void stateIsMcFreeAndProbesAreReflective() throws IOException {
        Path state = MAIN_JAVA.resolve(
            Paths.get("com", "solidus", "enforcer", "compat", "EnforcerCompatState.java"));
        Path probes = MAIN_JAVA.resolve(
            Paths.get("com", "solidus", "enforcer", "compat", "EnforcerCompatProbes.java"));
        assertTrue(Files.isRegularFile(state), "EnforcerCompatState must live in compat");
        assertTrue(Files.isRegularFile(probes), "EnforcerCompatProbes must live in compat");

        String stateSrc = Files.readString(state, StandardCharsets.UTF_8);
        assertFalse(stateSrc.contains("net.minecraft"),
            "EnforcerCompatState must not reference Minecraft — it is read from tests, "
                + "the admin command and non-server threads");

        String probesSrc = Files.readString(probes, StandardCharsets.UTF_8);
        assertFalse(probesSrc.lines().anyMatch(l -> l.startsWith("import net.minecraft")),
            "the probes may reference Minecraft classes ONLY through reflection strings "
                + "(Class.forName) — a direct import bootstraps Minecraft in a unit-test JVM");
        // ... and it must actually probe the two real target names:
        assertTrue(probesSrc.contains("hurtServer"), "probes must verify the hurtServer target");
        assertTrue(probesSrc.contains("\"die\""), "probes must verify the die target");
    }
}
