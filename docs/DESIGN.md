# Design

One sentence: a website, presented as though someone cared about it. Warm paper in the light, warm
charcoal in the dark, one accent, no ornament that does not report something.

The reference is the *quality* of the Claude app's chrome, not its identity: nothing here copies a
logo, a wordmark, an icon set, a brand colour or a proprietary asset. The launcher and splash mark are
a generic geometric "A" drawn as a path in this repository.

## Colour

Every colour in the app is one of thirteen roles in `ui/theme/Color.kt`. Nothing else is allowed to
appear in the shell.

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| background | `#F6F3EF` warm paper | `#141312` charcoal | window, behind the WebView, the failure screen |
| surface | `#FFFFFF` | `#1C1B19` graphite | panels, strips |
| surface high | `#F1EDE8` warm grey | `#262421` | the "something happened" surfaces |
| outline | `#E3DDD5` | `#37342F` | 1 dp hairlines |
| outline soft | `#EDE8E2` | `#2C2A26` | hairline on a quiet notice |
| ink | `#1C1A17` | `#ECE9E4` | headings, body |
| ink muted | `#6B655D` | `#A39C92` | secondary text |
| ink faint | `#746D63` | `#8B857B` | recovery lines, dismiss glyphs |
| accent (clay) | `#A64E2C` | `#C8794F` | the progress line, the primary button, one link |
| on accent | `#FFF8F4` | `#1B0E06` | text on the accent |
| error | `#A8321F` | `#D96A5B` | only: a load that failed |
| warning | `#8A5A00` | `#D9A03F` | only: an approval the site still needs |

Contrast was computed from these hex values (WCAG 2.x relative-luminance ratio), not eyeballed: the
lowest text pairing in either theme is **4.54:1** (dark error red on the high surface) and body ink is
**14–17:1**, so every text role clears 4.5:1 and nothing relies on colour alone to be readable.

The accent is deliberately *not* a saturated blue-violet: a warm clay reads as a tool rather than as a
gadget, and it survives being the only saturated colour in the app.

## Forbidden, and enforced by review

| Not allowed | Because |
| --- | --- |
| blue, violet or cyan gradients; any gradient fill | they are the default look of "AI product", and they carry no information |
| glowing borders, coloured shadows, bloom, neon outlines | a glow reads as "active" whether or not anything is |
| oversized blurred header blobs | one full-screen blur pass per frame, on the exact devices this app targets |
| glassmorphism / frosted panels | a `RenderEffect` cost for a look, and it makes text contrast unpredictable |
| hero sections, marketing illustrations, onboarding carousels | the site is the product; a hero here is a wall between you and it |
| animation for its own sake (entrance fades, springs, parallax, spinners that wait for nothing) | every one of them delays something you asked for |
| pillowy 28 dp+ buttons, cartoon outlines | they read as a toy; see Shapes |
| a second accent "for dark mode", gradient text, animated counters | decoration that changes meaning |

The only motion in the app is the 2 dp progress line, driven by `onProgressChanged`, and it disappears
when loading does. No `AnimatedVisibility`, no crossfade, no spring, no shimmer, no skeleton placeholder.
If a future change wants animation, it has to say what the animation *reports*.

## Shapes, depth, typography

- Radii: 6 / 10 / 14 / 18 / 22 dp (`MaterialTheme.shapes`), assigned by size of surface rather than by
  fashion. A strip is 12–14, a panel 18–20, a button 10.
- Depth comes from a 1 dp hairline in `outline` plus one step of surface colour. `shadowElevation` is
  never used to decorate; panels sit flat on the background.
- Hairlines are 1 dp, never 2 dp gradients, and never coloured by state.
- Type: platform default (no font asset is bundled, so nothing is added to the APK and metrics match
  the rest of the device). `titleMedium` for the one heading a panel has, `bodyMedium` for what
  happened, `bodySmall` for what to do next. `labelLarge` for a card's kind. One monospace moment
  exists — the `ARENA` wordmark on the cold-start frame, tracked 8 sp — because that is the only place
  the app speaks as itself rather than as the site.
- Icon policy: two glyphs, from `material-icons-core`, both inside 16–20 dp, `contentDescription` set
  for anything meaningful and `null` for anything redundant. No icon font, no extended set (it was
  deleted: an unused 1.5 MB of vectors is not a free dependency).

## Light and dark

`isSystemInDarkTheme()`, no in-app toggle. The window background, the splash background and the
navigation/status bar treatment come from `values/` and `values-night/` XML so the **first native
frame** is already correct — a white flash before a dark app appears is the most common way a WebView
shell looks cheap, and it is fixed with four resource lines rather than a theme.

Web content follows the app's theme through the supported switch
(`WebSettingsCompat.setAlgorithmicDarkeningAllowed`, when the device's WebView provides
`ALGORITHMIC_DARKENING`); the app never forces colours onto the page and never invents a dark theme for
content that has none.

## Splash

`androidx.core:core-splashscreen`, themed by `Theme.Arena.Starting`, with the system doing the drawing
(the same on 26–30 as on 31+). It is held until the page paints its first frame, and never longer than
1.2 s, so a slow network still reaches something you can tap. There is no brand animation and no
second, hand-drawn splash screen to keep in sync.

## Loading, offline, error

- Loading: 2 dp determinate line at the very top, from the WebView's own progress. No modal, no
  blocking scrim, no spinner over content you are trying to read.
- Offline: the reducer keeps the loaded page and says so in one line. A page you can read is not thrown
  away because a radio went quiet.
- Failure: a full-screen panel *only* when nothing has painted; otherwise a dismissible card. Titles
  name the observable fact; recovery lines name what the app cannot do; the retry button is disabled
  when retrying cannot help (`WebFailure.retryable`).

## A window the page opens

When the page calls `window.open()` — which is how a Google sign-in popup arrives — the shell shows a
bottom sheet, not a dialog and not a silent swap of what was on screen:

- the sheet occupies the bottom 92 %, starting 44 dp down, so the site stays visible above it and the
  user can see that something was *added* rather than replaced;
- the scrim is `ArenaPalette.Scrim` and nothing else: no blur, no frost, no animation;
- the header strip is `surfaceHigh` with one hairline of structure from the corner radius alone
  (16 dp top corners, `clip`, no border), holding one muted `labelMedium` sentence and **Close**;
- the sentence names the host the window is really on, read from the window itself. This is the design's
  one security-relevant affordance: an unlabelled second page over the site is exactly what a
  look-alike login wants;
- ink, accent and the one red behave as everywhere else. A failure inside that window is one line of
  text, never a `error`-coloured panel over the site.

This does not contradict "no rounded-corner app frame around the WebView": that rule forbids decorating
the page itself. Here the rounding belongs to the sheet, which is chrome the app owns.

## Deliberately not built

A theme switcher (the site has appearance settings); accent-colour settings; custom fonts; a
rounded-corner "app frame" around the WebView; a native header bar with a title (the page has its own);
a bottom navigation bar; a FAB; pull-to-refresh (it would reload, which is the one thing the
background requirement forbids); a screenshot-protection toggle; a serif display face; animated
skeleton screens; a haptic on every tap.
