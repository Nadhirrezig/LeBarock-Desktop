# LeBarock — AltosPOS desktop (counter app)

Windows desktop app for the **counter PCs** of **Le Baroque** (restaurant / bar). It keeps only the point-of-sale
work of the web caisse (`LeBarock-RestaubarCaisse`): POS, tables, orders, payment, cash register. The web caisse stays
on the counter for management and as a backup. Kotlin + Compose Multiplatform (Desktop, JVM). Name on Windows:
**AltosPOS** (the app); the restaurant is Le Baroque.

## Sibling repos

| Repo | Role | Contract with this app |
|------|------|------------------------|
| `LeBarock-RestaubarCaisse` | Laravel POS, the server and only source of truth | Same `/api/mobile/*` contract as the handheld (Sanctum token, `device_name` `altospos-desktop`). New caisse endpoints only where the review showed a need (cash register, expenses, discount, table operations, print jobs). Overview of step 4 in its `CLAUDE.md`. |
| `AltosPOS-Mobile` (branch `LeBarokMobileapp`) | Sunmi handheld app | The model: API layer, repository logic, ticket layouts and later the offline code are **copied** from it (no shared module yet). |
| Stock back / front | Stock | None directly. |

## Stack and commands

- Kotlin 2.4.21, Compose Multiplatform 1.12.2 (material3 1.12.0-alpha03), Gradle 9.5.1, JDK 21 (Gradle provisions Azul
  Zulu 21 through foojay), versions from the JetBrains wizard (kept, client 2026-11-08). Retrofit 3.0.0, OkHttp 5.5.1,
  kotlinx.serialization 1.12.0, material-icons-extended 1.7.3.
- Modules: `shared` (all app code, `src/jvmMain`; tests in `src/jvmTest`), `desktopApp` (window, installer settings).
- Run: `gradlew :desktopApp:run` (Windows: `gradlew.bat`). Tests: `gradlew :shared:jvmTest`.
- Installer: `gradlew :desktopApp:packageMsi` **on Windows** (jpackage + WiX, downloaded by the Compose plugin) →
  `desktopApp/build/compose/binaries/main/msi/AltosPOS-x.y.z.msi`. Raise `altospos.version` (gradle.properties or
  `-Paltospos.version=`) for each release: MSI versions are MAJOR.MINOR.BUILD; `upgradeUuid` never changes, so a new
  MSI replaces the installed one.
- Data folder: `%APPDATA%\AltosPOS` (`settings.json`, `logs\altospos-N.log`, `certs\`, `altospos.lock`);
  `-Daltospos.home=…` overrides it (tests, dev).
- **Cloud sessions:** Maven Central, Google Maven and foojay answer, but `cdn.azul.com` (the JDK download) is blocked:
  build with the local JDK 21 by setting `gradle/gradle-daemon-jvm.properties` aside for the build (and putting it
  back). Maven Central rate-limits (429): retry. The app runs under `Xvfb` + `xdotool` for screenshots
  (`apt-get install xdotool`); the caisse runs with `php artisan serve` on a sqlite file (see the caisse `CLAUDE.md`).
  **Never** use `/*` inside a KDoc (e.g. a route like `/api/mobile/*`): it opens a nested comment.

## Working rules (from the client)

- **Big task → review first** in the session chat (current state, plan, decisions, effort), wait for the OK, then build
  in small steps the client tests. Effort quoted in the review is the depth of work to deliver for each step.
- **No review/notes `.md` files in the repos.** `CLAUDE.md` is only for tracking and planning.
- Ask before building when anything is unclear; confirm intent even when it seems obvious.

## Decisions (review of 2026-11-08, approved by the client)

- **Server address** = protocol + IP + port, typed in Réglages like on the handheld (default HTTPS 443), with
  "Tester la connexion". Offline mode comes later, with the handheld's logic (`/api/sync/*`, `kind: "desktop"`).
- **Sign-in:** the restaurant's accounts (kept on the PC, refreshed from the caisse), pick one, type its password
  ("Mot de passe / PIN", as on the Sunmi). **The account decides the Caisse** (menu, drawer, session, stock till);
  Admin / Gérant may switch it (the caisse's terminal switch, kept on the account).
- **Shared counter:** "Changer d'utilisateur", and the session closes after **5 min** without a click, key or touch
  (Réglages: Jamais / 2 / 5 / 10 / 15 / 30 min), warning 30 s before; **never while a cart has unsent lines or a
  payment window is open** (those screens hold the lock). Closing = token deleted on the caisse, back to sign-in;
  nothing is lost (orders, payments and cash session belong to the Caisse). The token is never written to disk:
  each start of the app goes through the sign-in.
- **Accueil buttons by rights** (`abilities`): POS and Tables (orders), Commandes (show_orders), Encaisser
  (take_payment), Caisse (cash_register), Réglages (everyone). A kitchen account only gets Réglages (its screen is the
  22" kitchen screen's Android app).
- **Printing (D2), like the Sunmi:** Réglages → « Impression des tickets »: « Sur cet appareil » (default: the app
  prints its own bill / receipt / X / Z from the caisse's ticket JSON on the counter printer, connected by **USB**
  (Windows printer, raw ESC/POS through `javax.print`) **or Ethernet** (IP + port 9100)) / « Imprimante de la caisse »
  (the caisse prints on the Caisse's printer). "Directe" print agent: collects the caisse's print jobs for this
  printer when it is on USB (web backup, handhelds on « Imprimante de la caisse »); the branch key is fetched by an
  admin signing in once in Réglages. Keeps running in the Windows tray when the window is closed; starts with Windows.
- **Tables (D4): every operation of the web**: floor by area with live state (libre, occupée, addition demandée,
  réservée + next reservation, "Prêt n", QR count, waiting for stock), open with couverts, add rounds, release,
  transfer, merge, split by item, change waiter, customer, remove an extra charge, cancel check / line, Addition,
  Encaisser, payment-method correction cash ⇄ card, "Vérifié" for offline checks. Only QR approvals are left out.
- **X / Z (D2 caisse + D6):** the existing X / Z section switches (Settings → Sessions de caisse) get a « Ticket X / Z :
  Complet / Résumé » choice; the desktop screen shows exactly what prints. The X / Z currency format is fixed to follow
  the currency settings (screen, thermal, Sunmi, desktop).
- **Not included:** cash drawer, customer display, barcode, second screen, kitchen screen, waiter "Prêt"
  notifications, QR approvals.
- **HTTPS trust:** Java's list + the **Windows certificate store** (mkcert's rootCA installed in Windows) + root
  certificates imported in Réglages → Certificat (`certs\`). Errors say what to do (certificate not trusted, made for
  another address, HTTP port, port closed, no answer, not a caisse…).
- One AltosPOS per Windows session (lock file): a second copy would sign in and, from D2, print twice.

## How D1 is built (2026-11-08)

- `AppInfo` (name, version from `-Daltospos.version`, data folder), `AppLog` (journal, 5 × 1 MB, never a password /
  token / key), `SingleInstance`, `AppContainer`.
- `data/settings`: `ServerAddress` (protocol / host / port, validation with French messages, a pasted URL fills all
  three), `SettingsStore` (`settings.json`, atomic write, a damaged file kept as `.bad`, unknown keys ignored):
  address, restaurant name and currency from the last test, idle minutes, the account list, the last account.
- `data/remote`: `AltosApi` (the handheld's, without kitchen / waiter calls), `Dtos` (copied; `AbilitiesDto` + the
  counter rights, nullable for a caisse older than step 4), `ApiClient` (Bearer, `Accept: application/json`,
  User-Agent `AltosPOS-Desktop/x`, short-timeout client for the test), `TrustStore` (composite trust manager, import /
  remove a root certificate: refuses a non-CA certificate).
- `data/ApiFailure`: every failure → `FailureKind` + French message + hint (Laravel error bodies read as on the
  handheld: first validation message, `code`, `order_id`, `can_force`). 401 ends the session.
- `data/CaisseRepository`: test (ping, restaurant, server clock gap: warning past 2 min), save (another server drops
  the old account list), accounts (kept), login / me / logout, terminals / switch.
- `data/session`: `Rights.of(abilities)` (fallbacks for an older caisse), `Session` (memory only), `SessionHolder`.
- `ui`: `ScreenModel` (state holder with a UI-thread scope, no Android ViewModel), `Navigator` (small back stack),
  `IdleMonitor` (AWT listener on mouse / key / wheel, holds), theme (handheld's green, larger type, light only),
  Réglages (Serveur, Certificat, Session, Application: version, Java, folder, journals), sign-in (search ignoring
  case / accents, Enter opens a single match, last account first, password window with keypad, Enter / Esc),
  Accueil (restaurant, clock, Caisse chip + picker for managers, user, "Changer d'utilisateur", tiles; sections not
  built yet say which step brings them; a section needing a Caisse without one opens "Pas de caisse").
- Window "AltosPOS — Le Baroque", maximized, minimum 1024 × 680, icon from the handheld's logo
  (`desktopApp/icons`: .ico for the MSI, .png). Closing the window signs out on the caisse (2 s at most).
- MSI: `AltosPOS`, menu group + desktop shortcut + folder choice, fixed `upgradeUuid`, ProGuard off, modules
  `java.naming`, `jdk.crypto.ec`, `jdk.unsupported` (+ `jdk.crypto.mscapi` when built on Windows, for the Windows
  certificate store).
- Caisse (D1): `abilities` gained `show_orders`, `cash_register`, `use_cash_register`, `apply_discount`,
  `give_credit`, `assign_waiter`, `create_expense`, `manage_settings` (`RightsTest`).
- Tests (`shared/src/jvmTest`, 34): address, settings file, rights → buttons, idle lock, and `CaisseRepositoryTest`
  against a local server answering like the caisse — real HTTPS with a test CA (refused, then trusted after import;
  certificate for another address), HTTPS to an HTTP port, port closed, not a caisse (404 / HTML / empty), 502,
  account list kept, sign-in (token in memory only, Bearer sent after), wrong password / other refusal / throttle,
  401 ends the session, sign-out with the caisse down, Caisse switch.
- Checked end to end (2026-11-08) on a local caisse (Le Baroque card, `lebaroque:setup`) under Xvfb: first start →
  Réglages, test + save, sign-in list, wrong then right PIN (keypad), Serveur / Caissier / Gérant buttons, Gérant
  without Caisse → picker → Caisse, Réglages from Accueil, idle lock at 2 min with its warning.

## Roadmap — step 4 (review approved 2026-11-08; ~17.5 days)

| Step | Caisse | AltosPOS desktop | Effort | Status |
|---|---|---|---|---|
| D1 | Counter rights in `abilities` | Réglages (server, test, certificate, session, application), sign-in, Accueil by rights, Caisse picker, change user, idle lock, MSI "AltosPOS" | ~2 d | [x] built 2026-11-08, client to test |
| D2 | Print jobs per printer; branch key endpoint; X / Z thermal ticket fix; currency format fix; « Complet / Résumé » | Ticket printing (USB or Ethernet), test print, « Sur cet appareil / Imprimante de la caisse », "Directe" agent, tray + start with Windows | ~3 d | [ ] |
| D3 | — | POS (sections in Tri order, search, cart always visible, send, add round, bill, waiting stock / force) and Commandes | ~2.5 d | [ ] |
| D4 | Table and check operations; table details | Tables with every operation | ~3.5 d | [ ] |
| D5 | Discount endpoint | Encaisser: cash + change, Carte (TPE), À crédit, split, tip, discount, receipt | ~2.5 d | [ ] |
| D6 | Cash register and expense endpoints | Caisse: open with float, cash in / out / safe drop, X, Z with count, stock panel + "Envoyer la demande au stock", dépenses | ~3.5 d | [ ] |
| D7 | CLAUDE.md | CLAUDE.md, release MSI, branches | ~0.5 d | [ ] |

Later (own step): offline mode on `/api/sync/*` (`kind: "desktop"`), copying the handheld's offline code (Room KMP,
sync, PIN checks; Windows DPAPI instead of the Android Keystore).

## Open questions

- None.

## Progress log

- 2026-11-08 — Review in the chat; client's answers: password as PIN; idle lock 5 min (not during a cart or payment);
  printing like the Sunmi with USB **or** Ethernet; tray + start with Windows; branch key fetched by an admin; Tables
  with every operation; X / Z « Complet / Résumé » + currency fix; server = IP + port; name AltosPOS; Réglages with
  printer test and connection test. Plan updated to ~17.5 days, approved ("start D1").
- 2026-11-08 — D1 built (see "How D1 is built"): 34 desktop tests, caisse `RightsTest` 11 passed; run end to end
  against a local caisse with screenshots. Next: the client builds (`gradlew run`, `packageMsi`) and tests D1 on the
  counter PC; then D2.
