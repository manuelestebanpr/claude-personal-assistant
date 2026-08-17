package com.my.custom.claudepersonalassistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two ways a cost query goes silently wrong, on the artefacts that are not compiled and
 * therefore have no other safety net.
 *
 * <p>Neither failure announces itself: a query that forgets {@code gen_ai_token_type!="total"}
 * renders a confident figure that is exactly double the truth, and a panel pointed at the image's
 * default {@code prometheus} datasource renders at the 60s {@code timeInterval} this project added
 * {@code prometheus-15s} to escape. Both are the kind of thing a later edit reintroduces.
 *
 * <p>Deliberately not a schema check: Grafana's dashboard JSON changes shape between versions and
 * asserting on its structure would be a test that fails on upgrades without anything being wrong.
 */
class LlmCostDashboardTest {

    private static final Path DASHBOARD = Path.of("observability/grafana/dashboards/llm-cost.json");
    private static final Path RULES = Path.of("observability/prometheus/llm-cost-rules.yaml");
    private static final Path PROMETHEUS_CONFIG = Path.of("observability/prometheus/prometheus.yaml");
    private static final Path ALERTS =
            Path.of("observability/grafana/provisioning/alerting/llm-cost-alerts.yaml");

    private static final String TOKEN_USAGE_SERIES = "gen_ai_client_token_usage_total";
    private static final String DATASOURCE_UID = "prometheus-15s";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@code gen_ai_token_type="total"} is its own series alongside {@code input} and {@code output}
     * — Spring AI emits all three from one response — so a selector on the usage counter that
     * mentions neither counts every token twice. Selecting {@code ="total"} on purpose is fine; not
     * deciding is not.
     */
    @Test
    void everyTokenUsageSelectorDecidesWhatToDoWithTheTotalSeries() {
        List<String> undecided = expressions().stream()
                .filter(expr -> expr.contains(TOKEN_USAGE_SERIES))
                .filter(expr -> !expr.contains("gen_ai_token_type!=\"total\"")
                        && !expr.contains("gen_ai_token_type=\"total\"")
                        && !expr.contains("gen_ai_token_type=\"input\"")
                        && !expr.contains("gen_ai_token_type=\"output\""))
                .toList();

        assertThat(undecided)
                .as("a selector on %s that neither excludes nor deliberately selects the 'total' "
                        + "token type double-counts every token", TOKEN_USAGE_SERIES)
                .isEmpty();
    }

    @Test
    void recordingRulesExcludeTheTotalSeries() throws Exception {
        String rules = Files.readString(RULES);

        assertThat(rules).contains("llm:spend_usd:24h");
        assertThat(rules.lines().filter(line -> line.contains(TOKEN_USAGE_SERIES)).toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("gen_ai_token_type!=\"total\""));
    }

    /** A rule file nothing references is a rule file that never evaluates. */
    @Test
    void prometheusConfigReferencesTheRuleFile() throws Exception {
        String config = Files.readString(PROMETHEUS_CONFIG);

        assertThat(config).contains("rule_files:");
        assertThat(config).contains(RULES.getFileName().toString());
    }

    /**
     * Every panel must use the app-owned datasource. The image's default {@code prometheus} uid
     * declares {@code timeInterval: 60s}, which pushes {@code $__rate_interval} past four minutes
     * and averages away any burst shorter than that.
     */
    @Test
    void everyPanelUsesTheFifteenSecondDatasource() throws Exception {
        JsonNode dashboard = objectMapper.readTree(Files.readString(DASHBOARD));

        List<String> uids = new ArrayList<>();
        dashboard.findValues("datasource").forEach(node -> {
            JsonNode uid = node.get("uid");
            if (uid != null) {
                uids.add(uid.asString());
            }
        });

        assertThat(uids).isNotEmpty().containsOnly(DATASOURCE_UID);
    }

    /**
     * These are the mangled forms of the Micrometer tags {@code LlmTokenPriceMeterBinder} puts on
     * the price gauge; that those tags equal the ones Spring AI puts on the usage counter is
     * asserted against Spring AI's own constants in {@code LlmTokenPriceMeterBinderTest}. Here they
     * are literals on purpose — the binder is package-private, as an internal class should be, and
     * the point of this test is that the query artefacts spell the labels the same way.
     */
    @Test
    void costExpressionsJoinOnTheTagsThePriceGaugePublishes() throws Exception {
        assertThat(expressions()).isNotEmpty()
                .anySatisfy(expr -> assertThat(expr).contains("gen_ai_response_model"));
        assertThat(expressions().stream().filter(expr -> expr.contains("llm:spend_usd")).toList())
                .as("the spend panels read the recorded series rather than recomputing the join")
                .isNotEmpty();
        assertThat(Files.readString(RULES))
                .contains("on (gen_ai_response_model, gen_ai_token_type) group_left()");
    }

    /**
     * The budget must be a number. Grafana does not expand {@code $VARIABLE} in alerting
     * provisioning files — it stores the literal string and still reports the rule as healthy — so
     * a threshold that looks configured would silently not be.
     *
     * <p>Parsed rather than grepped, and that is half the value: a malformed alerting file does not
     * degrade Grafana, it fails the provisioning module at startup and the container never becomes
     * ready. Loading it here turns that into a test failure.
     */
    @SuppressWarnings("unchecked")
    @Test
    void alertThresholdIsALiteralNumberNotAnUnexpandedVariable() throws Exception {
        Map<String, Object> alerts = new Yaml().load(Files.readString(ALERTS));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) alerts.get("groups");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.getFirst().get("rules");
        Map<String, Object> rule = rules.getFirst();

        assertThat(rule.get("for")).as("a burst must not page; 15m is the agreed grace period")
                .isEqualTo("15m");

        List<Map<String, Object>> data = (List<Map<String, Object>>) rule.get("data");
        Map<String, Object> threshold = data.stream()
                .map(node -> (Map<String, Object>) node.get("model"))
                .filter(model -> "threshold".equals(model.get("type")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) threshold.get("conditions");
        Map<String, Object> evaluator =
                (Map<String, Object>) conditions.getFirst().get("evaluator");

        assertThat((List<Object>) evaluator.get("params"))
                .singleElement()
                .isInstanceOf(Number.class)
                .satisfies(budget -> assertThat(((Number) budget).doubleValue()).isPositive());
    }

    /** Every {@code expr} in the dashboard, panel targets included. */
    private List<String> expressions() {
        try {
            return objectMapper.readTree(Files.readString(DASHBOARD)).findValues("expr").stream()
                    .map(JsonNode::asString)
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read " + DASHBOARD, exception);
        }
    }
}
