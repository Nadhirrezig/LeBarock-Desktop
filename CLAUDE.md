# LeBarock — AltosPOS desktop (counter app)

Windows app for the **counter PCs** of **Le Baroque** (restaurant / bar): the point-of-sale work of the web caisse
(`LeBarock-RestaubarCaisse`) — POS, tables, orders, payment, cash register. The web stays on the counter for management
and as a backup. Kotlin + Compose Multiplatform (Desktop, JVM). Name on Windows: **AltosPOS**. Older detail (how each
step was built, run logs) is in git history (cleaned up 2026-11-10).

## Sibling repos

| Repo | Role | Contract with this app |
|------|------|------------------------|
| `LeBarock-RestaubarCaisse` | The server, only source of truth | `/api/mobile/*` (Sanctum token, `device_name` `altospos-desktop`); the endpoints step 4 added are listed in its `CLAUDE.md` ("Step 4 — desktop app, caisse side"). |
| `AltosPOS-Mobile` (branch `LeBarokMobileapp`) | Sunmi handheld app | The model: API layer, repository logic, ticket layouts and the offline code are **copied** from it (no shared module). |
| Stock back / front | Stock | None. |

## Stack and commands

- Kotlin 2.4.21, Compose Multiplatform 1.12.2 (material3 1.12.0-alpha03), Gradle 9.5.1, JDK 21. Retrofit 3, OkHttp 5,
  kotlinx.serialization, material-icons-extended, JNA (Windows DPAPI and registry).
- Modules: `shared` (all app code `src/jvmMain`, tests `src/jvmTest`), `desktopApp` (window, tray, installer settings).
- Run `gradlew :desktopApp:run`; tests `gradlew :shared:jvmTest` (**91 tests**, 2026-11-10).
- Installer: `gradlew :desktopApp:packageMsi` **on Windows** → `desktopApp/build/compose/binaries/main/msi/AltosPOS-x.y.z.msi`.
  Raise `altospos.version` for each release (MAJOR.MINOR.BUILD); `upgradeUuid` never changes.
- Data folder `%APPDATA%\AltosPOS` (`settings.json`, `logs\`, `certs\`, `cache\images`, lock file); `-Daltospos.home=` overrides it.
- **Cloud sessions:** `cdn.azul.com` is blocked — build with the local JDK 21 by setting `gradle/gradle-daemon-jvm.properties`
  aside for the build; Maven Central may answer 429 (retry). Screenshots: packaged app (`createDistributable`) under
  `Xvfb` + `xdotool`, caisse with `php artisan serve` on a sqlite file. **Never** write `/*` inside a KDoc.

## Working rules (from the client)

- **Big task → review first** in the chat, then small steps the client tests. Ask before building; confirm intent even
  when obvious. Each step: questions first, build, run it end to end, tests, CLAUDE.md, commit and push.
- **No review / notes `.md` files in the repos.** `CLAUDE.md` is only for tracking and planning.

## Decisions in force

- **Server** = protocol + IP + port in Réglages, « Tester la connexion ». HTTPS trust: Java + Windows store + certificates
  imported in Réglages.
- **Sign-in:** the restaurant's accounts, password as PIN. **The account decides the Caisse** (Admin / Gérant may switch).
  Token in memory only. Idle lock 5 min (Réglages), warning 30 s before, **never while a cart, a payment window or a
  Caisse window is open**. One AltosPOS per Windows session.
- **Accueil by rights:** POS, Tables, Commandes, Encaisser, Caisse, Réglages.
- **Style (client):** one set of sizes (`ui/theme/Dimens`): spacing in steps of 8, buttons and fields 48 dp, main action
  56 dp, text ≥ 14 sp, choices green with a check, a scrollbar on every scrolling part, label above each field.
  Réglages is one page. **The counter PC stays at 100 % (1366 × 768 is the reference); 125 % (1093 × 614) is checked as
  the responsive case.**
- **Printing:** « Sur cet appareil » (the app prints the caisse's ticket JSON on the counter printer, USB via the Windows
  spooler or Ethernet port 9100) or « Imprimante de la caisse ». « Directe » agent for USB print jobs (branch key fetched
  once by an admin). Tray: closing the window keeps the agent running and ends the session; starts with Windows.
- **POS (D3):** web cart's UI and logic, table picker + « À emporter », item photos like the web. **Cancel keeps the order
  « Annulée » with its reason.**
- **Tables (D4):** the web's floor; an occupied table opens the POS on its check; a free table records nothing until
  « Envoyer »; refresh every 15 s. Every web operation except QR approvals.
- **Encaisser (D5):** the web's payment window; discount in the POS cart like the web; receipt printed by itself once
  settled; « Encaisser » from the POS, Commandes, Accueil (« À encaisser », every day) and the floor.
- **Caisse (D6):** the web's Caisse page + past X / Z on the account's Caisse; Z counted by value (whole numbers, the
  caisse's list) **plus « Reste (pièces) », an amount typed for the coins** (sent as `rest`; client 2026-11-10, no coin
  values); movements under the web's names (« Entrée de caisse / Sortie de caisse / Prélèvement coffre », action buttons
  two per row so they fit at 100 % and 125 %); expenses = web
  form + the day's list; every money action asks « Confirmer »; the Z's approval stays on the web.
- **Not included:** cash drawer, customer display, barcode, kitchen screen, waiter notifications, QR approvals.

## How the app is organised

- `data/`: `CaisseRepository` (test, sign-in, accounts, Caisse switch), `OrdersRepository` (menu, tables, orders, the
  check's operations, payments, tip, discount), `CashRegisterRepository` (session, movements, X / Z, reports, expenses),
  `PrintOutcomes` (where a ticket went), `ApiFailure` (French messages + hints), `settings/SettingsStore`, `remote/*`
  (Retrofit API, DTOs), `session/` (`Rights.of(abilities)` with fallbacks for an older caisse).
- `pos/`: plain, tested logic — `Cart` + `CartMath` (the web's `calculateTotal`, discount), `Payment` (`PaymentForm`:
  methods, keypad, credit, equal / custom / by-article splits; `TipForm`), `CashCount` (Z count by value, amounts, dates).
- `print/`: `ReceiptRenderer` (48 / 32 columns; a plain number reads « Ticket #42 », an X / Z keeps its title),
  `EscPosEncoder`, `TicketTransport`, `TicketPrinter`, `PrintAgent`.
- `ui/`: `ScreenModel` + `Navigator`; screens `login`, `home`, `settings`, `pos`, `tables`, `orders`, `check`
  (`CheckActions`, shared by the POS and Commandes), `payment` (`PaymentFlow` + `PaymentWindow`), `cash`
  (`CashRegisterModel` + `CashRegisterScreen`, ticket preview as printed).

## Steps done (step 4)

- **D1** Réglages, sign-in, Accueil by rights, Caisse picker, idle lock, MSI settings.
- **D2** style system, ticket printing (USB / Ethernet, test print), « Directe » agent, tray, start with Windows.
- **D3** POS (sections, search, web cart, table picker, à emporter, send, round, bill, stock waiting / force) and Commandes.
- **D4** Tables like the web floor, the check's operations (move, merge, split, waiter, customer, charges, payment fix,
  Vérifié, cancel, line taken off); reviewed and fixed 2026-11-10.
- **D5** Encaisser: cash + change, TPE, credit, split (equal / amounts / by article), tip, receipt; discount in the cart;
  « À encaisser » list. Found: tickets printed « Remise (1%) » for 10 % (fixed caisse-side).
- **D6** Caisse: session with float, cash in / out / safe drop, X, Z counted by value with difference, waiting for
  approval, stock request after the Z, past X / Z, expenses. Found: X / Z printed « Ticket #RAPPORT X » (fixed); the
  caisse's count values could not be saved (fixed caisse-side).

Each was run end to end on the packaged app at 1366 × 768 (and 1093 × 614). Not checked here: a real Windows screen,
USB printing through a Windows driver, tray and registry — the client's test.

## Roadmap

- [x] D1–D6 built (2026-11-08 → 2026-11-10).
- [ ] Client tests D4, D5, D6 on the counter PC.
- [ ] **D7 wrap-up**: CLAUDE.md files, deployment branches.
- [ ] **Offline mode** on `/api/sync/*` (`kind: "desktop"`), copying the handheld's offline code (local store, outbox,
      sync, offline PIN sign-in, payment PIN; Windows DPAPI instead of the Android Keystore).
- [ ] **MSI release — last**, after the offline mode (client, 2026-11-10).

## Open questions

- None.

## History (short)

- 2026-11-08 — review approved (~17.5 days); D1, D2, D3, D4 built.
- 2026-11-10 — D4 reviewed and fixed; D5 and D6 built. Order changed by the client: offline mode, then the MSI last.
  Z count: « Reste (pièces) » amount added; movement names follow the web.
