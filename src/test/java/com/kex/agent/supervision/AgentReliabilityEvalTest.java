// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import com.kex.agent.supervision.eval.AgentEvalCorpus;
import com.kex.agent.supervision.eval.AgentEvalHarness;
import com.kex.agent.supervision.eval.AgentEvalMcpServer;
import com.kex.agent.supervision.eval.AgentEvalReport;
import com.kex.agent.supervision.eval.AgentEvalScoring;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid, stochastic evaluation of actual production supervision and real model/tool execution. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "KEX_RUN_AGENT_EVALS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class AgentReliabilityEvalTest {
    private static final AgentEvalMcpServer SERVER = start();
    private static final String PROVIDER = System.getenv().getOrDefault("KEX_AGENT_LLM_PROVIDER", "anthropic");
    private static final String MODEL = requiredEnvironment("KEX_EVAL_MODEL");
    private static final AgentEvalHarness HARNESS = harness();
    /** The supervision cycle converses under the internal owner: seeded skills must belong to it. */
    private static final String CYCLE_OWNER = "kex-internal";

    @Autowired
    SupervisionService supervision;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    ObjectProvider<MeterRegistry> meters;

    @Autowired
    ObjectProvider<LearningRepository> learning;

    private static AgentEvalHarness harness() {
        String path = System.getenv("KEX_EVAL_SKILLS");
        if (path == null || path.isBlank()) return AgentEvalHarness.BASE;
        try {
            return AgentEvalHarness.load(Path.of(path));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static AgentEvalMcpServer start() {
        try {
            return new AgentEvalMcpServer();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required environment: " + name);
        return value;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!List.of("anthropic", "openai").contains(PROVIDER)) {
            throw new IllegalStateException("Evaluation provider must be anthropic or openai");
        }
        String key = requiredEnvironment(PROVIDER.equals("anthropic") ? "ANTHROPIC_API_KEY" : "OPENROUTER_API_KEY");
        registry.add("spring.ai.model.chat", () -> PROVIDER);
        registry.add("spring.ai." + PROVIDER + ".api-key", () -> key);
        registry.add("spring.ai." + PROVIDER + ".chat.options.model", () -> MODEL);
        registry.add("spring.ai.tools.limits.max-total-tool-calls", () -> 8);
        registry.add("kex.agent.request-timeout", () -> "90s");
        registry.add("kex.agent.log-interactions", () -> false);
        // Memory exists only to hold the seeded skills. remember_fact would otherwise let one trial
        // leave facts that recall_facts hands to the next, which the fixtures do not isolate.
        boolean seeded = !HARNESS.skills().isEmpty();
        registry.add("kex.agent.memory.enabled", () -> seeded);
        if (seeded) {
            String storage = temporaryDirectory();
            registry.add("kex.agent.memory.storage-directory", () -> storage);
            registry.add("kex.agent.tools.rules.remember_fact.denied", () -> true);
            registry.add("kex.agent.tools.rules.recall_facts.denied", () -> true);
        }
        registry.add("kex.agent.token-budget.daily-limit", () -> 0);
        registry.add("kex.agent.supervision.notify.webhook-url", () -> "");
        registry.add("kex.agent.supervision.learning.enabled", () -> false);
        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.eval.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.eval.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> AgentEvalMcpServer.TOKEN);
        registry.add("kex.agent.supervision.processes[0].id", () -> "order-integration");
        registry.add("kex.agent.supervision.processes[0].name", () -> "Order Integration");
        registry.add("kex.agent.supervision.processes[0].hint", () ->
                "Mesure actuelle du topic orders, groupe order-consumer. Préférer kex_diagnose_consumer "
                        + "si annoncé, sinon kex_consumer_lag. "
                        + "Les prévisions disponibles concernent aussi ces topic et groupe.");
    }

    private static String temporaryDirectory() {
        try {
            return Files.createTempDirectory("kex-eval-memory").toString();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void evaluatesRealModelAcrossRepeatedNetworkFixturesAndWritesReviewableReport() throws Exception {
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("KEX_EVAL_REPETITIONS", "3"));
        assertThat(repetitions).isBetween(2, 10);
        List<AgentEvalCorpus.Scenario> corpus = AgentEvalCorpus.load();
        List<AgentEvalScoring.Trial> trials = new ArrayList<>();
        Path reportFile = Path.of(System.getenv().getOrDefault("KEX_EVAL_REPORT", "target/agent-evals/candidate.json"));
        seed();
        for (AgentEvalCorpus.Scenario scenario : corpus) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                // A deliberate MCP outage must not poison the next independent fixture's circuit.
                circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
                SERVER.select(scenario);
                Integer inputBefore = tokens("input");
                Integer outputBefore = tokens("output");
                CycleReport cycle = supervision.runCycle("agent-evaluation");
                ProcessSnapshot snapshot = supervision.snapshots().stream()
                        .filter(value -> "order-integration".equals(value.processId())).findFirst().orElseThrow();
                var observation = new AgentEvalScoring.Observation(snapshot.state().name(),
                        snapshot.coverage().complete(), snapshot.coverage().knownIncomplete(),
                        snapshot.coverage().notReached(), cycle.anomaliesDetected(), cycle.failure(), SERVER.calls(),
                        delta(inputBefore, tokens("input")), delta(outputBefore, tokens("output")));
                trials.add(AgentEvalScoring.score(scenario, repetition, observation));
                // Persist completed trials even if a later call crashes or the workflow times out.
                report(repetitions, trials).write(reportFile);
            }
        }
        AgentEvalReport report = report(repetitions, trials);
        String baselinePath = System.getenv("KEX_EVAL_BASELINE");
        AgentEvalReport baseline = baselinePath == null || baselinePath.isBlank() ? null
                : AgentEvalReport.read(Path.of(baselinePath));
        if (baseline != null) {
            String name = reportFile.getFileName().toString().replaceFirst("\\.json$", "");
            report.compare(baseline).write(reportFile.resolveSibling(name + ".comparison.json"));
        }
        assertThat(report.gate(corpus, baseline)).as("Evaluation gate; report: %s", reportFile).isEmpty();
    }

    private void seed() {
        LearningRepository repository = learning.getIfAvailable();
        if (HARNESS.skills().isEmpty()) return;
        if (repository == null) throw new IllegalStateException("A skills harness needs kex.agent.memory.enabled");
        Instant now = Instant.now();
        // The first fixture entry ranks first: approval recency is the order injection follows.
        for (int index = 0; index < HARNESS.skills().size(); index++) {
            AgentEvalHarness.Skill skill = HARNESS.skills().get(index);
            Instant approved = now.minusSeconds(index);
            repository.add(new LearningEntry(UUID.randomUUID().toString(), CYCLE_OWNER, "SKILL", skill.title(),
                    skill.markdown(), "Evaluation harness " + HARNESS.fingerprint(), null, approved, "APPROVED",
                    "agent-evaluation", approved, "Seeded for evaluation", null, skill.scope(), skill.triggers(),
                    List.of()));
        }
    }

    /**
     * Spring AI counts tokens under one name with a type tag; reading it unfiltered adds input,
     * output and total together. The counter is born at the first call, so its absence before a
     * trial is a true zero — but its absence after one means nothing was reported.
     */
    private Integer tokens(String type) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry == null) return null;
        var counters = registry.find("gen_ai.client.token.usage").tag("gen_ai.token.type", type).counters();
        return counters.isEmpty() ? null : (int) counters.stream().mapToDouble(Counter::count).sum();
    }

    private static Integer delta(Integer before, Integer after) {
        return after == null ? null : after - (before == null ? 0 : before);
    }

    private static AgentEvalReport report(int repetitions, List<AgentEvalScoring.Trial> trials) {
        return new AgentEvalReport(AgentEvalCorpus.VERSION, MODEL, PROVIDER,
                System.getenv().getOrDefault("KEX_EVAL_REVISION", "local-unrecorded"), repetitions, List.copyOf(trials),
                HARNESS.label(), HARNESS.fingerprint());
    }
}
