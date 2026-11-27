# Namma Ooru Food

Pickup-only food ordering for village hotels: customers order ahead, hotels accept and prepare, customers collect with a token.
Kotlin Multiplatform (Compose Multiplatform) clients plus a Ktor + MySQL backend.

## Modules

| Module | Purpose |
|---|---|
| `server` | Ktor/Netty API, MySQL (Flyway migrations, Exposed transactions, HikariCP), JWT + rotating refresh tokens |
| `app/shared` | Shared Compose UI: login, Customer app, Hotel Owner app, Admin dashboard (chosen by the user's role) |
| `app/androidApp`, `app/iosApp`, `app/desktopApp`, `app/webApp` | Platform entry points calling `App()` |
| `telegramMiniApp` | Separate Kobweb customer Mini App; connects to the Ktor API |
| `core` | Shared KMP code |

## Backend

```
mysql -uroot -p -e "CREATE DATABASE nammaoorufood; CREATE DATABASE nammaoorufood_test;"
APP_ENV=local DB_URL=jdbc:mysql://localhost:3306/nammaoorufood DB_USER=namma_app DB_PASSWORD='replace-me' ./gradlew :server:run
APP_ENV=test SERVER_TEST_DB_URL=jdbc:mysql://localhost:3306/nammaoorufood_test DB_USER=namma_test DB_PASSWORD='replace-me' ./gradlew :server:test
./gradlew :app:shared:jvmTest                # client tests
```

Server tests truncate tables in the database selected by `SERVER_TEST_DB_URL` (default: `nammaoorufood_test`).
Use a dedicated disposable test database, never a development or production database.

* `APP_ENV` is **required**. Set `DB_USER` and `DB_PASSWORD` in every environment; there are no database credential defaults. Only `local` and `test` enable development defaults (JWT secret, seeded accounts
  `admin`/`owner`/`customer`); any other value is treated as production and requires every variable in `.env.example`.
* Startup validates/applies Flyway migrations before creating the HikariCP pool. Flyway remains the only schema manager;
  Exposed maps the existing tables and manages transactions. Existing non-user repositories retain their SQL inside those transactions.
* ImageKit configuration is required at startup: set `IMAGEKIT_PUBLIC_KEY`, `IMAGEKIT_PRIVATE_KEY`, and
  `IMAGEKIT_URL_ENDPOINT` in the server environment (see `.env.example`). Keep the private key on the server only.
  `GET /api/imagekit/upload-auth` requires a hotel-owner access token and returns temporary upload credentials.
  Add the matching **public** values to the ignored project-root `local.properties`, then rebuild the client:

  ```properties
  IMAGEKIT_PUBLIC_KEY=public_replace_me
  IMAGEKIT_URL_ENDPOINT=https://ik.imagekit.io/replace_me
  ```

  Never put the private key in `local.properties` or a client build. If client configuration is missing or does not
  match the server, image uploads fail with an error; forms without images continue to work. The owner app lets users
  select and crop an image before submission. The crop is exported as PNG; crops over 2 MiB are converted to JPEG
  and reduced in quality and, if necessary, dimensions. JPEG conversion does not preserve transparency.
  ImageKit uploads go directly from the client using short-lived credentials; the owner access token is not sent to
  ImageKit. A successful upload is reused on retry if saving the form fails; unreferenced uploads are not deleted
  automatically.
* API docs: Swagger UI at `/swagger` (spec: `server/src/main/resources/openapi/documentation.yaml`). Disabled in production
  unless `ENABLE_API_DOCS=true`. The spec is maintained by hand, so update it when routes change.
* Customers and hotel owners can create accounts from their respective apps. Public registration creates only the
  corresponding customer or owner role; newly registered hotels still require administrator approval.
* Order lifecycle: `PLACED → ACCEPTED → PREPARING → READY_FOR_PICKUP → COMPLETED`, or `PLACED → REJECTED`.
  Pickup tokens are assigned on acceptance and numbered per hotel per day. Prices are always computed on the server.
* A background job every 6 hours deletes refresh sessions expired/revoked more than 7 days ago, notifications older than
  90 days, and delivered or permanently failed Telegram outbox entries older than 90 days. Pending entries are retained.
  Orders are never deleted.

### Telegram customer bot

1. Put `TELEGRAM_BOT_TOKEN` in the ignored repository-root `.env` or in the server process environment.
   `./gradlew :server:run` reads this key from the root `.env` when it is absent from the environment; an
   environment value takes precedence. Other `.env` keys are **not** automatically loaded by Gradle: supply
   `APP_ENV`, database and ImageKit settings as environment variables as described above, or use Docker's `--env-file`.
   Never put the bot token in the Mini App, logs, or source control. Use separate bots and databases for test and production.
2. Set `TELEGRAM_MINI_APP_URL` in the server process environment to the Mini App's public HTTPS origin.
   The bot adds an **Open Mini App** button to `/start` and `/help`. Alternatively, configure the menu button or
   main Mini App URL with BotFather. The button must open the real app without `?mock=true` for real orders.
   Set `CORS_ALLOWED_ORIGINS` to the exact Mini App HTTPS origin in production.
3. The Mini App sends `{ "initData": "<raw Telegram.WebApp.initData>" }` to `POST /auth/telegram`. The server
   checks Telegram's signature and a five-minute freshness window, then returns the usual access and rotating refresh
   tokens. Reauthenticate with fresh init data on reload if keeping tokens in memory. An invalid or expired signature
   is rejected. Telegram customers have their own account and history, separate from password customers.
4. Customers must open the bot's **private chat** and send `/start` to enable messages. They can use `/orders` to
   view active orders and `/cancel ORDER_ID` to cancel their own order while it is `PLACED`. Ordering itself uses
   the Mini App and existing customer APIs; use an `Idempotency-Key` for order placement.

The server polls Telegram for updates and sends placed, accepted, rejected, preparing, ready, completed and cancelled
status messages. Status changes and outgoing messages are recorded atomically; delivery retries rate limits and
transient failures. Delivery is at least once, so a crash after Telegram accepts a message can cause a duplicate.
Messages remain queued until the user starts the bot; a user who blocks it must start it again. Telegram may discard
incoming updates after 24 hours of downtime. This integration does not link owner or mobile accounts to Telegram.

Long polling cannot run while a webhook is configured for the same bot. Run **exactly one** polling instance per bot:
set `TELEGRAM_BOT_POLLING_ENABLED=false` on other server instances, and enable it on a replacement if the polling instance
fails. Test mode disables polling by default. Check server logs for Telegram API status codes, especially 409 (webhook
or competing poller); the bot token is never included in those logs.

### Kobweb Telegram Mini App

The `telegramMiniApp` module provides the pickup customer flow: open hotels, menus, a single-hotel cart, orders and
order cancellation. It uses `POST /auth/telegram` and the existing customer APIs; pricing, stock and order transitions
remain server-authoritative. The orders screen shows the most recent 50 orders and refreshes every five seconds.
The estimated cart total can differ from the final server-calculated total.

1. Build the production static site with `kobweb export --notty --layout static --path telegramMiniApp`. The output is
   `telegramMiniApp/.kobweb/site/`. This requires the Kobweb CLI; a development bundle can also be built with
   `./gradlew :telegramMiniApp:jsBrowserDevelopmentWebpack`.
2. Host the exported files at the root of an HTTPS origin. Edit the **deployed** `mini-app-config.json` to set the
   public server origin, for example `{"apiBaseUrl":"https://api.example.com"}`. Use an empty value only when the
   API is reverse-proxied at the same origin. The value must be an origin without a path; HTTP is accepted only for
   `localhost` or `127.0.0.1`. Configure the server's `CORS_ALLOWED_ORIGINS` with the exact Mini App origin. For a
   local build, the initial configuration lives at `telegramMiniApp/src/jsMain/resources/public/mini-app-config.json`;
   `http://localhost:8080` works when both browser and server run on the same machine.
3. Configure that HTTPS URL as the bot's menu button or main Mini App in BotFather. The app must be opened from
   Telegram to receive signed init data; opening it directly in a browser does not sign a user in. For local
   development, `kobweb run --notty --foreground --path telegramMiniApp` serves the module on port 8083; use a
   trusted HTTPS endpoint accessible from Telegram for device testing.

Access and refresh tokens are held in memory only. An unconfirmed order and its idempotency key are kept in the
current tab's session storage so a reload can retry the same request; they are cleared on success or a definitive
client error. If Telegram discards the webview/session storage after an uncertain submission, check **My orders**
before placing the order again. Bot chat messages require the customer to start the bot separately.

For a UI preview without real ordering, run `kobweb run --notty --foreground --path telegramMiniApp`
and open `http://localhost:8083/?mock=true` (or use the port configured in `telegramMiniApp/.kobweb/conf.yaml`).
Mock mode is enabled on `localhost`, `127.0.0.1`, and the development tunnel `pulsate-shaded-crust.ngrok-free.dev`
with that query parameter. It shows sample hotels and menus; orders and cancellations are simulated in memory and
disappear on reload. Anyone with access to the tunnel can use mock mode; remove the tunnel hostname before production.
Remove the parameter to use the real API.

To launch that mock preview from the bot, start the backend in `APP_ENV=local` with the usual database and ImageKit
settings, a **test bot** token, and `TELEGRAM_MINI_APP_URL=https://pulsate-shaded-crust.ngrok-free.dev/?mock=true`.
Keep the ngrok tunnel running to port 8083, then send `/start` to the bot and select **Open Mini App**.
The bot token is the only setting automatically read from `.env` by Gradle; export `TELEGRAM_MINI_APP_URL` in the
server environment. Mock orders are not saved to the backend and cannot trigger bot updates or owner actions.
The mock URL is rejected in production. For real orders, remove `?mock=true`, configure a separate public HTTPS
API origin in the deployed `mini-app-config.json`, and use the same bot token for the backend and BotFather.

### Resetting a development database

The initial Flyway schema now includes nullable hotel and food-item image URLs. An existing database has a different
`V1` checksum and must be reset before using this development schema. **This permanently deletes every table and all
data in the selected database. Never run this against production.** Stop the server, verify the host and database name,
and explicitly run the following only for the local `nammaoorufood` development database:

```sh
mysql -h 127.0.0.1 -u root -p -e 'DROP DATABASE nammaoorufood; CREATE DATABASE nammaoorufood;'
```

Reapply any grants needed by the application database user, then start the server normally; Flyway recreates all tables.
The server does not automatically clean or reset the database on migration errors.

## Deployment

```
docker build -t namma-ooru-food-server .
docker run --env-file .env -p 8080:8080 namma-ooru-food-server
```

Create the first admin with `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` (once), terminate TLS in front of the
service, and use a managed MySQL with TLS. Never commit `.env`.

## Clients

API base URL: Android `-PnammaApiUrl=https://api.example.com` (default emulator `10.0.2.2:8080`), iOS `NammaApiUrl` Info.plist key, desktop `NAMMA_API_URL` env var (all default to localhost). Web is still hardcoded to `localhost:8080`.
Session storage: Android Keystore, iOS Keychain, encrypted file on desktop, **memory only on web** (a page reload signs out).

Business rules: customers may cancel only while an order is `PLACED` (`POST /orders/{id}/cancel`); pickup tokens are per hotel per day;
`REJECTED`, `CANCELLED` and `COMPLETED` are terminal; payment is at pickup.

Known limitations: no native-app push notifications (order status and ready-for-pickup banners are polled every 5 s), web sessions are memory-only
(HttpOnly-cookie sessions with CSRF protection are not implemented), no payment integration, Swagger spec is hand-written,
UI has only been compiled and ViewModel-tested (no Compose UI tests, not visually reviewed on devices).

CI: `.github/workflows/ci.yml` runs server tests (MySQL service), client JVM tests and desktop/web compiles.

## Template notes

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
  - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`
- Web tests:
  - Wasm target: `./gradlew :app:shared:wasmJsTest`
  - JS target: `./gradlew :app:shared:jsTest`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
## Build variants

| Variant | Who | How to build/run |
|---|---|---|
| Customer | Customers | Android: `./gradlew :app:androidApp:assembleCustomerDebug` (id `dev.livin.nammaoorufood`); iOS (default); desktop `-Pvariant=customer` |
| Hotel Owner | Hotel owners | Android: `./gradlew :app:androidApp:assembleOwnerDebug` (id `dev.livin.nammaoorufood.owner`); desktop `-Pvariant=owner` |
| Admin | App maintainer | Web: `:app:webApp:wasmJsBrowserDevServer`; desktop: `./gradlew :app:desktopApp:run` (default) |

Each app refuses accounts of another role (client-side UX only; the server enforces roles). Desktop keeps a separate session store per variant. iOS has only the customer target.
