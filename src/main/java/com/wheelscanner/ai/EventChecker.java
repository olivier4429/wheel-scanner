package com.wheelscanner.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wheelscanner.config.AppConfig;
import com.wheelscanner.model.Opportunity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyse evenements via Groq.
 * Compatible modeles "reasoning" (gpt-oss) : max_completion_tokens + extraction content/reasoning.
 */
public class EventChecker {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedResult> cache = new HashMap<>();
    private String lastGlobalError;
    private int consecutiveFailures;

    private static final class CachedResult {
        final String recommendation;
        final String summary;
        CachedResult(String recommendation, String summary) {
            this.recommendation = recommendation;
            this.summary = summary;
        }
    }

    public void analyze(Opportunity opp) {
        if (cache.containsKey(opp.ticker)) {
            CachedResult c = cache.get(opp.ticker);
            opp.aiRecommendation = c.recommendation;
            opp.eventSummary = c.summary + " (cache meme ticker)";
            return;
        }

        if (consecutiveFailures >= 2 && lastGlobalError != null) {
            apply(opp, "error", "IA stoppee apres erreurs repetees: " + lastGlobalError);
            return;
        }

        String key = AppConfig.GROQ_API_KEY == null ? "" : AppConfig.GROQ_API_KEY.trim();
        if (key.isBlank() || looksLikePlaceholder(key)) {
            apply(opp, "manual_check",
                    "Cle Groq absente ou placeholder. Mets groq.api.key=gsk_... puis mvn clean package");
            return;
        }

        try {
            // 1) sans response_format (plus fiable sur gpt-oss)
            // 2) avec json_object en secours
            String respBody = callGroq(key, opp, false);
            if (respBody == null) {
                respBody = callGroq(key, opp, true);
            }
            if (respBody == null) {
                consecutiveFailures++;
                apply(opp, "error", lastGlobalError != null ? lastGlobalError : "Echec appel Groq");
                return;
            }

            JsonNode root = mapper.readTree(respBody);
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText(root.path("error").toString());
                lastGlobalError = "Groq API: " + truncate(msg, 180);
                consecutiveFailures++;
                apply(opp, "error", lastGlobalError);
                return;
            }

            JsonNode message = root.path("choices").path(0).path("message");
            String content = firstNonBlank(
                    message.path("content").asText(""),
                    message.path("reasoning").asText(""),
                    root.path("choices").path(0).path("text").asText("")
            );

            String finish = root.path("choices").path(0).path("finish_reason").asText("");
            if (content.isBlank()) {
                consecutiveFailures++;
                lastGlobalError = "Reponse Groq vide (finish_reason=" + finish
                        + ") body=" + truncate(respBody, 220);
                apply(opp, "error", lastGlobalError);
                return;
            }

            JsonNode analysis = parseJsonContent(content);
            if (analysis == null) {
                // Si le modele a raisonne en texte libre, on garde un resume manuel
                consecutiveFailures = 0;
                apply(opp, "manual_check", "IA texte libre: " + truncate(content, 180));
                cache.put(opp.ticker, new CachedResult("manual_check", opp.eventSummary));
                return;
            }

            String summary = analysis.path("summary").asText("N/A");
            String rec = normalizeRec(analysis.path("recommendation").asText("manual_check"));
            consecutiveFailures = 0;
            lastGlobalError = null;
            apply(opp, rec, summary);
            cache.put(opp.ticker, new CachedResult(rec, summary));

        } catch (Exception e) {
            consecutiveFailures++;
            lastGlobalError = e.getClass().getSimpleName() + ": " + e.getMessage();
            apply(opp, "error", lastGlobalError);
            if (AppConfig.DEBUG) e.printStackTrace();
        }
    }

    private String callGroq(String apiKey, Opportunity opp, boolean withJsonFormat) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", AppConfig.GROQ_MODEL);
            body.put("temperature", 0.2);
            // Important pour gpt-oss (reasoning) : assez de tokens pour reasoning + reponse
            body.put("max_completion_tokens", 2048);
            body.put("max_tokens", 2048);
            // Reduit le raisonnement pour laisser de la place au JSON final
            body.put("reasoning_effort", "low");

            if (withJsonFormat) {
                body.set("response_format", mapper.createObjectNode().put("type", "json_object"));
            }

            body.set("messages", mapper.createArrayNode()
                    .add(mapper.createObjectNode()
                            .put("role", "system")
                            .put("content",
                                    "Tu es un analyste options. Reponds UNIQUEMENT avec un objet JSON valide, "
                                            + "sans markdown, sans texte avant/apres."))
                    .add(mapper.createObjectNode()
                            .put("role", "user")
                            .put("content", buildPrompt(opp))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AppConfig.GROQ_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String respBody = response.body() == null ? "" : response.body();

            if (code != 200) {
                if (withJsonFormat && (code == 400 || code == 422)) {
                    return null;
                }
                lastGlobalError = "HTTP " + code + " - " + shortGroqError(code, respBody);
                return null;
            }
            return respBody;
        } catch (Exception e) {
            lastGlobalError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    private void apply(Opportunity opp, String rec, String summary) {
        opp.aiRecommendation = rec;
        opp.eventSummary = summary == null ? "" : summary;
        if ("error".equals(rec) || (summary != null && summary.startsWith("Cle Groq"))) {
            System.err.println("[IA] " + opp.ticker + " -> " + rec + " | " + opp.eventSummary);
            System.err.flush();
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static boolean looksLikePlaceholder(String key) {
        String k = key.toLowerCase();
        return k.contains("xxxx") || k.contains("your_key") || k.contains("changeme");
    }

    private static String normalizeRec(String rec) {
        if (rec == null) return "manual_check";
        String r = rec.trim().toLowerCase();
        if (r.contains("avoid")) return "avoid";
        if (r.contains("reduce")) return "reduce_size";
        if (r.contains("keep")) return "keep";
        return "manual_check";
    }

    private JsonNode parseJsonContent(String content) {
        try {
            return mapper.readTree(content.replace("```json", "").replace("```", "").trim());
        } catch (Exception ignored) {}
        Matcher m = JSON_OBJECT.matcher(content);
        if (m.find()) {
            try { return mapper.readTree(m.group()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String shortGroqError(int code, String body) {
        try {
            JsonNode n = new ObjectMapper().readTree(body);
            String msg = n.path("error").path("message").asText("");
            if (!msg.isBlank()) return truncate(msg, 200);
        } catch (Exception ignored) {}
        if (code == 401) return "cle API invalide (Unauthorized)";
        if (code == 404) return "modele introuvable - change groq.model";
        if (code == 429) return "quota / rate-limit Groq depasse";
        return truncate(body.replace('\n', ' '), 200);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String buildPrompt(Opportunity opp) {
        return String.format(
                "Evenements pour %s jusqu au %s (DTE %d). Strategie %s, strike %.2f, prix %.2f.%n"
                        + "Reponds avec UNIQUEMENT ce JSON:%n"
                        + "{\"earnings\":{\"date\":null,\"risk\":\"none\"},"
                        + "\"dividends\":{\"exDate\":null},\"other_events\":[],"
                        + "\"overall_risk\":\"low\",\"recommendation\":\"keep\","
                        + "\"summary\":\"deux phrases max en francais\"}",
                opp.ticker, opp.expiration, opp.dte, opp.strategy, opp.strike, opp.stockPrice);
    }
}
