# Namma Ooru Food — Copilot Instructions

## Project context

Namma Ooru Food is a pickup-only food ordering platform for customers and local hotels. The repository is a Kotlin Multiplatform project: Compose Multiplatform clients share UI and business flows, while a Kotlin/Ktor backend serves a MySQL database. The shared client contains the customer app, hotel-owner app, and admin dashboard; there is no separate React frontend.

Read `README.md` for setup, environment variables, build variants, limitations, and deployment notes. Inspect relevant source and tests before changing behavior; implementation and README take precedence over stale documentation.

## Architecture map

- `app/shared/src/commonMain`: shared Compose UI, MVI-style ViewModels and intents, repositories, network DTOs/clients, authentication/session management, dependency injection.
- `app/shared/src/{androidMain,iosMain,jvmMain,jsMain,wasmJsMain}`: platform-specific implementations and clients.
- `app/androidApp`, `app/iosApp`, `app/desktopApp`, `app/webApp`: platform entry points/build configuration. Android and desktop support product variants; iOS is customer-only; web is the Compose admin client.
- `server/src/main/kotlin`: Ktor/Netty API, services, repositories, authentication, and configuration. `server/src/main/resources/db/migration` is the Flyway schema source; `server/src/main/resources/openapi/documentation.yaml` is the hand-maintained API spec.
- `core`: small shared KMP utilities.
- `gradle/libs.versions.toml`: dependency and plugin versions. Reuse catalog aliases.

Do not edit generated/build output or `server/bin` copies as the source of a change. Keep implementation changes in `src` and regenerate artifacts only through the normal build.

## Non-negotiable product and technical rules

- This is pickup-only. Do not add delivery workflows or payment processing unless explicitly requested.
- The backend is authoritative for roles, resource ownership, order state transitions, prices, and pickup tokens. Never rely on client-side checks for authorization or totals.
- Order states, cancellation rules, token behavior, session rules, and environment/security requirements are described in `README.md`; preserve them and test meaningful behavior changes.
- Keep secrets out of source, client builds, logs, and commits. ImageKit private credentials belong only in the server environment. Client configuration may contain only its public key and URL endpoint from ignored `local.properties`.
- Owner image uploads use short-lived credentials from the authenticated server endpoint and upload directly to ImageKit. Preserve validation, HTTPS/endpoint checks, and the 2 MiB limit/crop behavior.
- Keep owner-app status and action icons in Material Icons; do not use emoji as UI icons.
- Schema changes go through Flyway migrations. Update the hand-maintained OpenAPI YAML when API contracts change.
- Server startup requires explicit `APP_ENV`; only `local` and `test` may use development defaults. Treat all other environments as production and fail closed.
- Keep shared code compatible with supported KMP targets. Do not introduce platform-only APIs into `commonMain`.

## Core product behavior

- Order lifecycle: `PLACED → ACCEPTED → PREPARING → READY_FOR_PICKUP → COMPLETED`, or `PLACED → REJECTED`. Customers can cancel only while `PLACED`; `REJECTED`, `CANCELLED`, and `COMPLETED` are terminal.
- Pickup tokens are generated after owner acceptance and numbered per hotel per day. The server must make acceptance/token generation concurrency-safe.
- A customer order belongs to one hotel. The server validates item availability and calculates prices; order item name/price snapshots preserve historical totals.
- Only open hotels accepting orders can receive orders. New owner registrations create hotels that still require admin approval.
- Customer and owner accounts can register publicly for their own roles; admin accounts must be provisioned securely. Local/test seed accounts must never be enabled in production.
- Session restore happens before protected content is shown. Access tokens are short-lived and refresh sessions are persisted/rotated/revocable. Explicit logout clears local credentials even if the server is unreachable.
- Payment is at pickup; there is no payment integration. Order status and ready-for-pickup banners are currently polled every 5 seconds; push notifications are not implemented.
- Android, iOS, desktop, and web use different API URL configuration; consult the README before changing platform configuration. Web sessions are memory-only and reload signs the user out.

## Implementation and verification

Follow existing patterns instead of adding new frameworks or layers: immutable UI state and intents in ViewModels, repositories for client data access, Koin for dependency injection, and server routes → services → repositories → database transactions. Preserve cancellation behavior and surface failures through the existing result/error patterns.

Add or update tests with behavioral changes. Run the smallest relevant Gradle checks, commonly:

- `./gradlew :app:shared:jvmTest`
- `./gradlew :server:test` (requires the configured MySQL test database)
- `./gradlew :app:androidApp:assembleOwnerDebug`
- `./gradlew :app:shared:compileKotlinWasmJs :app:shared:compileKotlinJs`

Report only checks actually run and distinguish known limitations from verified behavior. Keep changes focused; do not overwrite unrelated user work.
