package com.wheelscanner.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wheelscanner.config.AppConfig;
import com.wheelscanner.model.Opportunity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Yahoo Finance avec session cookie + crumb (requis pour les options).
 */
public class YahooDataProvider implements DataProvider {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private String crumb;
    private boolean sessionReady;

    public YahooDataProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private synchronized void ensureSession() throws Exception {
        if (sessionReady && crumb != null && !crumb.isBlank()) {
            return;
        }

        HttpRequest fc = HttpRequest.newBuilder()
                .uri(URI.create("https://fc.yahoo.com"))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            httpClient.send(fc, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }

        HttpRequest crumbReq = HttpRequest.newBuilder()
                .uri(URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb"))
                .header("User-Agent", UA)
                .header("Accept", "text/plain,application/json,*/*")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> crumbResp = httpClient.send(crumbReq, HttpResponse.BodyHandlers.ofString());
        if (crumbResp.statusCode() != 200) {
            throw new IllegalStateException(
                    "Yahoo getcrumb HTTP " + crumbResp.statusCode() + " body=" + truncate(crumbResp.body(), 120));
        }
        crumb = crumbResp.body() == null ? "" : crumbResp.body().trim();
        if (crumb.isBlank() || crumb.startsWith("{") || crumb.toLowerCase().contains("too many")) {
            throw new IllegalStateException("Yahoo crumb invalide: " + truncate(crumb, 120));
        }
        sessionReady = true;
        System.out.println("  Yahoo session OK (crumb obtenu)");
    }

    private HttpRequest.Builder baseGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json,text/plain,*/*")
                .timeout(Duration.ofSeconds(20))
                .GET();
    }

    @Override
    public List<Opportunity> findOpportunities(String ticker) {
        List<Opportunity> results = new ArrayList<>();
        RejectionStats stats = new RejectionStats(ticker);

        try {
            ensureSession();

            double price = fetchPrice(ticker);
            if (price <= 0) {
                stats.markFatal("prix_indisponible (price<=0)");
                stats.printSummary(0);
                return results;
            }
            stats.stockPrice = price;

            JsonNode optionsRoot = fetchOptionsChain(ticker);
            if (optionsRoot == null) {
                stats.markFatal("chaine_options_null");
                stats.printSummary(0);
                return results;
            }

            JsonNode err = optionsRoot.path("optionChain").path("error");
            if (!err.isMissingNode() && !err.isNull()) {
                stats.markFatal("Yahoo error: " + err.toString());
                stats.printSummary(0);
                return results;
            }

            JsonNode result = optionsRoot.path("optionChain").path("result");
            if (!result.isArray() || result.isEmpty()) {
                stats.markFatal("optionChain.result vide | body=" + truncate(optionsRoot.toString(), 180));
                stats.printSummary(0);
                return results;
            }

            JsonNode options = result.get(0).path("options");
            JsonNode expDates = result.get(0).path("expirationDates");

            if ((!options.isArray() || options.isEmpty()) && expDates.isArray() && !expDates.isEmpty()) {
                long firstExp = expDates.get(0).asLong();
                optionsRoot = fetchOptionsChainForDate(ticker, firstExp);
                result = optionsRoot.path("optionChain").path("result");
                if (result.isArray() && !result.isEmpty()) {
                    options = result.get(0).path("options");
                    expDates = result.get(0).path("expirationDates");
                }
            }

            if (!options.isArray() || options.isEmpty()) {
                stats.markFatal("aucune expiration/options dans la reponse Yahoo");
                stats.printSummary(0);
                return results;
            }

            List<JsonNode> expirationNodes = new ArrayList<>();
            for (JsonNode n : options) {
                expirationNodes.add(n);
            }

            if (expDates.isArray()) {
                int loaded = 0;
                for (JsonNode expNode : expDates) {
                    if (loaded >= 8) break;
                    long expTs = expNode.asLong();
                    int dte = (int) (Instant.ofEpochSecond(expTs)
                            .atZone(ZoneId.of("America/New_York")).toLocalDate().toEpochDay()
                            - LocalDate.now().toEpochDay());
                    boolean useful = (dte >= AppConfig.MIN_DTE && dte <= AppConfig.MAX_DTE)
                            || (dte >= AppConfig.MIN_LEAP_DTE);
                    if (!useful) continue;

                    boolean already = false;
                    for (JsonNode existing : expirationNodes) {
                        if (existing.path("expirationDate").asLong() == expTs) {
                            already = true;
                            break;
                        }
                    }
                    if (already) continue;

                    try {
                        Thread.sleep(300);
                        JsonNode more = fetchOptionsChainForDate(ticker, expTs);
                        JsonNode moreOpts = more.path("optionChain").path("result");
                        if (moreOpts.isArray() && !moreOpts.isEmpty()) {
                            JsonNode opts = moreOpts.get(0).path("options");
                            if (opts.isArray()) {
                                for (JsonNode o : opts) {
                                    expirationNodes.add(o);
                                }
                                loaded++;
                            }
                        }
                    } catch (Exception e) {
                        if (AppConfig.DEBUG) {
                            System.out.println("  skip exp " + expTs + ": " + e.getMessage());
                        }
                    }
                }
            }

            int expirationsSeen = 0;
            int putsInDteWindow = 0;
            int callsLeapWindow = 0;

            for (JsonNode expirationNode : expirationNodes) {
                long expTimestamp = expirationNode.path("expirationDate").asLong();
                LocalDate expiration = Instant.ofEpochSecond(expTimestamp)
                        .atZone(ZoneId.of("America/New_York"))
                        .toLocalDate();
                int dte = (int) (expiration.toEpochDay() - LocalDate.now().toEpochDay());
                expirationsSeen++;

                if (dte >= AppConfig.MIN_DTE && dte <= AppConfig.MAX_DTE) {
                    JsonNode puts = expirationNode.path("puts");
                    if (puts.isArray()) {
                        for (JsonNode put : puts) {
                            putsInDteWindow++;
                            Opportunity opp = buildWheelOpportunity(ticker, price, put, expiration, dte, stats);
                            if (opp != null) results.add(opp);
                        }
                    }
                } else {
                    stats.inc("dte_hors_fenetre_wheel (hors min.dte/max.dte)");
                }

                if (dte >= AppConfig.MIN_LEAP_DTE) {
                    JsonNode calls = expirationNode.path("calls");
                    if (calls.isArray()) {
                        for (JsonNode call : calls) {
                            callsLeapWindow++;
                            Opportunity opp = buildPmccLeap(ticker, price, call, expiration, dte, stats);
                            if (opp != null) results.add(opp);
                        }
                    }
                }
            }

            stats.expirationsSeen = expirationsSeen;
            stats.putsInDteWindow = putsInDteWindow;
            stats.callsLeapWindow = callsLeapWindow;

            if (putsInDteWindow == 0) {
                stats.inc("aucune_put_dans_fenetre_dte [" + AppConfig.MIN_DTE + "-" + AppConfig.MAX_DTE + "]");
            }
            if (callsLeapWindow == 0) {
                stats.inc("aucune_call_LEAP_dte>=" + AppConfig.MIN_LEAP_DTE);
            }

        } catch (Exception e) {
            System.err.println("Erreur Yahoo pour " + ticker + " : " + e.getMessage());
            stats.markFatal("exception: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("crumb")) {
                sessionReady = false;
                crumb = null;
            }
            if (AppConfig.DEBUG) e.printStackTrace();
        }

        if (results.isEmpty() || AppConfig.DEBUG) {
            stats.printSummary(results.size());
        }
        return results;
    }

    private double fetchPrice(String ticker) throws Exception {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + ticker + "?interval=1d&range=1d";
        if (crumb != null && !crumb.isBlank()) {
            url += "&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
        }
        HttpResponse<String> response = httpClient.send(baseGet(url).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new IllegalStateException("Yahoo rate-limit 429 sur chart");
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode result = root.path("chart").path("result");
        if (result.isArray() && !result.isEmpty()) {
            return result.get(0).path("meta").path("regularMarketPrice").asDouble(0);
        }
        return 0;
    }

    private JsonNode fetchOptionsChain(String ticker) throws Exception {
        ensureSession();
        String url = "https://query2.finance.yahoo.com/v7/finance/options/" + ticker
                + "?crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
        return fetchOptionsJson(url);
    }

    private JsonNode fetchOptionsChainForDate(String ticker, long epochSeconds) throws Exception {
        ensureSession();
        String url = "https://query2.finance.yahoo.com/v7/finance/options/" + ticker
                + "?date=" + epochSeconds
                + "&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
        return fetchOptionsJson(url);
    }

    private JsonNode fetchOptionsJson(String url) throws Exception {
        HttpResponse<String> response = httpClient.send(baseGet(url).build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        String body = response.body();
        if (code == 429) {
            throw new IllegalStateException("Yahoo rate-limit 429 sur options");
        }
        if (code == 401 || code == 403) {
            sessionReady = false;
            crumb = null;
            throw new IllegalStateException("Yahoo HTTP " + code + " (crumb/cookie invalide) body=" + truncate(body, 150));
        }
        if (code != 200) {
            throw new IllegalStateException("Yahoo options HTTP " + code + " body=" + truncate(body, 150));
        }
        return mapper.readTree(body);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private Opportunity buildWheelOpportunity(String ticker, double stockPrice,
                                              JsonNode put, LocalDate expiration, int dte,
                                              RejectionStats stats) {
        double strike = put.path("strike").asDouble();
        if (strike >= stockPrice) {
            stats.inc("wheel: strike_not_OTM (strike>=prix)");
            return null;
        }

        double bid = put.path("bid").asDouble(0);
        double ask = put.path("ask").asDouble(0);
        double last = put.path("lastPrice").asDouble(0);
        double mid = (bid > 0 && ask > 0) ? (bid + ask) / 2.0 : last;

        if (mid < 0.05) {
            stats.inc("wheel: premium_trop_faible (mid<0.05)");
            return null;
        }

        long oi = put.path("openInterest").asLong(0);
        if (oi < AppConfig.MIN_OPEN_INTEREST) {
            stats.inc("wheel: openInterest < min.open.interest (" + oi + "<" + AppConfig.MIN_OPEN_INTEREST + ")");
            return null;
        }

        double spreadPct = (ask > 0 && bid > 0) ? (ask - bid) / mid : 1.0;
        if (spreadPct > AppConfig.MAX_SPREAD_PCT) {
            stats.inc("wheel: spread > max.spread.pct ("
                    + String.format("%.0f%%", spreadPct * 100) + ">"
                    + String.format("%.0f%%", AppConfig.MAX_SPREAD_PCT * 100) + ")");
            return null;
        }

        double moneyness = (stockPrice - strike) / stockPrice;
        double approxDelta = Math.max(0.05, Math.min(0.45, 0.30 - moneyness * 1.2));

        if (approxDelta < AppConfig.MIN_DELTA) {
            stats.inc("wheel: delta < min.delta (" + String.format("%.2f", approxDelta) + "<" + AppConfig.MIN_DELTA + ")");
            return null;
        }
        if (approxDelta > AppConfig.MAX_DELTA) {
            stats.inc("wheel: delta > max.delta (" + String.format("%.2f", approxDelta) + ">" + AppConfig.MAX_DELTA + ")");
            return null;
        }

        double annualized = (mid * 100) / (strike * 100) * (365.0 / Math.max(dte, 1));
        if (annualized < AppConfig.MIN_ANNUALIZED_YIELD) {
            stats.inc("wheel: annualizedYield < min.annualized.yield ("
                    + String.format("%.1f%%", annualized * 100) + "<"
                    + String.format("%.1f%%", AppConfig.MIN_ANNUALIZED_YIELD * 100) + ")");
            return null;
        }

        Opportunity opp = new Opportunity();
        opp.ticker = ticker;
        opp.strategy = Opportunity.Strategy.WHEEL_CSP;
        opp.stockPrice = stockPrice;
        opp.strike = strike;
        opp.expiration = expiration;
        opp.dte = dte;
        opp.midPremium = mid;
        opp.delta = approxDelta;
        opp.openInterest = oi;
        opp.bid = bid;
        opp.ask = ask;
        opp.annualizedYield = annualized;
        stats.incAcceptedWheel();
        return opp;
    }

    private Opportunity buildPmccLeap(String ticker, double stockPrice,
                                      JsonNode call, LocalDate expiration, int dte,
                                      RejectionStats stats) {
        double strike = call.path("strike").asDouble();
        if (strike > stockPrice * 0.90) {
            stats.inc("pmcc: LEAP pas assez ITM (strike > 90% du prix)");
            return null;
        }

        double bid = call.path("bid").asDouble(0);
        double ask = call.path("ask").asDouble(0);
        double last = call.path("lastPrice").asDouble(0);
        double mid = (bid > 0 && ask > 0) ? (bid + ask) / 2.0 : last;

        if (mid < 1.0) {
            stats.inc("pmcc: premium LEAP trop faible (mid<1.0)");
            return null;
        }

        long oi = call.path("openInterest").asLong(0);
        if (oi < 20) {
            stats.inc("pmcc: openInterest LEAP < 20");
            return null;
        }

        double moneyness = (stockPrice - strike) / stockPrice;
        double approxDelta = Math.min(0.95, 0.55 + moneyness * 1.5);

        if (approxDelta < AppConfig.MIN_LEAP_DELTA) {
            stats.inc("pmcc: delta < min.leap.delta (" + String.format("%.2f", approxDelta) + "<" + AppConfig.MIN_LEAP_DELTA + ")");
            return null;
        }
        if (approxDelta > AppConfig.MAX_LEAP_DELTA) {
            stats.inc("pmcc: delta > max.leap.delta (" + String.format("%.2f", approxDelta) + ">" + AppConfig.MAX_LEAP_DELTA + ")");
            return null;
        }

        Opportunity opp = new Opportunity();
        opp.ticker = ticker;
        opp.strategy = Opportunity.Strategy.PMCC;
        opp.stockPrice = stockPrice;
        opp.strike = strike;
        opp.expiration = expiration;
        opp.dte = dte;
        opp.midPremium = mid;
        opp.delta = approxDelta;
        opp.openInterest = oi;
        opp.bid = bid;
        opp.ask = ask;
        opp.annualizedYield = 0;
        stats.incAcceptedPmcc();
        return opp;
    }

    static final class RejectionStats {
        final String ticker;
        double stockPrice;
        int expirationsSeen;
        int putsInDteWindow;
        int callsLeapWindow;
        int acceptedWheel;
        int acceptedPmcc;
        String fatal;
        final Map<String, Integer> reasons = new LinkedHashMap<>();

        RejectionStats(String ticker) { this.ticker = ticker; }
        void inc(String reason) { reasons.merge(reason, 1, Integer::sum); }
        void markFatal(String reason) { this.fatal = reason; }
        void incAcceptedWheel() { acceptedWheel++; }
        void incAcceptedPmcc() { acceptedPmcc++; }

        void printSummary(int kept) {
            System.out.println();
            System.out.println("  --- Diagnostic " + ticker
                    + (stockPrice > 0 ? String.format(" (prix %.2f)", stockPrice) : "")
                    + " ---");
            if (fatal != null) {
                System.out.println("  STOP: " + fatal);
                System.out.println("  ----------------------");
                return;
            }
            System.out.println("  Expirations chargees : " + expirationsSeen);
            System.out.println("  Puts fenetre Wheel [" + AppConfig.MIN_DTE + "-" + AppConfig.MAX_DTE + "] : " + putsInDteWindow);
            System.out.println("  Calls LEAP (dte>=" + AppConfig.MIN_LEAP_DTE + ") : " + callsLeapWindow);
            System.out.println("  Acceptes Wheel / PMCC : " + acceptedWheel + " / " + acceptedPmcc);
            System.out.println("  Conserves : " + kept);
            if (!reasons.isEmpty()) {
                System.out.println("  Rejets par critere :");
                reasons.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(12)
                        .forEach(e -> System.out.println("    - " + e.getKey() + " : " + e.getValue()));
            }
            System.out.println("  ----------------------");
        }
    }
}
