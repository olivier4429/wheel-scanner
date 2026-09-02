package com.wheelscanner.filter;

import com.wheelscanner.config.AppConfig;
import com.wheelscanner.model.Opportunity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Applique les filtres metier finaux et trie les resultats.
 * Wheel (CSP) en priorite.
 */
public final class StrategyFilter {

    private StrategyFilter() {}

    public static List<Opportunity> apply(List<Opportunity> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<Opportunity> filtered = new ArrayList<>();

        for (Opportunity opp : raw) {
            if (opp.strategy == Opportunity.Strategy.WHEEL_CSP) {
                if (opp.annualizedYield < AppConfig.MIN_ANNUALIZED_YIELD) {
                    continue;
                }
                filtered.add(opp);
            } else if (opp.strategy == Opportunity.Strategy.PMCC) {
                filtered.add(opp);
            }
        }

        return filtered.stream()
                .sorted(Comparator
                        .comparing((Opportunity o) -> o.strategy == Opportunity.Strategy.WHEEL_CSP ? 0 : 1)
                        .thenComparing(o -> o.annualizedYield, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }
}
