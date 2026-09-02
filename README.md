# Wheel + PMCC Scanner (Java)

Scanner d opportunités pour la **Wheel Strategy** (priorité) et le **Poor Man s Covered Call**.

## Fonctionnalités

- Watchlist de titres <= 30 $
- Filtrage selon critères stricts (delta, DTE, open interest, spread...)
- Analyse des événements via **Groq** (IA gratuite)
- Envoi automatique des résultats sur un **groupe Telegram**
- Architecture prête pour basculer vers **IBKR** plus tard

## Prérequis

- Java 17+
- Maven 3.8+

## Configuration

Édite le fichier :

```
src/main/resources/application.properties
```

Exemple :

```properties
groq.api.key=gsk_ta_cle
telegram.bot.token=123456:AAF...
telegram.chat.id=-100xxxxxxxxxx
debug=false
```

Tu peux aussi placer `application.properties` **à côté du jar** après compilation.

Les variables d environnement restent prioritaires si elles sont définies.

## Lancement

```bash
mvn clean package
java -jar target/wheel-scanner-1.0.0.jar
```

## Passer à IBKR plus tard

Dans `Main.java`, remplace :

```java
DataProvider provider = new YahooDataProvider();
```

par l implémentation IBKR.
