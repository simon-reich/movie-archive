# MovieArchive — Projektübersicht & Konzepterklärungen

> Zielgruppe: Entwickler mit Frontend-Hintergrund (JS/TS), neu im Java/Spring-Ökosystem

---

## 1. Was wir bisher gebaut haben (Phase 0, MOV-9–11)

| Pfad | Was steckt drin |
|---|---|
| `backend/` | Spring Boot 3 + Java 21 Skeleton. Alle Dependencies konfiguriert, Package-Struktur angelegt — noch kein Feature-Code. |
| `frontend/` | Nuxt 3 + Vue 3 + TypeScript Skeleton. Tailwind, Pinia, Vitest, Playwright konfiguriert. Zeigt gerade nur eine leere Landing-Page. |
| `docker-compose.yml` | Startet alle Infrastruktur-Dienste lokal. Details → Abschnitt Dev vs. Prod. |
| `Caddyfile` | Konfiguration für den Reverse-Proxy Caddy. Regelt, welche Requests wohin gehen. Details → Abschnitt Reverse-Proxy. |
| `.env.example` | Template für alle Umgebungsvariablen (Passwörter, Secrets). Lokal als `.env` befüllen, nie committen. |
| `README.md` | Setup-Anleitung, Quick-Start, Service-URLs. |
| `CLAUDE.md` + `.claude/` | Kontext-Dokumentation für den KI-Assistenten. Wird automatisch in jede Session geladen. |

**Noch nicht gebaut:** Feature-Code, Datenbank-Tabellen, API-Endpoints, Auth, Movie-Fetch. Kommt ab Phase 1 (MOV-18+). Das Backend lässt sich aber schon starten — `/actuator/health` antwortet, das Frontend zeigt eine leere Seite.

---

## 2. Dev vs. Prod — zwei völlig verschiedene Betriebsarten

### Lokale Entwicklung (täglich)

Ziel: schnell Code schreiben, sofort Feedback sehen, Mails lokal abfangen.

```bash
# 1. Nur Infra starten (Postgres, OpenSearch, Mailpit)
docker compose up -d

# 2. Backend nativ starten (Port 8080)
cd backend && ./gradlew bootRun

# 3. Frontend nativ starten (Port 3000, mit Hot-Reload)
cd frontend && pnpm dev
```

- Browser öffnet `localhost:3000` (Nuxt direkt) oder `localhost:8080/api` (Backend direkt)
- **Kein Caddy dazwischen** — du siehst die Ports direkt
- Änderungen an `.vue`-Dateien sind sofort im Browser sichtbar (Hot-Module-Replacement)
- Backend-Restart dauert Sekunden, kein Docker-Build nötig
- **Mails** landen in Mailpit (`localhost:8025`) — kein echtes SMTP

**Vorteile Dev:**
- Hot-Reload Frontend: sofortiges Feedback
- Debugger direkt an den Java-Prozess hängen
- Logs live im Terminal
- Kein Warten auf Docker-Builds

---

### Production (deployed)

Ziel: alles reproduzierbar in Containern, echter SMTP, HTTPS automatisch.

```bash
docker compose --profile app up -d
```

- Startet **alles**: Postgres, OpenSearch, Mailpit, Backend-Container, Frontend-Container, Caddy
- Caddy empfängt alle Requests auf Port 80/443
- Leitet `/api/*` ans Backend, alles andere ans Frontend
- Holt automatisch ein TLS-Zertifikat von Let's Encrypt (sobald `localhost` im Caddyfile durch eine echte Domain ersetzt wird)
- Echter SMTP-Provider (Brevo/Resend) schickt Mails raus

**Vorteile Prod:**
- Reproduzierbar: jeder Container hat exakt dieselbe Umgebung
- Isoliert: Services sehen nur was sie brauchen (Docker-Netzwerk)
- HTTPS automatisch via Caddy
- Einfaches Deployment: `git pull && docker compose --profile app up -d`

---

### Der entscheidende Unterschied

| | Dev | Prod |
|---|---|---|
| Backend | Nativ auf Mac (Port 8080) | Docker-Container |
| Frontend | Nativ auf Mac (Port 3000, Hot-Reload) | Docker-Container |
| Infra (Postgres, OpenSearch) | Docker | Docker |
| Proxy | Keiner (direkte Ports) | Caddy (Port 80/443) |
| Mail | Mailpit (lokal, kein Versand) | Brevo / Resend (echter Versand) |
| HTTPS | Nein | Ja (automatisch) |

---

## 3. Gradle — Build-Tool für Java

Gradle ist für Java/Spring das, was pnpm für Nuxt ist:

| pnpm | Gradle |
|---|---|
| `pnpm install` | `./gradlew dependencies` |
| `pnpm build` | `./gradlew build` |
| `pnpm dev` | `./gradlew bootRun` |
| `pnpm test` | `./gradlew test` |
| `package.json` | `build.gradle.kts` |

**Warum Gradle statt Maven?**
Maven ist die ältere Alternative (Konfiguration in `pom.xml`). Gradle ist schneller (inkrementelle Builds, Build-Cache) und nutzt Kotlin als Konfigurationssprache statt XML — lesbarer und typ-sicher.

**Was ist `.kts`?**
Unsere `build.gradle.kts`-Datei ist in Kotlin geschrieben. Das `.kts` steht für Kotlin Script. Das ist nur die Konfigurationssprache für den Build — der eigentliche App-Code ist weiterhin Java.

**Was ist `gradlew`?**
Der "Gradle Wrapper" im Repo. Stellt sicher, dass alle dieselbe Gradle-Version nutzen — egal ob lokal oder in CI. Du brauchst Gradle **nicht** global installieren. `./gradlew` lädt bei Bedarf die richtige Version selbst herunter.

**Was produziert Gradle?**
`./gradlew build` erzeugt `backend/build/libs/backend-0.0.1-SNAPSHOT.jar` — eine sogenannte **Fat JAR** (Uber JAR): eine einzige Datei, die die komplette App + alle Abhängigkeiten enthält. Diese JAR wird im Docker-Container gestartet: `java -jar backend.jar`

---

## 4. Reverse-Proxy — der Türsteher vor den Services

### Das Problem ohne Proxy

Du hast zwei Services: Backend auf Port 8080, Frontend auf Port 3000. Ohne Proxy müsste der Browser beide Adressen kennen — und hätte **CORS-Probleme**, weil `localhost:3000` und `localhost:8080` als verschiedene "Origins" gelten. Der Browser blockiert dann viele Requests.

### Die Lösung: Caddy sitzt davor

```
Browser → localhost (Port 80)
              │
              ▼ Caddy entscheidet anhand des Pfads:
         /api/*   → backend:8080
         alles    → frontend:3000
```

Für den Browser sieht alles wie **ein** Server aus (gleicher Origin). Kein CORS. Kein zweites Port.

### Forward-Proxy vs. Reverse-Proxy

- **Forward-Proxy**: steht vor dem *Client* (z.B. VPN, Firmen-Proxy) → `Browser → [Proxy] → Internet`
- **Reverse-Proxy**: steht vor dem *Server* → `Browser → [Proxy] → deine Services`

Wir nutzen Reverse-Proxy.

### Warum Caddy statt nginx?

- **nginx**: mächtig, aber komplexe Konfiguration (100+ Zeilen nur für HTTPS)
- **Caddy**: ~15 Zeilen Konfiguration, HTTPS vollautomatisch via Let's Encrypt, moderner, aktiv maintained

Unser komplettes Caddyfile hat ~50 Zeilen inkl. Security-Headers und Kommentaren.

### HTTPS automatisch

Sobald man `localhost` im Caddyfile durch eine echte Domain ersetzt (z.B. `moviearchive.example.com`), fragt Caddy automatisch bei Let's Encrypt ein TLS-Zertifikat an und erneuert es. Keine manuelle Zertifikatsverwaltung.

### Security-Headers

Caddy setzt bei jedem Response automatisch:
- `X-Content-Type-Options: nosniff` — Browser darf MIME-Type nicht raten
- `X-Frame-Options: DENY` — Seite kann nicht in einem iframe eingebettet werden
- `Referrer-Policy` — kontrolliert welche URL bei externen Links weitergegeben wird

---

## 5. SMTP und Mailpit — wie E-Mail-Versand funktioniert

### Was ist SMTP?

SMTP (Simple Mail Transfer Protocol) ist **das** Protokoll für E-Mail-Versand — so wie HTTP das Protokoll für Webseiten ist. Wenn eine App eine Mail sendet, spricht sie über SMTP mit einem Mail-Server.

```
App (Spring Boot) → SMTP-Verbindung → Mail-Server → Empfänger-Inbox
```

Man braucht dafür: Host, Port, Username, Password eines Mail-Servers. Das ist SMTP — kein Framework, kein Library-Name, sondern ein Protokoll aus den 1980ern, das bis heute funktioniert.

### SMTP in Production

In Production will man einen professionellen SMTP-Provider, weil eigene Mail-Server oft als Spam markiert werden und Provider sich um Deliverability und Bounce-Handling kümmern.

Unsere Empfehlungen:
- **Brevo** (früher Sendinblue): 300 Mails/Tag kostenlos
- **Resend**: 3.000 Mails/Monat kostenlos

Konfiguration nur über `.env`:
```env
MAIL_HOST=smtp.brevo.com
MAIL_PORT=587
MAIL_USERNAME=dein@account.com
MAIL_PASSWORD=dein-api-key
```

### Was ist Mailpit?

Mailpit ist ein **Fake-SMTP-Server** für die lokale Entwicklung.

**Das Problem ohne Mailpit:** App sendet Mail → landet bei echtem User → nervt, kostet ggf. Geld, und bei Test-Adressen landen Mails bei fremden Leuten.

**Mit Mailpit:**
```
App sendet Mail → Mailpit fängt ab → Mail geht nirgendwo hin
                                   → sichtbar unter localhost:8025
```

Mailpit verhält sich aus Sicht von Spring Boot wie ein echter SMTP-Server (Port 1025, kein Auth nötig). Der einzige Unterschied: Mails gehen nicht raus. Perfekt zum Testen von Verification-Links, Password-Reset-Mails etc.

---

## 6. Docker Compose Profiles — selektives Starten von Services

### Das Konzept

Ohne Profiles: `docker compose up` startet **alle** definierten Services.  
Mit Profiles: Services können einer Gruppe zugeordnet werden. Services ohne Profil starten immer. Services mit Profil nur wenn das Profil explizit aktiviert wird.

### Unser Setup

| Service | Profil | Wann gestartet |
|---|---|---|
| `postgres` | — | immer |
| `opensearch` | — | immer |
| `opensearch-dashboards` | — | immer |
| `mailpit` | — | immer |
| `backend` | `app` | nur mit `--profile app` |
| `frontend` | `app` | nur mit `--profile app` |
| `caddy` | `app` | nur mit `--profile app` |

```bash
docker compose up -d               # → nur Infra (Dev-Workflow)
docker compose --profile app up    # → alles (Prod-Workflow)
```

### Warum diese Aufteilung?

In der Entwicklung startest du Backend und Frontend lokal — da braucht man die Container nicht. Würde man sie trotzdem starten, gäbe es Port-Konflikte (Backend-Container auf 8080 blockiert deinen lokalen Spring Boot).

Die Aufteilung in Profiles löst das sauber: **ein** `docker-compose.yml`, zwei Betriebsmodi.

---

## 7. @Async in Spring — Hintergrundaufgaben

### Der Vergleich zu JS async/await

Du kennst async/await aus JavaScript — das Prinzip ist ähnlich, die Mechanik aber grundlegend anders.

### Java ist Multi-Threaded

Jeder eingehende HTTP-Request bekommt in Java einen eigenen Thread. Wenn dieser Thread blockiert (auf eine externe API wartet), hängt der gesamte Request — der User wartet.

**Ohne @Async:**
```
User klickt "Film speichern"
→ TMDB-Call   (500ms)
→ OMDB-Call   (300ms)
→ Wikipedia   (800ms)
→ Postgres    (50ms)
→ OpenSearch  (100ms)
→ User wartet ~1,75 Sekunden
```

Schlecht UX — besonders wenn APIs langsam sind oder Retries nötig werden.

**Mit @Async:**
```
User klickt "Film speichern"
→ Backend antwortet SOFORT: "202 Accepted" (~5ms)
→ Hintergrund-Thread läuft parallel:
    TMDB → OMDB → Wikipedia → Postgres → OpenSearch
→ User sieht den Film schon in der UI
```

`@Async` ist eine Annotation auf einer Java-Methode. Spring startet die Methode automatisch in einem Thread-Pool, ohne dass der Aufrufer warten muss.

### JS async/await vs. Spring @Async

| | JS async/await | Spring @Async |
|---|---|---|
| Threading | Single-Threaded, Event-Loop | Echter neuer OS-Thread |
| Mechanik | `await` pausiert die Funktion | Caller läuft sofort weiter |
| Gut für | I/O-Operationen | Lang laufende I/O-Ketten |
| Ergebnis | Non-blocking Caller | Non-blocking Caller |

Das Ergebnis (Caller blockiert nicht) ist dasselbe, der Mechanismus ist fundamental anders.

### @Retryable — automatische Wiederholung bei Fehlern

Dazu kommt `@Retryable` (Spring Retry): Wenn ein externer API-Call fehlschlägt (Timeout, 503 etc.), wird er automatisch bis zu **3× wiederholt** — mit exponentiellem Backoff (1s → 2s → 4s Pause).

Das passiert transparent: der Code weiß nichts davon. Nach 3 Fehlversuchen wird der Fehler geloggt, der Film wird ohne die fehlenden Daten gespeichert (z.B. ohne Wikipedia-Daten, wenn Wikipedia nicht erreichbar war).

---

## 8. Das große Bild — wie alles zusammenspielt

### Beispiel: "Film speichern" klicken

```
1. Browser → Caddy (Port 80)
   Caddy: /api/movies/save → weiter an backend:8080

2. Spring Boot empfängt Request
   → antwortet SOFORT: 202 Accepted (dank @Async)
   → startet Hintergrund-Thread:

3. Hintergrund-Thread:
   a. TMDB API (mit @Retryable)
   b. OMDB API, falls Key vorhanden (mit @Retryable)
   c. Wikipedia (mit @Retryable)
   d. Postgres INSERT
   e. OpenSearch INDEX

4. Frontend hat die 202-Antwort längst — zeigt Film in der UI

5. Mails (z.B. Sign-Up Verification):
   Dev:  Backend → Mailpit (localhost:1025) → sichtbar unter localhost:8025
   Prod: Backend → Brevo (SMTP) → echter Posteingang des Users
```

### Verantwortlichkeiten auf einen Blick

| Technologie | Aufgabe |
|---|---|
| **Gradle** | Baut das Backend-JAR ("kompiliert und verpackt") |
| **Docker** | Isolierte, reproduzierbare Umgebungen für alle Services |
| **Profiles** | Wählt welche Services starten (dev vs. prod) |
| **Caddy** | Ein Eingang für alles, kein CORS, automatisch HTTPS |
| **@Async** | Save-Flow blockiert User nicht, läuft im Hintergrund |
| **@Retryable** | Externe APIs dürfen kurz ausfallen, ohne den Flow zu brechen |
| **Mailpit** | Mails lokal abfangen ohne echten Provider |
| **SMTP** | Protokoll für Mail-Versand (Dev: Mailpit, Prod: Brevo) |
| **Postgres** | Source of Truth (alle User- und Film-Daten) |
| **OpenSearch** | Suchabfragen (abgeleitet aus Postgres, jederzeit rebuildbar) |
