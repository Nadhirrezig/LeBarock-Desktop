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
  kotlinx.serialization, material-icons-extended, JNA (Windows DPAPI and registry), sqlite-jdbc (the offline store; the
  installed app needs the `java.sql` module, set in `desktopApp/build.gradle.kts`).
- Modules: `shared` (all app code `src/jvmMain`, tests `src/jvmTest`), `desktopApp` (window, tray, installer settings).
- Run `gradlew :desktopApp:run`; tests `gradlew :shared:jvmTest` (**132 tests**, 2026-11-11).
- Installer: `gradlew :desktopApp:packageMsi` **on Windows** → `desktopApp/build/compose/binaries/main/msi/AltosPOS-x.y.z.msi`.
  Raise `altospos.version` for each release (MAJOR.MINOR.BUILD); `upgradeUuid` never changes.
- Data folder `%APPDATA%\AltosPOS` (`settings.json`, `offline.db`, `logs\`, `certs\`, `cache\images`, lock file);
  `-Daltospos.home=` overrides it.
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
- **« Code remise » (client, 2026-11-11):** the « Remise » window (cart and check) asks the restaurant's « Code remise »
  each time a discount is added or changed (right « Appliquer une remise » + code; set by the Admin on the caisse,
  Paramètres → Remises). Online the caisse checks it (`discount_code`, `/discount-code/check` for a new cart); offline
  it is checked against the copy's sealed check (`offline_access.discount_verifier_sealed`, store v4) and the discount
  is sent with `authorized_by: discount_code` — **no payment PIN for a discount any more**. Taking it off: no code.
- **Caisse (D6):** the web's Caisse page + past X / Z on the account's Caisse; Z counted by value (whole numbers, the
  caisse's list) **plus « Reste (pièces) », an amount typed for the coins** (sent as `rest`; client 2026-11-10, no coin
  values); movements under the web's names (« Entrée de caisse / Sortie de caisse / Prélèvement coffre », action buttons
  two per row so they fit at 100 % and 125 %); expenses = web
  form + the day's list; every money action asks « Confirmer »; the Z's approval stays on the web.
- **Offline mode (client, 2026-11-10):** the handheld's offline mode on this PC, on the caisse's `/api/sync/*`
  (`kind: "desktop"`, the caisse names the PC « D1 » and lists it « Caisse PC D1 »). **Scope = the handheld's actions
  only** (new order, round, bill, reprint; step 3: cash / card payment, split); discount, credit, waiter, charges, table
  operations, cancel, taking a line off, the cash register and expenses wait for the caisse (may come later as feature
  updates); **a click on those says « Hors ligne : … n'est pas possible tant que la caisse ne répond pas. »** (nothing
  greyed out). **Everything offline is recorded as the Caisse's « Hors ligne » account** (needs its offline PIN set at the
  caisse; as on the handheld). **Kitchen tickets (always, one per station) and the bill print offline on this PC's
  printer** (Réglages), even when Réglages says « Imprimante de la caisse ». **Sign-in offline = the Caisse's « Hors
  ligne » account and its PIN** (« Connexion hors ligne » on the sign-in screen while the caisse does not answer; 5 wrong
  PINs lock 30 s). **Offline payments: cash with change, card, the bill divided (equal / amounts / by article, one
  `payment.split`), behind the manager's payment PIN typed once per check, valid 5 minutes** (client, 2026-11-10);
  whoever is signed in (the caisse takes « Hors ligne » payments only with it); no credit, no tip; the receipt prints on
  this PC. **The caisse back during a « Hors ligne » session, everything sent → « La caisse répond à nouveau » window →
  sign-in** (client, 2026-11-10, as the handheld). **O4a (client, 2026-11-11): everything syncs with the PC's
  key alone, signed in or not** (locked, in the tray): the PC registers once with the branch's « Clé de
  synchronisation » typed in Réglages → Mode hors ligne (or, as before, at a sign-in); the copy is **the branch's: every
  Caisse** with its menu and « Hors ligne » PIN check, fresh within 30 s; **the PIN typed at « Connexion hors ligne »
  decides the Caisse** (menu, take-away checks, « Hors ligne » account); a signed-in account uses its own Caisse. The
  key field keeps what is typed (adding dashes while typing moved the cursor). **O4b (client, 2026-11-11): the check's
  changes offline — discount (cart and check), cancel with its reason, a sent line with no kitchen station taken off
  with its reason (asked offline only), waiter, extra charge — behind the same payment PIN, once per check for 5 min
  (`ui/offline/ManagerPin`, the « Code du gérant » window over the check's windows and the POS); shown at once
  (`local_orders.edits_json`, store v3) and sent to the caisse. A dish sent to a kitchen is not taken off and a
  discount under what was paid is refused, as online; nothing prints for the kitchen; a cancelled check's money goes
  back when the caisse applies it.** **O4c (client, 2026-11-11): « Changer de table », « Fusionner », « Séparer »
  offline, as online (no PIN)** — shown at once (edits `move` / `absorb` / `merged` / `split_out`; the floor frees the
  table left and takes the one reached), one action each for the caisse; a split gets its own number « D1-008 »; a
  table taken meanwhile is refused at the caisse (« À vérifier »). **O4d (client, 2026-11-11): « Pourboire » offline in
  the payment window, as online, no PIN, only when the copy says the restaurant takes tips (`tip_pos`)** — shown at once
  (edit `tip`), sent as `order.tip`; a check paid at the caisse meanwhile refuses it. Still waiting for the caisse: credit, customer,
  « Libérer la table », Caisse page, expenses. The caisse's `order.remove_line` is used since O4b (a sent line with no kitchen station, with its reason).
- **Not included:** cash drawer, customer display, barcode, kitchen screen, waiter notifications, QR approvals.

## How the app is organised

- `data/`: `CaisseRepository` (test, sign-in, accounts, Caisse switch), `OrdersRepository` (menu, tables, orders, the
  check's operations, payments, tip, discount), `CashRegisterRepository` (session, movements, X / Z, reports, expenses),
  `PrintOutcomes` (where a ticket went), `ApiFailure` (French messages + hints), `settings/SettingsStore`, `remote/*`
  (Retrofit API, DTOs), `session/` (`Rights.of(abilities)` with fallbacks for an older caisse).
- `data/offline/`: `OfflineStore` (`offline.db`, SQLite: device, snapshot parts, open checks, table states, outbox,
  review items, order refs, local orders, offline access — the handheld's Room schema and call names; WAL, a damaged file
  is set aside as `.bad`), `ConnectionMonitor` (offline after 10 s of failed calls; « Tester la connexion » is not
  watched), `OfflineSync` (heartbeat 30 s, snapshot static + checks, outbox sent in order, « À vérifier » items, device
  key and PIN checks sealed with `SecretBox`), `OfflineCatalog` (the copy read for the screens, the bill, the kitchen),
  `OfflineOrders` (reads, new order « D1-007 », round, bill, reprint; printing on `TicketPrinter`). `OrdersRepository`
  answers from `OfflineOrders` while `OfflineSync.mustQueue()` (offline, or the outbox not empty), refuses the rest
  (`waits(…)`), and opens an order taken offline as its real check once synced (`real(id)`). The last online POS context
  is kept (`pos_context` part: order types). Photos come from the disk cache offline. `ui/offline/OfflineBanner`,
  `ReviewScreen` (+ Accueil card, Réglages button), `ui/settings/OfflineSection` + `OfflineModel`; the kitchen layout is
  `ReceiptRenderer.renderKitchen`. Step 3: `OfflinePins` (PBKDF2 against the caisse's verifiers, sealed in
  `offline_access`), `Session.offline` (no token; `CaisseRepository.api()` refuses every call of such a session, the sync
  uses the device key alone), `ui/offline/PinWindow` (sign-in and payment PIN), `PaymentFlow.paymentPin`.
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

- **O1 offline foundation** (2026-11-10): the store, the connection watch, the PC registered at the caisse, the copy of
  the menu / tables / open checks while signed in, the sending of what waits (nothing fills the outbox yet), the
  « Hors ligne » banner above every screen (sign-in included), Réglages → « Mode hors ligne » (code, copy, waiting,
  last contact, state, « Synchroniser maintenant », « Effacer les données hors ligne »). No caisse change. After O1 the
  app says « Hors ligne » but still cannot take orders. Tests: `OfflineFoundationTest`.
- **O2 orders offline** (2026-11-10): POS (sections, photos from the cache, number « D1-001 »), the floor and Commandes
  from the copy with the orders taken offline on top; send, round, bill, reprint; kitchen tickets (« HORS LIGNE ») and
  the bill on this PC's printer; the rest refused in French; « À vérifier » screen. Run end to end: offline order on T2,
  a round, the bill printed; the caisse back → check #1 (D1-001, billed, same total) recorded as « Hors ligne ». Found:
  the compact cart header's window cut the covers stepper with « #D1-001 » (widened to 540). Tests: `OfflineOrdersTest`.
- **O3 « Hors ligne » sign-in and payment** (2026-11-10): « Connexion hors ligne » with the Caisse's PIN; the payment PIN
  once per check for 5 min; cash / card / split; receipt on this PC; « La caisse répond à nouveau » → sign-in. Run end to
  end: signed out, caisse stopped, PIN sign-in, order on T3, « Encaisser Ticket » 20 DT cash + payment PIN, change 6.770,
  receipt printed; the caisse back → check #2 (D1-002) paid, cash 13.230 with 6.770 change in Counter 1's open session,
  6 actions applied as « Hors ligne ». Found: Accueil's tiles had no scrollbar (cut at 125 % under the banner): added.
  Tests: `OfflinePaymentTest`.

Each was run end to end on the packaged app at 1366 × 768 (and 1093 × 614). Not checked here: a real Windows screen,
USB printing through a Windows driver, tray and registry — the client's test.

## Roadmap

- [x] D1–D6 built (2026-11-08 → 2026-11-10).
- [ ] Client tests D4, D5, D6 on the counter PC.
- [x] **Offline O1** foundation (2026-11-10) — client to test: PC code in Réglages, copy downloaded, banner when the
      caisse is stopped.
- [x] **Offline O2** orders offline (2026-11-10) — client to test: sign in, stop the caisse, take an order on a table,
      a round, « Imprimer Ticket »; start the caisse: the check appears with its D1 number.
- [x] **Offline O3** « Hors ligne » sign-in and payment (2026-11-10) — client to test on the counter PC: stop the caisse,
      « Connexion hors ligne », an order paid in cash with the payment PIN, the caisse back (session open on the Caisse).
- [x] **Offline O4a** key-only sync (2026-11-11) — client to test: Réglages → Mode hors ligne, type the key from the
      caisse (Paramètres → Mode hors ligne), nobody signed in: « Caisse PC D1 » and the copy of every Caisse.
- [x] **Offline O4b** check edits offline (2026-11-11) — client to test: stop the caisse, « Connexion hors ligne »,
      an order with a cart discount (« Code du gérant »), then on it: waiter, « Retirer des frais », a drink taken off
      with its reason, and cancel another check; start the caisse: same totals, check « Annulée » with its reason.
- [x] **Offline O4c** table operations offline (2026-11-11) — client to test: stop the caisse, move a check to a free
      table, split one line of another, merge its table into the first; start the caisse: same checks and totals.
- [x] **Offline O4d** tip offline (2026-11-11) — client to test: restaurant set to take tips, stop the caisse, a check
      → Encaisser → « Pourboire » 2 DT with a note (no PIN), pay; start the caisse: same total, tip and note. Credit,
      customer, Caisse page and expenses stay online (client, 2026-11-11).
- [ ] **D7 wrap-up** after O4 (CLAUDE.md files, deployment branches), then **MSI release — last** (client, 2026-11-10).

## Open questions

- None.

## History (short)

- 2026-11-08 — review approved (~17.5 days); D1, D2, D3, D4 built.
- 2026-11-10 — D4 reviewed and fixed; D5 and D6 built. Order changed by the client: offline mode, then the MSI last.
  Z count: « Reste (pièces) » amount added; movement names follow the web. Offline mode: scope and steps agreed, O1
  (foundation), O2 (orders offline) and O3 (« Hors ligne » sign-in, payment PIN) built and run against the caisse.
- 2026-11-11 — O4 agreed before D7 (check edits, table operations, tip offline; desktop and handheld). O4a built: key
  only sync, branch copy, PIN picks the Caisse. Run end to end: fresh PC registered signed out, PIN and price changes
  reached it within 30 s, old PIN refused, Counter 2's PIN → Counter 2's menu (Mojito only), order D1-001 on T2
  recorded as « Hors ligne — Counter 2 ». Found: the key field moved the cursor when it added dashes (fixed).
  O4b built: the check's changes offline behind the manager's PIN; run end to end (cart discount 10 % + PIN, drink
  taken off « Renversé », waiter Sami, service fee removed, check #1 cancelled « Client parti » + PIN; the caisse back →
  7 actions applied, D1-002 at 5.940 as on the PC; the store upgraded v2 → v3 on the PC).
  O4c built: move, merge, split offline (no PIN); run end to end (order on T3, #2 moved T1 → T4, a Citronnade split to
  D1-004, T3 merged into T4; the caisse back → 4 actions applied, #2 on T4 at 19.800 as on the PC). Found: the split's
  message had two brackets (fixed).
  « Code remise » built: the discount window asks the code (online and offline, store v4); 131 tests pass.
  O4d built: the tip offline (no PIN, follows the setting); 132 tests pass. O4 done; next D7.
