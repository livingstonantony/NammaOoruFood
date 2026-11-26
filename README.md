# Namma Ooru Food

Pickup-only food ordering for village hotels: customers order ahead, hotels accept and prepare, customers collect with a token.
Kotlin Multiplatform (Compose Multiplatform) clients plus a Ktor + MySQL backend.

## Modules

| Module | Purpose |
|---|---|
| `server` | Ktor/Netty API, MySQL (Flyway migrations, Exposed transactions, HikariCP), JWT + rotating refresh tokens |
| `app/shared` | Shared Compose UI: login, Customer app, Hotel Owner app, Admin dashboard (chosen by the user's role) |
| `app/androidApp`, `app/iosApp`, `app/desktopApp`, `app/webApp` | Platform entry points calling `App()` |
| `core` | Shared KMP code |

## Backend

```
mysql -uroot -p -e "CREATE DATABASE nammaoorufood; CREATE DATABASE nammaoorufood_test;"
APP_ENV=local DB_URL=jdbc:mysql://localhost:3306/nammaoorufood DB_USER=namma_app DB_PASSWORD='replace-me' ./gradlew :server:run
APP_ENV=test DB_URL=jdbc:mysql://localhost:3306/nammaoorufood_test DB_USER=namma_test DB_PASSWORD='replace-me' ./gradlew :server:test
./gradlew :app:shared:jvmTest                # client tests
```

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
* A background job every 6 hours deletes refresh sessions expired/revoked more than 7 days ago and notifications older than 90 days.
  Orders are never deleted.

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

Known limitations: no push notifications (order status and ready-for-pickup banners are polled every 5 s), web sessions are memory-only
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
