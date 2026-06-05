# Lymow RC App — UI/UX Design Specification

**Date:** 2026-06-04
**Platform:** Android first (Jetpack Compose, Material 3), Kotlin Multiplatform (iOS later)
**Author:** Product Design
**Status:** v1 design spec (Settings screen is forward-looking shell only)

---

## 0. Purpose & design problem in one paragraph

This app turns a phone into a **manual RC controller for a blade-spinning robotic mower** over Bluetooth LE. The defining facts that drive every decision: (1) the user is **outdoors in bright sun, looking at the mower, not the phone**, operating **one-handed with a thumb**; (2) the mower is **physically dangerous** and has **no watchdog** — if BLE drops while driving, the blades keep moving and the mower keeps going. Therefore the design optimizes for *glanceability, sun contrast, large thumb targets, and making connection/stop state impossible to misread*. Prettiness is secondary to "can I tell at a one-second glance whether this thing is connected and whether it's about to drive into my flowerbed."

---

## 1. Design language / mood

**Vibe name: "Site Rugged."** Think industrial outdoor-equipment controller — the lovechild of a DJI drone controller, a construction-grade Trimble GPS handset, and a Caterpillar dashboard. Dark, matte, high-contrast, function-first. No glassmorphism, no soft pastel gradients, no decorative fluff that washes out in sunlight.

**Principles:**

1. **Contrast over decoration.** Every important element clears 7:1 contrast against its background so it survives direct sun. Decorative mid-tones are banned for anything load-bearing.
2. **Big, blunt, gloved-thumb-friendly.** Controls are oversized. Nothing critical is smaller than 56dp; the joystick is enormous.
3. **State is shouted, not whispered.** Connection and motion state use color + shape + text + motion + haptics redundantly. You should never have to read a label to know if you're connected.
4. **Calm when safe, alarming when not.** Idle/connected UI is quiet and dark. Danger states (disconnect, error) flip to saturated, pulsing, unmissable treatments.
5. **One-thumb symmetry.** Layout is horizontally mirror-tolerant: the joystick centers low so either thumb reaches it; secondary controls live top-corners, reachable on the backswing.

**Material reference:** matte anodized metal, hazard-stripe accents used *sparingly* (only true danger), rubberized dark housing. Surfaces read as physical panels with subtle 1dp hairline borders rather than drop shadows (shadows disappear in sun; borders don't).

---

## 2. Color palette

Designed **dark-first**. A dark UI with bright accents reads better in sun than a white UI (less full-screen glare bouncing back at the eye, and saturated accents pop harder against near-black). A light theme is provided for users who prefer it and for accessibility, but **dark is the default and recommended outdoor mode**.

### 2.1 Core neutrals (Dark — default)

| Token | Hex | Use |
|---|---|---|
| `bg/base` | `#0B0F0E` | App background (near-black, slight green-cool cast) |
| `surface/1` | `#15201D` | Cards, header bar, list rows |
| `surface/2` | `#1E2C28` | Raised elements, joystick gate well |
| `outline/hairline` | `#3A4A45` | 1dp borders, dividers |
| `text/primary` | `#F2F7F5` | Primary text, status names |
| `text/secondary` | `#A9B8B2` | Secondary labels, captions |
| `text/disabled` | `#5C6A65` | Disabled text |

### 2.2 Core neutrals (Light — opt-in)

| Token | Hex | Use |
|---|---|---|
| `bg/base` | `#F4F7F5` | App background |
| `surface/1` | `#FFFFFF` | Cards / header |
| `surface/2` | `#E7EDEA` | Raised elements |
| `outline/hairline` | `#B9C6C1` | Borders |
| `text/primary` | `#0B0F0E` | Primary text |
| `text/secondary` | `#3E4B47` | Secondary text |
| `text/disabled` | `#9AA7A2` | Disabled text |

### 2.3 Semantic / status colors (shared, tuned per theme)

These are the load-bearing colors. Each is defined for use as a fill on dark bg; the light-theme variant is given where it differs.

| Semantic | Dark hex | Light hex | Meaning |
|---|---|---|---|
| `state/connected` | `#22E06B` | `#0E9F4C` | BLE connected, driving allowed. High-vis "go" green. |
| `state/connecting` | `#FFC02E` | `#C98600` | Scanning / connecting / reconnecting (transient). Amber. |
| `state/disconnected` | `#FF3B30` | `#D32218` | **Not connected.** Red — treated as danger, see §6. |
| `state/warning` | `#FF8A1E` | `#C25E00` | Low battery, weak signal, non-fatal mower warning. Orange. |
| `state/danger` | `#FF1E1E` | `#C20000` | Error / emergency-stop / lost-link-while-driving. Pure alarm red. |
| `accent/drive` | `#3DD6FF` | `#0079A6` | Joystick active vector, magnitude readouts. Cyan — distinct from all status colors so it never reads as "alarm." |
| `hazard/stripe` | `#FFD400` on `#0B0F0E` | same | Diagonal hazard striping for E-stop affordance only. |

**Why these choices:**
- **Green = connected/safe** and **red = disconnected/danger** map to universal machine-safety convention (green run, red stop). We deliberately make *disconnected* red, not gray, because a dropped link with no watchdog is a dangerous state, not a neutral one.
- **Amber connecting** signals "transient, don't trust it yet."
- **Cyan drive accent** is intentionally *not* on the red/green/amber axis, so the live motion vector never gets confused with a status color and stays legible for red-green color-blind users.
- Near-black green-cooled background reduces sun glare versus pure black and gives the green/cyan accents a complementary pop.

---

## 3. Typography & sizing

**Typeface:** A wide, sturdy, high-legibility sans with tabular figures. Recommend **Inter** (variable) for UI text and **Roboto Mono** / Inter tabular for numeric readouts (battery %, magnitude). Tabular figures prevent jitter when numbers change rapidly while driving.

### 3.1 Type scale

| Role | Size / line | Weight | Use |
|---|---|---|---|
| `display` | 40 / 44sp | 800 | Battery %, big status callouts |
| `headline` | 28 / 32sp | 700 | Screen titles, status name on drive screen |
| `title` | 22 / 28sp | 700 | Card titles, device names |
| `body` | 17 / 24sp | 500 | Default body, list rows |
| `label` | 15 / 20sp | 600 | Buttons, chips, captions (all-caps for status chips) |
| `mono-readout` | 18 / 22sp | 600 tabular | DRIVE / TURN magnitude numbers |

Minimum body text never below **15sp**. Respect system font scaling up to **200%** (see §8).

### 3.2 Touch targets & spacing

- **Minimum interactive target: 56×56dp** (exceeds Material's 48dp; we are gloved/sunlit/one-thumbed).
- Primary actions (Scan, Connect, E-stop): **64dp tall minimum.**
- Joystick: see §5 — far larger.
- Base spacing grid: **8dp**; component padding 16dp; screen edge margin 20dp.
- Corner radius: **16dp** for cards, **28dp** (pill) for buttons/chips, joystick gate is a true circle.

---

## 4. Screen layouts (wireframes)

Orientation recommendation up front:

> **Recommended orientation: PORTRAIT for connect/settings, and the Drive screen supports BOTH but defaults to and is optimized for PORTRAIT.**
>
> Rationale: the user holds the phone one-handed while walking and watching the mower. One-handed portrait is the natural grip; landscape generally forces two hands. The joystick is placed in the lower-third "thumb zone" of portrait. We *do* offer a landscape Drive layout (joystick to one side, status to the other) for users who prop the phone or use the future gamepad, but we do **not** force rotation. Drive screen locks out auto-rotate while actively driving to avoid a layout jump mid-maneuver.

### 4.1 Device list / connect screen (portrait)

```
┌─────────────────────────────────────┐
│  ●  LYMOW RC                         │ ← app bar, surface/1
├─────────────────────────────────────┤
│                                     │
│   Connect your mower                │ ← headline
│   Bring the phone near the mower    │ ← body/secondary
│                                     │
│  ┌───────────────────────────────┐  │
│  │  Lymow_8F2A                   │  │ ← device row, 64dp tall
│  │  ▣▣▣▢  signal      [CONNECT]  │  │   tap row or button
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │  Lymow_3C71                   │  │
│  │  ▣▣▢▢  signal      [CONNECT]  │  │
│  └───────────────────────────────┘  │
│                                     │
│            (more appear here)       │
│                                     │
│                                     │
├─────────────────────────────────────┤
│   ◔  Scanning for Lymow_…           │ ← scan status strip (amber dot)
│  ┌───────────────────────────────┐  │
│  │           ⟳  SCAN             │  │ ← 64dp primary button (pill)
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

**States of this screen:**

- **Permission moment** — before first scan, tapping SCAN triggers the OS BLE/Location permission sheet. Behind/around it we show a pre-permission explainer card so the OS prompt isn't a surprise:

```
┌───────────────────────────────┐
│  📶  Bluetooth permission      │
│                               │
│  We use Bluetooth to find and │
│  drive your Lymow mower.      │
│  No location data leaves your │
│  phone.                       │
│                               │
│      [ NOT NOW ]  [ ALLOW ]   │
└───────────────────────────────┘
```

- **Scanning, empty** — animated radar/pulse glyph, "Scanning for Lymow_… Make sure the mower is powered on." SCAN button shows a stop affordance (`■ STOP SCAN`) while active.
- **Scanning, results present** — rows stream in, newest sorted by signal strength (strongest top). Each row: device name (`title`), 4-bar RSSI meter, CONNECT button.
- **Connecting** — tapped row collapses others, shows inline spinner + "Connecting…" in amber; row border animates amber.
- **Connected** — auto-navigates to Drive screen with a connect haptic (see §7).
- **Bluetooth off** — replaces list with a centered card: "Bluetooth is off" + [ TURN ON ] (deep-links to system toggle).
- **No devices after ~8s** — empty-state card: "No Lymow mowers found. Power-cycle the mower and scan again." with a retry button.

### 4.2 Drive screen (portrait — primary)

```
┌─────────────────────────────────────┐
│ ┌────────────────┐      ┌─────────┐ │
│ │ ● CONNECTED    │      │  87%  ▮ │ │ ← status chip (green) + battery
│ │ Lymow_8F2A     │      └─────────┘ │
│ └────────────────┘                  │
│                                     │
│   STATUS:  remote_control           │ ← mower status name, headline
│                                     │
│                  ┌───┐              │
│                  │ ⏹ │ ← E-STOP     │ ← top-right, hazard-bordered, 72dp
│                  └───┘              │
│                                     │
│        DRIVE  +0.00   TURN  +0.00   │ ← live magnitude readout (cyan/mono)
│                                     │
│           ╭───────────────╮         │
│          ╱        ▲        ╲        │
│         │                   │       │
│         │     ╭───────╮     │       │ ← JOYSTICK
│        │      │  ███  │      │      │   gate Ø ~300dp
│        │   ◄  │  KNOB │  ►   │      │   knob Ø ~108dp
│         │     ╰───────╯     │       │
│         │                   │       │
│          ╲        ▼        ╱        │
│           ╰───────────────╯         │
│                                     │
│            release = stop           │ ← persistent reassurance caption
└─────────────────────────────────────┘
```

Notes on layout:
- **Status cluster pinned top** so the user can glance up while the thumb stays on the joystick down low.
- **Joystick centered horizontally, biased to the lower 55% of the screen** — the natural thumb arc for either hand. It is horizontally centered (not left/right offset) precisely so it's equally reachable left- or right-handed.
- **E-stop top-right**, always visible, but positioned so it is not under the driving thumb (you stop with the *other* hand or a deliberate reach). It's also redundant with "release the joystick = stop."
- **Magnitude readout** sits just above the joystick, in the eye-path between status and control.

### 4.3 Drive screen (landscape — secondary)

```
┌──────────────────────────────────────────────────────────────┐
│ ● CONNECTED  Lymow_8F2A          STATUS: remote_control       │
│                                                  87% ▮   [⏹] │
│                                                              │
│   DRIVE +0.42  TURN -0.10            ╭───────────────╮       │
│                                     ╱        ▲        ╲      │
│                                    │      ╭───────╮    │     │
│                                    │   ◄  │ KNOB  │ ►  │     │
│                                    │      ╰───────╯    │     │
│                                     ╲        ▼        ╱      │
│                                      ╰───────────────╯       │
│                                          release = stop      │
└──────────────────────────────────────────────────────────────┘
```

Landscape places the joystick on the **right** by default (right-thumb), with a settings toggle to mirror to the left. Status/readouts move to the left column. Use this when the phone is propped or with the future gamepad.

### 4.4 Settings screen — shell (forward-looking, NOT built in v1)

v1 ships the **navigation entry and screen shell only**, so the IA has a clean home for gamepad mapping later. Entry point: a gear icon in the Drive screen status bar / connect screen app bar.

```
┌─────────────────────────────────────┐
│  ‹  SETTINGS                         │
├─────────────────────────────────────┤
│  CONTROLLER                          │ ← section header
│  ┌───────────────────────────────┐  │
│  │ 🎮 Gamepad / USB-C joystick   │  │
│  │    Not connected         ›    │  │ ← row → button-mapping screen
│  └───────────────────────────────┘  │
│                                     │
│  DRIVE                              │
│  ┌───────────────────────────────┐  │
│  │ Joystick hand     [Left|Right]│  │
│  │ Landscape layout        [ ◯ ] │  │
│  └───────────────────────────────┘  │
│                                     │
│  ABOUT                              │
│  ┌───────────────────────────────┐  │
│  │ App version            1.0.0  │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

**Button-mapping sub-screen (future, sketched):**

```
┌─────────────────────────────────────┐
│  ‹  BUTTON MAPPINGS                  │
├─────────────────────────────────────┤
│  Detected: "USB Gamepad"   ● ready  │
│  Press a button to identify it.     │ ← live highlight on press
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Button 1        Slow mode   › │  │ ← tap → action picker
│  ├───────────────────────────────┤  │
│  │ Button 2        Turbo mode  › │  │
│  ├───────────────────────────────┤  │
│  │ Button 3        Deck raise  › │  │
│  ├───────────────────────────────┤  │
│  │ Button 4        Deck lower  › │  │
│  ├───────────────────────────────┤  │
│  │ Button 5        Unassigned  › │  │
│  └───────────────────────────────┘  │
│                                     │
│  Action picker (bottom sheet):      │
│   ○ Unassigned                      │
│   ○ Slow mode (precision)           │
│   ● Turbo mode (full speed)         │
│   ○ Raise cutting deck              │
│   ○ Lower cutting deck              │
│   ○ Emergency stop                  │
└─────────────────────────────────────┘
```

When a physical button is pressed during mapping, its row flashes the cyan accent so the user can correlate physical → on-screen. Actions come from a fixed enum list (single-select per button). This is the only design owed for Settings in this spec.

---

## 5. The joystick component (detailed)

This is the heart of the app. Single 2-axis, spring-return, virtual joystick.

### 5.1 Geometry & sizing (portrait, baseline)

| Element | Size | Notes |
|---|---|---|
| **Gate (outer boundary)** | **300dp diameter** | Circular well. Defines max travel. Centered to thumb zone. |
| **Knob (thumb cap)** | **108dp diameter** | The draggable cap. Large enough for a thumb in sun. |
| **Max knob travel** | gate radius − knob radius = (150 − 54) = **96dp** | Knob center can move up to 96dp from center. |
| **Deadzone radius** | **14dp** (~15% of travel) | Inside this, output = 0 / 0. Prevents drift and accidental creep. |
| **Center crosshair** | thin cyan + at gate center | Reference for "neutral." |

Scale gate to `min(0.82 × screenWidth, 320dp)` on small devices; never below 240dp.

### 5.2 Output model

- Normalize knob offset to unit vector within travel radius.
- `drive = -y` (up = +forward), `turn = +x` (right = +turn-right). Range each [-1.0, +1.0].
- Apply deadzone, then optionally a slight expo curve (cubic blend ~30%) for fine control near center — precision near flowerbeds matters. Document the curve but keep it subtle; raw linear is acceptable for v1.
- **Spring return:** on release, knob animates back to center over ~120ms with a slight overshoot-free ease-out; **output is forced to 0/0 immediately on pointer-up** (do not wait for the animation — safety: release means stop *now*, the animation is cosmetic).

### 5.3 Visual feedback states

**(a) Idle / centered (connected)**
- Gate: `surface/2` fill, 2dp `outline/hairline` ring, faint inner radial vignette.
- Knob: raised matte disc, `text/primary` rim, subtle 1dp top highlight; centered crosshair visible beneath.
- Four faint direction ticks (▲▼◄►) at gate edge in `text/secondary`.
- Calm. No motion.

**(b) Actively dragging**
- Knob follows thumb (clamped to travel radius — hard stop at the gate, knob cannot leave the well; this is the "gate").
- **Vector line** drawn from center to knob in `accent/drive` cyan, width scaling 3→6dp with magnitude.
- **Magnitude arc:** an arc/ring segment fills around the gate proportional to total magnitude, cyan, so peripheral vision reads "how hard am I pushing" without looking directly.
- **Direction sector glow:** the gate edge in the push direction brightens cyan.
- Knob lifts (elevation/scale +4%) and gains a cyan ring.
- Live numeric readout above updates (`DRIVE +0.42  TURN -0.10`), tabular mono, cyan.
- **Haptic:** soft tick when crossing the deadzone boundary (entering "live"); optional faint tick at full deflection.

**(c) Released / springing back**
- Output already 0/0 (see 5.2). Knob eases to center 120ms; vector line and arc fade to 0 over same duration; readout snaps to `+0.00`.
- **Haptic:** single medium "stop" thump on release (confirms "you let go, it stopped"). See §7.

**(d) Disabled — disconnected**
- Entire joystick desaturates to grayscale, 40% opacity, gate ring becomes dashed `text/disabled`.
- Knob locked at center, not draggable; touches are ignored (and instead surface the reconnect prompt).
- A diagonal "no-entry" treatment is *avoided* (too subtle in sun); instead the whole screen takes the disconnect treatment (§6) which dominates.
- Caption changes from "release = stop" to "DISCONNECTED — not driving."

**(e) Error / emergency-stop mower state**
- Joystick disabled as in (d) but with the gate ring pulsing `state/danger` red; input ignored until the mower leaves the error/e-stop state.

### 5.4 Why a circular gate (not square)

A circular gate gives uniform max speed in every direction and a natural thumb-arc boundary. Diagonal corners of a square would allow √2 over-range and feel inconsistent. The hard circular wall doubles as tactile-by-sight feedback ("I'm maxed").

---

## 6. Status & safety affordances

### 6.1 Connection state — the most important indicator

Shown **top-left as a status chip**, redundantly encoded:

| State | Dot | Chip fill | Label | Extra |
|---|---|---|---|---|
| Connected | ● green, steady | `surface/1` + green text | `CONNECTED` + device name | quiet |
| Connecting/reconnecting | ◔ amber, pulsing | amber-tinted | `CONNECTING…` | joystick disabled |
| Disconnected | ● red, **blinking 1Hz** | **red fill** | `DISCONNECTED` | full-screen treatment ↓ |

**Disconnect treatment (the alarming one):** Because a mid-drive drop is dangerous, a disconnect is not a quiet chip change. It triggers:
1. A **red full-width banner** slams down from the top: `⚠ LINK LOST — mower may still be moving`.
2. Screen edges gain a **pulsing red border** (1Hz).
3. The joystick goes to its disabled-red state.
4. **Strong haptic alarm pattern** (long-short-long) + optional alert tone.
5. A prominent **[ RECONNECT ]** button replaces the readout area, and a secondary line: "If the mower keeps moving, use the physical stop / power switch."

This is deliberately over-the-top. With no watchdog, the human is the watchdog, so we make absolutely sure they notice.

### 6.2 Battery

- Top-right: tabular `87%` + horizontal fill bar.
- Thresholds: >30% `text/primary`; 11–30% `state/warning` orange; ≤10% `state/danger` red + bar blinks. Charging shows a ⚡ glyph and the `charging` mower status.

### 6.3 Mower status name

- Rendered as `STATUS: <name>` in headline weight just under the status chip.
- Known states get color + icon: `remote_control` (green ▶), `charging` (amber ⚡), `error` (red ⚠ + full-screen red border like disconnect), `emergency_stop` (red ⏹ + locked joystick). Unknown/new states render the raw string in `text/secondary` so the app never hides a state it doesn't recognize.

### 6.4 Emergency-stop affordance

- **Top-right E-STOP button, 72dp, octagon glyph (⏹/STOP-sign shape), hazard-yellow-on-near-black border (`hazard/stripe`).** It is the only place hazard striping is used, so it's unmistakable.
- Tapping it sends stop-and-disengage immediately, shows a confirming red overlay "STOPPED," and requires an explicit `[ RESUME CONTROL ]` tap to re-enable the joystick (no accidental re-arm).
- Note the **primary** stop is always "release the joystick"; E-stop is the belt-and-braces for "stop and stay stopped."

---

## 7. Iconography & motion

### 7.1 Icons (line weight 2.5dp, rounded caps, drawn to read at 24–32dp in sun)

| Icon | Meaning |
|---|---|
| ⟳ radar/refresh | Scan |
| 📶 / signal-bars | RSSI / Bluetooth permission |
| ● filled dot | Connection state (color-coded) |
| ▮ battery + ⚡ | Battery / charging |
| ⏹ octagon | Emergency stop |
| ⚠ triangle | Warning / error / link lost |
| 🎮 gamepad | Controller settings |
| ‹ chevron | Back |
| ⚙ gear | Settings |
| ▲▼◄► | Joystick direction ticks |

Avoid hairline icons; favor filled or heavy-stroke glyphs for sun legibility.

### 7.2 Motion & haptics

Haptics are first-class here because eyes are on the mower, not the screen.

| Event | Visual | Haptic |
|---|---|---|
| Connect success | chip flips green, brief green flash | **double medium tick** (confirmation) |
| Cross joystick deadzone (start driving) | vector appears | **soft single tick** |
| Full deflection reached | edge glow saturates | optional faint tick |
| Release joystick → stop | knob springs center, readout → 0 | **single medium "stop" thump** |
| Disconnect / link lost | red banner + pulsing edge | **alarm pattern: long–short–long, repeating until acknowledged** |
| Mower error / e-stop state | red border, locked joystick | **alarm pattern (one cycle)** |
| E-stop button pressed | red "STOPPED" overlay | **strong single thump** |

Motion durations: state chips/banners 150–200ms ease; disconnect banner slam 120ms (fast = urgent); pulses 1Hz; joystick spring-return 120ms. Reduce-motion setting: replace pulses with steady high-contrast fills (never remove the *information*, only the animation).

---

## 8. Accessibility

- **Contrast:** all primary text and status indicators target **≥7:1** against their background (WCAG AAA for normal text); large display text ≥4.5:1 minimum. The chosen `text/primary #F2F7F5` on `bg/base #0B0F0E` ≈ 17:1. `state/connected #22E06B` on `#0B0F0E` ≈ 11:1. `state/disconnected #FF3B30` on `#0B0F0E` ≈ 5.3:1 fill, paired with white text and motion for redundancy.
- **Color-blind-safe semantics:** status is **never color-only**. Connected/disconnected/connecting always carry a distinct *shape/animation* (steady dot / blinking dot / pulsing ring) and a *text label*. The drive accent (cyan) is off the red-green axis. Disconnect adds motion + haptics + text, so a fully color-blind user still gets the alarm.
- **Large text:** support dynamic type to 200%. Layouts use flexible/wrapping containers; status name truncates with ellipsis but the chip/label never clips below readable. The joystick is fixed-size (it's a control, not text) and unaffected by font scaling.
- **Touch:** 56dp minimum targets exceed the 48dp guideline; E-stop and Scan are 64–72dp.
- **Screen reader (TalkBack):** status chip announces "Connected to Lymow_8F2A, battery 87 percent, status remote control." Joystick exposes a custom action description and announces magnitude changes coarsely (avoid spamming). Disconnect posts an assertive live-region announcement.
- **Haptic redundancy** ensures eyes-off and low-vision users still perceive connect/stop/disconnect.
- One-handed reach: critical controls within the lower-two-thirds thumb arc; nothing destructive in the easy-reach zone except by deliberate action (E-stop is up-and-across, the joystick release is the easy stop).

---

## 9. Component inventory (for the Compose design system)

Reusable components implied by this spec. Each should live in a `core-ui` / design-system module so iOS (KMP) can share the contracts.

**Foundation / theme**
- `LymowTheme` — Material 3 `ColorScheme` (dark default + light), typography, shapes.
- `LymowColors` — semantic color tokens (`connected`, `connecting`, `disconnected`, `warning`, `danger`, `driveAccent`, `hazard`).
- `LymowType` — type scale incl. tabular mono readout style.
- `Dimens` — spacing grid, min target sizes, radii, joystick dimensions.

**Status & safety**
- `ConnectionStatusChip(state, deviceName)` — color/shape/label/animation encoded.
- `BatteryIndicator(percent, charging)` — tabular % + fill bar + threshold colors.
- `MowerStatusLabel(statusName)` — known-state mapping + raw fallback.
- `DisconnectBanner(onReconnect)` — slam-down red alarm banner.
- `AlarmEdgeBorder(active)` — pulsing screen-edge red border overlay.
- `EmergencyStopButton(onStop)` — hazard-bordered octagon, 72dp.
- `LinkLostScaffoldOverlay` — composes banner + edge border + joystick lockout + haptic trigger.

**Joystick**
- `VirtualJoystick(state, enabled, onVector)` — the full component (gate, knob, vector, arc, deadzone, spring-return, feedback states).
- `JoystickMagnitudeReadout(drive, turn)` — tabular cyan drive/turn numbers.
- `JoystickGate` / `JoystickKnob` — internal pieces (or kept private).

**Connect screen**
- `ScanButton(scanning, onToggle)` — Scan / Stop-scan pill, 64dp.
- `DeviceRow(name, rssi, onConnect)` — 64dp row with signal meter + connect.
- `SignalStrengthMeter(rssi)` — 4-bar meter.
- `PermissionExplainerCard(onAllow, onDismiss)` — pre-permission rationale.
- `ScanStatusStrip(state)` — scanning/empty/bluetooth-off status line.
- `EmptyResultsCard(onRetry)` / `BluetoothOffCard(onEnable)`.

**Settings shell (future)**
- `SettingsScaffold` + `SettingsSection` + `SettingsRow` (nav / toggle / segmented).
- `SegmentedToggle` (joystick hand L/R).
- `GamepadStatusRow` — controller connected state.
- `ButtonMappingRow(buttonName, action, onPick)` (future).
- `ActionPickerSheet(actions, selected, onSelect)` (future).

**Shared interaction**
- `HapticController` — named patterns (connectTick, stopThump, deadzoneTick, alarmPattern).
- `PulsingDot(state)` / `RadarPulse` — connection dot + scanning animation.

---

## 10. Open decisions deferred (not blocking v1)

- Exact expo curve coefficient for joystick (ship subtle/linear, tune with field testing near flowerbeds).
- Whether disconnect alarm includes an audible tone by default (recommend: on, with a settings toggle later).
- Landscape mirroring default (ship right-thumb, expose toggle).

These do not change layout, color, or component contracts above.
