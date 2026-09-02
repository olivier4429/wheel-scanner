package com.wheelscanner.notify;

import com.wheelscanner.config.AppConfig;
import com.wheelscanner.model.Opportunity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TelegramNotifier {

    private final HttpClient client = HttpClient.newHttpClient();

    public void sendResults(List<Opportunity> opportunities) {
        String token = AppConfig.TELEGRAM_BOT_TOKEN;
        String chatId = AppConfig.TELEGRAM_CHAT_ID;

        if (token == null || token.isBlank() || token.contains("xxxx")
                || chatId == null || chatId.isBlank() || chatId.contains("xxxx")) {
            System.out.println("Telegram non configure.");
            System.out.println("  Attendu dans application.properties :");
            System.out.println("    telegram.bot.token=123456:ABC...");
            System.out.println("    telegram.chat.id=-100...");
            System.out.println("  Puis: mvn clean package  OU  fichier application.properties a cote du jar");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Scanner Wheel + PMCC\n");
        sb.append(java.time.LocalDateTime.now()).append("\n\n");

        if (opportunities == null || opportunities.isEmpty()) {
            sb.append("Aucune opportunite interessante aujourd'hui.");
        } else {
            int count = 0;
            for (Opportunity o : opportunities) {
                if ("avoid".equalsIgnoreCase(o.aiRecommendation)) continue;
                count++;
                sb.append(String.format(
                        "%s | %s\nStrike: %.2f | Exp: %s (%d j)\nPremium: %.2f | d~%.2f | Ann: %.1f%%\nAI: %s\n\n",
                        o.ticker, o.strategy, o.strike, o.expiration, o.dte,
                        o.midPremium, o.delta, o.annualizedYield * 100, o.aiRecommendation
                ));
                if (count >= 12) {
                    sb.append("... (tronque)");
                    break;
                }
            }
            if (count == 0) {
                sb.append("Aucune opportunite retenue apres filtre IA.");
            }
        }

        try {
            String text = URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
                    token, chatId, text
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Resultats envoyes sur Telegram");
            } else {
                System.err.println("Erreur Telegram HTTP " + response.statusCode() + " : " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Erreur envoi Telegram : " + e.getMessage());
        }
    }
}
