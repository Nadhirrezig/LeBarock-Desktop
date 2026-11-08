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
  kotlinx.serialization 1.12.0, material-icons-extended 1.7.3, JNA 5.19.1 (Windows DPAPI and registry).
- Modules: `shared` (all app code, `src/jvmMain`; tests in `src/jvmTest`), `desktopApp` (window, installer settings).
- Run: `gradlew :desktopApp:run` (Windows: `gradlew.bat`). Tests: `gradlew :shared:jvmTest`.
- Installer: `gradlew :desktopApp:packageMsi` **on Windows** (jpackage + WiX, downloaded by the Compose plugin) →
  `desktopApp/build/compose/binaries/main/msi/AltosPOS-x.y.z.msi`. Raise `altospos.version` (gradle.properties or
  `-Paltospos.version=`) for each release: MSI versions are MAJOR.MINOR.BUILD; `upgradeUuid` never changes, so a new
  MSI replaces the installed one.
- Data folder: `%APPDATA%\AltosPOS` (`settings.json`, `logs\altospos-N.log`, `certs\`, `altospos.lock`, `.secret.key`
  off Windows only);
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
- **Style (client, 2026-11-08, after D1: "buttons, texts, margins and paddings must be right"; Réglages had display
  issues):** one set of sizes for every screen (`ui/theme/Dimens`): spacing in steps of 8, every button and field
  48 dp, a window's main action 56 dp, one corner radius (10), text never below 14 sp, choices shown in green with a
  check, choice boxes as wide as their longest label (nothing cut, nothing moves), a scrollbar on every scrolling
  page, label above each field, every line of a block on one left edge. Réglages stays **one page** (client chose it
  over a side menu); only the server has « Enregistrer » (greyed until something changes), every other section saves
  on each change and says « Enregistré automatiquement ». Checked at 1366 × 768 and at the sizes Windows 125 % / 150 %
  leave (1093 × 614, 1280 × 720).
- **Closing the window** (with a tray): AltosPOS stays in the tray (the "Directe" printing goes on), the counter's
  session ends; « Quitter AltosPOS » in the tray menu ends it. Without a tray (not Windows), closing quits.
- **D3 (client, 2026-11-08):** the POS opened from Accueil has a **table picker and « À emporter »** (shown when the
  restaurant takes take-away orders, as on the web); the **cart copies the web POS's UI and logic** (`pos/kot_items`,
  `pos/order_items`, `Pos::calculateTotal`); **Commandes = today's orders with the status chips** (as the handheld);
  **« Annuler la commande » works like the web everywhere**: the order stays « Annulée » with its reason (list + text),
  the handheld's cancel too (it used to delete the order); the caisse's thermal bill date fixed in the same step.
- **Item photos (client, 2026-11-08):** "do like the web": a photo on each item card, the web's food icon for an item
  without one, square cards as on the web.

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

## How D2 is built (2026-11-08)

- **Style system:** `ui/theme/Dimens` (sizes), typography floor 14 sp, scrollbar style in `AltosTheme`;
  `ui/common/Controls.kt`: `AppButton` (primary / secondary / text / danger, 48 or 56 dp, hand cursor), `TextInput`
  (48 dp, green focus, red error), `FormField` (label above, help or error under), `ChoiceRow` (equal boxes sized to the
  widest label, check space always kept), `SelectField`, `AppTopBar` (72 dp), `ScrollPage` (centred 960 dp + scrollbar),
  `SectionCard`, `SubHeading`, `InfoRow` (one text size, paths break after a separator), `AppDialog`, `AutoSavedNote`.
  Réglages, Connexion (PIN window compact under 760 dp of height: 48 dp keys, buttons keep their height), Accueil
  (tiles one height) and their windows use only these.
- **Printing** (`print/`): `ReceiptPayload` (the caisse's ticket JSON, copied from the handheld + `remainingDue`),
  `ReceiptRenderer` (ported; section titles of the X / Z, test ticket with an edge-to-edge line), `EscPosEncoder` (ESC @,
  code page PC858 = 19 / Windows-1252 = 16 / sans accents, bold, double size, feed, partial cut, QR; narrow spaces,
  dashes and French quotes written plainly), `TicketTransport` (`WindowsPrinterTransport`: raw bytes to the Windows
  spooler by printer name, `DocFlavor.BYTE_ARRAY.AUTOSENSE`; `NetworkTransport`: port 9100, 4 s connect), `TicketPrinter`
  (one ticket at a time, French messages). Settings `PrinterConfig` (target device / caisse, USB / Ethernet, Windows
  printer, IP / port, 58 / 80 mm, charset). MSI modules + `jdk.charsets` (PC858) + `jdk.localedata` (French dates: the
  installed app wrote "Sun 8 Nov" without it).
- **Impression « Directe »** (`print/PrintAgent`): `AgentClient` (OkHttp without the session token, header
  X-AltosPos-KEY): `pull?printer_id=` every 2 s → job bytes (`payload.base64`, else `text` read as Latin-1) →
  `TicketPrinter.printRaw` (cut added when asked and missing) → `PATCH /api/print-jobs/{id}` done / failed with the
  printer's name; 401 → "clé refusée", stops; caisse down → retry 10 s; printer error → job failed, shown. Status for
  Réglages and the tray. Set-up (`PrintingModel`): an admin's sign-in made only for this (`device_name`
  `altospos-desktop-agent`, token ended right after) → `GET /api/mobile/desktop/print-agent` → key sealed with
  `SecretBox` (DPAPI on Windows; AES-GCM + key file elsewhere) + printers; printer list refreshed later with the key
  (`/api/printer-details`). A printer not in "Directe" on the caisse is flagged.
- **Tray / startup** (`desktopApp/main.kt`): Compose `Tray` (Ouvrir, état de l'impression « Directe », Quitter);
  closing hides the window and ends the session (`AppContainer.lockRequests`), a one-time notification says AltosPOS
  stays open; `--tray` starts hidden; `WindowsStartup` (HKCU\…\Run "AltosPOS" = `"<AltosPOS.exe>" --tray`, JNA
  Advapi32), on at the installed app's first start (`startWithWindows` null), Réglages → Application to change it.
- Tests: 53 (+ `PrintingTest` 12: code pages, typographic characters, styles, QR, no line wider than 58 / 80 mm,
  bill with "Reste dû", X / Z layout, test ticket, Ethernet to a local socket, refused port, nothing sent before a printer
  is chosen, cut once, one ticket at a time; `PrintAgentTest` 7: exact bytes printed and reported done, older job without
  base64, printer failure reported failed, refused key stops, caisse down retried, off / no printer does nothing, key
  sealed). `CaisseRepositoryTest`'s "HTTPS to an HTTP port" now uses a server that answers at once (it was flaky).
- Checked end to end (2026-11-08) with the local caisse under Xvfb, packaged app: Réglages at 1366 × 768 and 1093 × 614;
  "Imprimer un test" on a stand-in Ethernet printer (accents right in PC858, 48 columns, cut); « Directe » set-up refused
  for a Caissier, done by the admin (temporary tokens deleted); an X queued by the caisse and a handheld bill sent to
  « Imprimante de la caisse » printed by the agent within seconds, both "done" on the caisse. Not checked here: USB
  printing through a real Windows driver, the tray and the registry (no Windows): the client's test.

## How D3 is built (2026-11-08)

- **Data:** `data/OrdersRepository` (menu, tables, POS context, orders, send / round / bill / reprint / send again /
  remove a waiting line / cancel with reason / « Servi »; a ticket asked prints here from the caisse's JSON with
  « Sur cet appareil », or the caisse's `print_result` with « Imprimante de la caisse »), `data/Money` (the caisse's
  `currency_format_exact`: at least 2 decimals, its separators and symbol position; the currency is read fresh from
  `pos-context` and kept), `pos/Cart` (`Cart`, `CartLine`, `CartMath` = the web's arithmetic: each line to 2 decimals,
  taxes and percent charges on the total, charges only with lines; `sectionsOf` in the Tri order; `searchItems`
  ignoring case and accents; `ModifierRules`; `tableLabel`: a digits-only code reads « T12 »).
- **POS** (`ui/pos/PosModel`, `PosScreen`): menu on the left (section tiles « n articles », « ‹ Sections », search over
  the whole menu — Enter adds a single match —, item cards with the price or « Voir les variations », « Épuisé »;
  menu choice only on a Caisse without its own menu), the web cart on the right, always visible: « Sur place / À
  emporter » (when both are allowed), « Commande #n » (the next number, a preview), « Choisir la table » (areas, free /
  occupied / reserved; a free table asks the covers, 1 by default; an **occupied table opens its check** for a new
  round), covers stepper, « Note » (the round's note, kitchen ticket), the waiter (a list with « Choisir le serveur »,
  else the account), lines « name — variation · note · − + · ×qty · bin » with options and the line's note under it
  (− stops at 1, the same choice adds to its line), « Total · n art. » opening Article(s) / Sous-total / charges (× to
  take one off a new order) / taxes, « Envoyer », « Encaisser Ticket » (shown greyed until D5), « Imprimer Ticket »
  (bill + ticket). After an order: a fresh POS (back on « Sur place », next number). Round on an open check (« Nouveau
  ticket » from Commandes or an occupied table): its lines shown « Envoyé » / « En attente de stock », the new ones
  editable, « Imprimer Ticket » sends the round then bills. Stock held back: « En attente de stock » window with the
  stock's message, « Garder en attente » / « Forcer l'envoi » (with the right). A ticket not printed: a window says why
  (the bill is made). Leaving with lines: « Abandonner la commande ? ». The idle lock is held while the cart has lines.
  **Short screens** (body under 660 dp: 1366 × 768 once Windows has its bars, 125 %): the header folds into one line
  (« #4 · Sur place · T3 · 3 couv. · Note · Amel ») that opens its controls in a window; footer buttons 48 dp.
- **Commandes** (`ui/orders/OrdersModel`, `OrdersScreen`): status chips with counts (Toutes, En cours, Facturées,
  Payées, À crédit, Annulées), cards (#, status, total, table / à emporter, articles, waiter, time, « Hors ligne »,
  « À vérifier »), the detail as the web order panel (table · couverts · type · serveur · heure · caisse, cancel reason,
  round note, « En attente de stock » with Envoyer / Forcer l'envoi, lines by kitchen ticket with its state and « Servi »
  when ready, lines not sent with a bin when waiting for stock, totals, paid, still due). Actions by state and right:
  « Nouveau ticket », « Addition », « Addition et impression », « Réimprimer », « Encaisser » (D5, greyed),
  « Annuler la commande » (list of reasons + comment, one of them required as on the web). Back from a round: Commandes
  on the same order, with what happened. Times in the PC's own time zone.
- Window style: `AppDialog` takes its width (no platform maximum), `ScrollArea` (a window's scrolling part, scrollbar
  only when it overflows), the note windows take the keyboard at once.
- **Item photos, as the web card** (`pos/menu`; client after D3): `ui/pos/ItemImages` + `ItemPhoto` — the caisse's
  `image_url` put on the server of Réglages (its `APP_URL` is often `localhost`), fetched through `ApiClient.images()`
  (the app's HTTPS trust, no token, an HTTP cache of 50 MB in `%APPDATA%\AltosPOS\cache\images` that follows the
  caisse's cache headers), kept in memory (400); `img/food.svg` and a photo that does not come → the web's food icon,
  bundled with the app (`resources/item/*.svg`, copied from the caisse's `public/img`), tried again after a minute.
  Card (`ItemCard`): the photo in a square (cropped, as `object-cover`), the item type's badge at the top left (veg,
  non-veg, egg, drink; none for « other », which has no icon on the web either), « ÉPUISÉ » over the darkened photo,
  the name on two lines and the price in a green pill with its currency (« 55.000 DT »), or « Voir les variations »;
  grid of cards from 150 dp (5 columns at 1366 × 768, 3 at 125 %). Compared with the web POS in a browser at
  1366 × 768: same columns, photo, icon, badge, pill.
- Tests: 67 (+ `ItemImagesTest` 5: the URL put on the server of Réglages, the food icon without a call, a photo
  fetched once then kept, a photo that does not come falls back and is tried again after a minute, no badge for
  « other »; `PosDataTest` 9: amounts as the caisse writes them, cart merging / « − » / notes, the web's totals,
  sections / search / options, « Imprimer Ticket » printed here from the caisse's ticket with every field sent
  (`print_target`, `waiter_id`, `removed_charge_ids`, note), a ticket not printed leaves the bill made, the stock's
  refusal with what to do, the POS context's currency kept, cancel with its reason, a refused reprint says why).
- Checked end to end (2026-11-08) on the packaged app with the local caisse (Moon Lounge card, tables added), at
  1366 × 768 and 1093 × 614: sections, search, variation, line note, table + 3 covers → « Commande #2 envoyée en
  cuisine » (the caisse has table 3, 3 covers, the waiter, the note, one kitchen ticket); « À emporter » + « Imprimer
  Ticket » printed here (« 08 nov. 2026 16:38 »); Commandes → « Nouveau ticket » (dessert) → back on the order with its
  second kitchen ticket; « Addition et impression »; the Gérant cancelled with « Demande du client — table partie »
  (kept « Annulée »), the Caissier had no cancel button. Not checked here: a round held by the stock (stock off
  locally; covered by the tests), on a real Windows screen.

## Roadmap — step 4 (review approved 2026-11-08; ~17.5 days)

| Step | Caisse | AltosPOS desktop | Effort | Status |
|---|---|---|---|---|
| D1 | Counter rights in `abilities` | Réglages (server, test, certificate, session, application), sign-in, Accueil by rights, Caisse picker, change user, idle lock, MSI "AltosPOS" | ~2 d | [x] built 2026-11-08, approved by the client |
| D2 | Print jobs per printer; branch key endpoint; X / Z thermal ticket fix; currency format fix; « Complet / Résumé » | Style system (client's note on D1); ticket printing (USB or Ethernet), test print, « Sur cet appareil / Imprimante de la caisse », "Directe" agent, tray + start with Windows | ~4 d | [x] built 2026-11-08, client: "Great, proceed to D3" |
| D3 | POS context, waiter right, charge taken off, cancel like the web, bill date | POS (sections in Tri order, search, web cart always visible, table picker + à emporter, send, add round, bill, waiting stock / force) and Commandes | ~3 d | [x] built 2026-11-08, client to test |
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
- 2026-11-08 — D1 built (see "How D1 is built"): 34 desktop tests, caisse suite 316 passed (8 skipped); run end to end
  against a local caisse with screenshots. Next: the client builds (`gradlew run`, `packageMsi`) and tests D1 on the
  counter PC; then D2.
- 2026-11-08 — Client approved D1, with a note: the style must be right (buttons, texts, margins, paddings) — Réglages had
  display issues. Measured at 1366 × 768 and the 125 % / 150 % sizes: no scrollbar, five control heights, 12 px help
  text, blue selections, chips moving, misaligned rows, unclear saving. Client chose: Réglages one page, cleaned up,
  inside D2. D2 built (see "How D2 is built"): style system on every screen, ticket printing, test print, « Directe »
  agent, tray, start with Windows; caisse part in its `CLAUDE.md`. Found by running the packaged app: French dates
  missing from the installer's runtime (`jdk.localedata` added), the PIN window too tall at 125 % (compact layout).
  53 desktop tests; caisse suite 328 passed. Next: the client tests D2 on the counter PC (USB printer, tray, Windows
  start); then D3 (POS + Commandes).
- 2026-11-08 — Client: "Great, proceed to D3". Asked first (client's answers): table picker + à emporter; the cart copies
  the web POS ("copy the same web UI and logic"); Commandes = today with status chips; the bill date fixed now; cancel
  like the web everywhere. D3 built (see "How D3 is built"); caisse part in its `CLAUDE.md`. Found by running it: the
  cart's lines had ~180 px at 1366 × 768 (header compacted, then folded into one line on short screens), the bottom
  buttons were cut, the note window did not take the keyboard, the table picker was held to the platform's dialog width.
  62 desktop tests; caisse suite 337 passed. Next: the client tests D3 on the counter PC; then D4 (Tables).
- 2026-11-08 — Client: "check the POS uses images like the web and the handheld". It did not (text cards). Client: like
  the web, photo on each article, the food icon for those without, square cards. Built (see "Item photos"), compared
  with the web POS in a browser. Caisse fixes of the same day in its `CLAUDE.md`. 67 desktop tests.
