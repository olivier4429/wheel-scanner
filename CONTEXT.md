# CONTEXT.md — Wheel + PMCC Scanner

Document de contexte pour reprendre le projet (humain ou IA).
À lire en priorité si on ouvre une nouvelle conversation avec le code source.

---

## Objectif

Scanner d'opportunités d'options pour :

1. **Wheel Strategy** (priorité) — Cash-Secured Puts puis Covered Calls
2. **Poor Man's Covered Call (PMCC)** — LEAP long + short call

But : identifier des trades intéressants, vérifier les événements à venir (earnings, dividendes…), envoyer un résumé (console + Telegram).

---

## Profil utilisateur / contraintes

- Capital ≈ **10 000 $**
- Broker cible : **Interactive Brokers (IBKR)**
- Source de données **actuelle** : **Yahoo Finance** (non officielle, delayed)
- Architecture prête pour basculer vers **IBKR** plus tard (interface `DataProvider`)
- Langage : **Java 17 + Maven**
- IA gratuite : **Groq** (`llama-3.1-8b-instant`) pour l'analyse d'événements
- Notifications : **Telegram** (groupe)

---

## Architecture

```
com.wheelscanner
├── Main.java
├── config/AppConfig.java          ← lit application.properties
├── model/Opportunity.java
├── data/
│   ├── DataProvider.java          ← interface
│   ├── YahooDataProvider.java     ← implémentation actuelle
│   └── IbkrDataProvider.java      ← placeholder IBKR
├── filter/StrategyFilter.java
├── ai/EventChecker.java           ← Groq
└── notify/TelegramNotifier.java
```

Point de bascule Yahoo → IBKR dans `Main.java` :

```java
DataProvider provider = new YahooDataProvider();
// plus tard : new IbkrDataProvider(...)
```

---

## Configuration

**Fichier unique :** `src/main/resources/application.properties`

Contient :

- `watchlist` (tickers séparés par des virgules)
- critères Wheel (DTE, delta, OI, spread, yield annualisé…)
- critères PMCC (DTE / delta LEAP)
- clés Groq + Telegram
- `debug`

**Priorité de chargement :**

1. Variable d'environnement (ex. `GROQ_API_KEY`)
2. Fichier `./application.properties` à côté du jar (modifiable sans recompiler)
3. Fichier embarqué dans le jar (`src/main/resources/`)

**Décision importante :**
`max.stock.price` **n'est plus utilisé** comme filtre métier.
Le filtre prix a été (ou doit être) retiré dans `YahooDataProvider` : on garde seulement `if (price <= 0)`.

---

## Critères métier (valeurs par défaut dans properties)

### Wheel — Cash-Secured Put

- DTE : 25 – 50 jours
- Delta put ≈ 0.18 – 0.32
- Open interest ≥ 50
- Spread max (ask−bid)/mid ≤ 35 %
- Rendement annualisé minimum ≈ 12 %
- On doit être prêt à se faire assigner l'action

### PMCC

- LEAP : DTE ≥ 300, delta ≈ 0.70 – 0.85
- Short call (logique future) : delta ≈ 0.20 – 0.30, DTE court

---

## Flux d'exécution

1. Charger config
2. Pour chaque ticker de la watchlist → Yahoo (prix + chaîne d'options)
3. Construire des `Opportunity` (WHEEL_CSP prioritaires, PMCC)
4. `StrategyFilter` (tri Wheel d'abord, yield, etc.)
5. `EventChecker` (Groq, nombre d'appels limité)
6. Affichage console
7. Envoi Telegram (ignore les `avoid` si possible)

---

## Limitations actuelles (Yahoo)

- Données delayed
- Delta souvent **approximé** (Greeks Yahoo incomplets)
- Pas de vrai IV Rank
- Endpoint options Yahoo peut évoluer / rate-limit

Avec IBKR : Greeks, liquidité et pricing plus fiables.

---

## Build & run

```bash
mvn clean package
java -jar target/wheel-scanner-1.0.0.jar
```

Pour forcer une recompilation propre et reprendre les fichiers de conf embarqués :

```bash
mvn clean package
```

`clean` efface `target/`, `package` recompile **tout** et ré-embarque `src/main/resources/application.properties` dans le jar.

Si une copie `application.properties` est **à côté du jar**, c'est elle qui est lue en premier (sans recompiler).

---

## État du projet / décisions prises

- [x] Architecture modulaire DataProvider
- [x] YahooDataProvider fonctionnel (base)
- [x] Filtres Wheel + ébauche PMCC
- [x] Groq EventChecker
- [x] TelegramNotifier
- [x] Config externalisée (properties)
- [x] Commentaires détaillés sur chaque paramètre de config
- [ ] Filtre `max.stock.price` **désactivé / à retirer** du code de filtrage
- [ ] Implémentation réelle `IbkrDataProvider` + TWS/Gateway
- [ ] Short call PMCC automatisé
- [ ] Suivi / gestion des positions ouvertes

---

## Comment continuer (prompt type)

> Voici le zip du projet Wheel scanner. Lis `CONTEXT.md` et `application.properties`, puis [demande précise].

Exemples de suites possibles :

- retirer définitivement `max.stock.price` du code
- améliorer le parsing Yahoo / Greeks
- brancher IBKR
- durcir les filtres ou la watchlist
- cron / run quotidien

---

## Notes pour une IA

- Répondre en **français** (utilisateur francophone).
- Ne pas réintroduire un hard-cap prix action sauf demande explicite.
- Préférer des changements minimaux et localisés.
- Après modif de config embarquée : rappeler `mvn clean package`.
- Sécurité : ne pas committer de vraies clés API dans le repo.
