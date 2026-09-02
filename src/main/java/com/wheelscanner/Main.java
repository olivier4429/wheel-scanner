package com.wheelscanner;

import com.wheelscanner.ai.EventChecker;
import com.wheelscanner.config.AppConfig;
import com.wheelscanner.data.DataProvider;
import com.wheelscanner.data.YahooDataProvider;
import com.wheelscanner.filter.StrategyFilter;
import com.wheelscanner.model.Opportunity;
import com.wheelscanner.notify.TelegramNotifier;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("=== Scanner Wheel (priorite) + PMCC ===");
        System.out.println("Source de donnees : Yahoo Finance");
        AppConfig.printLoadStatus();
        System.out.println("Watchlist : " + AppConfig.WATCHLIST.length + " tickers");
        System.out.println("Filtres Wheel DTE=[" + AppConfig.MIN_DTE + "-" + AppConfig.MAX_DTE
                + "] delta=[" + AppConfig.MIN_DELTA + "-" + AppConfig.MAX_DELTA
                + "] OI>=" + AppConfig.MIN_OPEN_INTEREST
                + " spread<=" + (int) (AppConfig.MAX_SPREAD_PCT * 100) + "%"
                + " yield>=" + (int) (AppConfig.MIN_ANNUALIZED_YIELD * 100) + "%/an");
        System.out.println();

        DataProvider provider = new YahooDataProvider();
        List<Opportunity> allOpportunities = new ArrayList<>();

        for (String ticker : AppConfig.WATCHLIST) {
            System.out.println("Scan " + ticker + " ...");
            try {
                List<Opportunity> found = provider.findOpportunities(ticker);
                allOpportunities.addAll(found);
                if (found.isEmpty()) {
                    System.out.println("  => 0 opportunite (voir diagnostic ci-dessus)");
                } else {
                    System.out.println("  => " + found.size() + " opportunite(s)");
                }
            } catch (Exception e) {
                System.out.println("  => ERREUR : " + e.getMessage());
            }

            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {}
        }

        System.out.println("\nTotal brut trouve : " + allOpportunities.size());

        List<Opportunity> filtered = StrategyFilter.apply(allOpportunities);
        System.out.println("Apres filtre strategie : " + filtered.size());

        EventChecker ai = new EventChecker();
        int aiCalls = 0;
        for (Opportunity opp : filtered) {
            if (aiCalls >= AppConfig.MAX_AI_CALLS_PER_RUN) break;
            System.out.print("Analyse IA " + opp.ticker + " ... ");
            ai.analyze(opp);
            System.out.println(opp.aiRecommendation + " | " + opp.eventSummary);
            aiCalls++;
        }

        System.out.println("\n==================== RESULTATS ====================");
        if (filtered.isEmpty()) {
            System.out.println("Aucune opportunite interessante.");
        } else {
            for (Opportunity o : filtered) {
                if (!"avoid".equalsIgnoreCase(o.aiRecommendation)) {
                    System.out.println(o);
                }
            }
        }
        System.out.println("===================================================\n");

        new TelegramNotifier().sendResults(filtered);
    }
}
