# LeBarock — AltosPOS desktop (counter app)

Windows app for the **counter PCs** of **Le Baroque**: the web caisse's point-of-sale work (POS, tables, orders,
payment, cash register). The web stays for management and as a backup. Kotlin + Compose Multiplatform (Desktop, JVM).
Name on Windows: **AltosPOS**. Detail of each step is in git history (cleaned up 2026-11-11, D7).

## Sibling repos

| Repo | Role | Contract with this app |
|------|------|------------------------|
| `LeBarock-RestaubarCaisse` | Server, only source of truth | `/api/mobile/*` (Sanctum, `device_name` `altospos-desktop`), `/api/sync/*` (`kind: desktop`). |
| `AltosPOS-Mobile` (`LeBarokMobileapp`) | Sunmi handheld | The model: API layer, ticket layouts, offline code **copied** from it. |
| Stock back / front | Stock | None. |

## Stack and commands

- Kotlin 2.4.21, Compose Multiplatform 1.12.2, Gradle 9.5.1, JDK 21; Retrofit 3, OkHttp 5, kotlinx.serialization,
  JNA (DPAPI, registry), sqlite-jdbc (offline store; installed app needs `java.sql`).
- Modules: `shared` (code `src/jvmMain`, tests `src/jvmTest`), `desktopApp` (window, tray, installer).
- Run `gradlew :desktopApp:run`; tests `gradlew :shared:jvmTest` (**136 tests**, 2026-11-12).
- Installer: `gradlew :desktopApp:packageMsi` **on Windows**; raise `altospos.version` each release; `upgradeUuid`
  never changes.
- Data folder `%APPDATA%\AltosPOS` (`settings.json`, `offline.db`, `logs\`, `certs\`, `cache\images`);
  `-Daltospos.home=` overrides it.
- **Cloud sessions:** `cdn.azul.com` blocked — set `gradle/gradle-daemon-jvm.properties` aside for the build; Maven
  Central may answer 429. Screenshots: `createDistributable` under `Xvfb` + `xdotool`, caisse on `php artisan serve`.
  **Never** write `/*` inside a KDoc.

## Working rules (from the client)

- **Big task → review first**, then small steps the client tests. Ask before building; confirm intent even when obvious.
- Each step: questions, build, run end to end, tests, CLAUDE.md, commit and push (session branch).
- **No review / notes `.md` files in the repos.** `CLAUDE.md` is only for tracking and planning.

## Decisions in force

- **Server**: protocol + IP + port in Réglages, « Tester la connexion »; HTTPS trust: Java + Windows store + imported certs.
- **Sign-in**: the restaurant's accounts, password as PIN; **the account decides the Caisse** (Admin / Gérant may
  switch); token in memory only; idle lock 5 min (never with a cart, payment or Caisse window open); one app per session.
- **Accueil by rights**: POS, Tables, Commandes, Encaisser, Caisse, Réglages.
- **Style**: `ui/theme/Dimens` (spacing 8, buttons / fields 48 dp, main action 56 dp, text ≥ 14 sp, green choices with a
  check, scrollbar on every scrolling part, label above each field). Reference 1366 × 768 at 100 %; 125 % checked.
- **Printing**: « Sur cet appareil » (USB via spooler or Ethernet 9100) or « Imprimante de la caisse »; « Directe »
  agent; tray keeps the agent running; starts with Windows.
- **POS**: the web cart; table picker + « À emporter »; photos; cancel keeps « Annulée » with its reason.
- **Tables**: the web floor, refresh 15 s; every web operation except QR approvals.
- **Encaisser**: the web's payment window (cash + change, TPE, credit, split, tip); receipt prints when settled.
- **« Code remise »**: the « Remise » window (cart and check) asks the code each time a discount is added or changed;
  checked by the caisse online, against the copy offline; removal needs none.
- **Caisse**: session, movements (web names), X, Z by value + « Reste (pièces) », past X / Z, expenses; every money
  action asks « Confirmer »; the Z's approval stays on the web.
- **Check view**: sub-total, discount, charges, taxes, tip, total, payments (the caisse sends `taxes`).
- **Live updates (client, 2026-11-11)**: `data/realtime/LiveSocket` (Pusher protocol 7 over OkHttp, same trust as the
  API, reconnects 1 → 30 s) — `LiveUpdates` (signed-in account, floor channel: Tables and Commandes / « À encaisser »
  read again at once) and the « Directe » agent (printer channel, branch key: prints at once). Live → check every 30 s;
  live down → as before (floor 15 s, agent 2 s). Logged « Direct (…) : en direct / connexion perdue ».
- **Offline** (`/api/sync/*`, the PC is « Caisse PC D1 »):
  - registers with the branch's « Clé de synchronisation »; syncs with its key alone, signed in or not; copy of every
    Caisse within 30 s;
  - « Connexion hors ligne » = a Caisse's « Hors ligne » PIN (decides the Caisse); 5 wrong PINs lock 30 s;
  - orders, rounds, bill, reprint; kitchen tickets and bill print on this PC; numbers « D1-007 »;
  - payments (cash, card, split) and check edits (cancel, line off, waiter, charge) behind the manager's payment PIN,
    once per check for 5 min; discount behind the « Code remise »; table operations and tip without PIN;
  - everything recorded as the « Hors ligne » account; refusals listed in « À vérifier »;
  - stay online: credit, customer, « Libérer la table », Caisse page, expenses (a click says « Hors ligne : … »).
- **Not included**: cash drawer, customer display, barcode, kitchen screen, waiter notifications, QR approvals.

## How the app is organised

- `data/`: `CaisseRepository`, `OrdersRepository` (online, or `OfflineOrders` while `OfflineSync.mustQueue()`),
  `CashRegisterRepository`, `PrintOutcomes`, `ApiFailure`, `settings/SettingsStore`, `remote/*`, `session/Rights`.
- `data/offline/`: `OfflineStore` (SQLite, store v4), `ConnectionMonitor`, `OfflineSync`, `OfflineCatalog`,
  `OfflineOrders` (local edits in `edits_json`), `OfflinePins`.
- `pos/`: `Cart` + `CartMath`, `Payment` (`PaymentForm`, `TipForm`), `CashCount`.
- `print/`: `ReceiptRenderer`, `EscPosEncoder`, `TicketTransport`, `TicketPrinter`, `PrintAgent`.
- `ui/`: `login`, `home`, `settings`, `pos`, `tables`, `orders`, `check` (`CheckActions`), `payment`, `cash`, `offline`.

## Done (summary)

- D1 Réglages, sign-in, Accueil, idle lock · D2 style, printing, agent, tray · D3 POS and Commandes · D4 tables and the
  check's operations · D5 Encaisser · D6 Caisse and expenses.
- Offline O1 foundation · O2 orders · O3 « Hors ligne » sign-in and payment · O4a key-only sync · O4b check edits ·
  O4c table operations · O4d tip.
- « Code remise » window (online and offline).
- Live updates (2026-11-12): floor and « À encaisser » follow the caisse within ~1 s; reconnects after a Reverb restart.
- Service test 2026-11-11 (3 waiters + this PC as Caissier): discount with the code, cash with change, card, split;
  drawer and receipts right; tax lines added to the check view.
- Each run end to end on the packaged app at 1366 × 768 (and 1093 × 614). Not checked here: a real Windows screen,
  USB printing through a Windows driver, tray, registry.

## To do

- [ ] Client tests on the counter PC: D4–D6, O1–O4d, « Code remise ».
- [ ] Deployment branch when the client says.
- [ ] **MSI release — last, built by the client on Windows** (client, 2026-11-11): pull `claude/epic-euler-r5vcoj`,
      `.\gradlew.bat :desktopApp:packageMsi` (Gradle fetches the Azul JDK 21, the plugin fetches WiX) →
      `desktopApp/build/compose/binaries/main/msi/AltosPOS-1.0.0.msi`. Version from `altospos.version` (1.0.0). Check:
      install, sign in, print, offline; a higher version installs over it. Not built in cloud sessions (Windows only).

## Open questions

- None.
