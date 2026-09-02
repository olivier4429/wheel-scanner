package com.wheelscanner.model;

import java.time.LocalDate;

/**
 * Représente une opportunité d'options (Wheel CSP ou PMCC).
 */
public class Opportunity {

    public enum Strategy {
        WHEEL_CSP,
        PMCC
    }

    public String ticker;
    public Strategy strategy;
    public double stockPrice;

    // Contrat principal (put pour Wheel, LEAP pour PMCC)
    public double strike;
    public LocalDate expiration;
    public int dte;
    public double midPremium;
    public double delta;
    public long openInterest;
    public double bid;
    public double ask;
    public double annualizedYield;

    // Pour PMCC uniquement
    public Double shortCallStrike;
    public LocalDate shortCallExpiration;
    public Double shortCallPremium;

    // Résultat de l'analyse IA
    public String eventSummary = "";
    public String aiRecommendation = "manual_check"; // keep / avoid / reduce_size / manual_check

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-6s | %-9s | Strike %.2f | Exp %s (%3dj) | Prem %.2f | Δ %.2f | Ann %5.1f%% | AI: %s",
                ticker,
                strategy,
                strike,
                expiration,
                dte,
                midPremium,
                delta,
                annualizedYield * 100,
                aiRecommendation));

        if (strategy == Strategy.PMCC && shortCallStrike != null) {
            sb.append(String.format(" | Short %.2f @ %s", shortCallStrike, shortCallExpiration));
        }
        return sb.toString();
    }
}
