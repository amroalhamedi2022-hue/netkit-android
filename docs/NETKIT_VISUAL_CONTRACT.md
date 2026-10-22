# NetKit Liquid Aurora — VISUAL ACCEPTANCE CONTRACT
**Locked visual reference:** user-supplied 2026-10-22 three-concept image, dark Liquid Aurora / light Aqua Glass / rich Nebula Flow. This visual reference was originally for another product. Reuse its **style principles only**; do not reproduce Remote Control screens or functionality.

## What v0.6 got wrong
- Colors from Material components ignored contrast on some surfaces (black labels on dark glass); Arabic labels clashed with English numeric fields.
- One narrow vertically stacked feed squandered Xiaomi Pad 5 tablet landscape space and left giant unused regions.
- Bare emoji icons, basic out-of-box buttons, uneven spacing and fixed translucent cards were not the requested premium product.
- Four mild radial dots are not enough to qualify as liquid motion; no meaningful surface transitions or tactile response.
- Server operations and shares are shown as lists of URLs rather than a useful operating dashboard.

## Theme system
- **Aurora Liquid (DEFAULT):** very dark blue-violet base, electric cyan-to-blue and ultraviolet highlights, glass with crisp soft-white headings and cool translucent dividers; layered liquid clusters and refraction-inspired lighting.
- **Aqua Glass:** off-white/icy blue base with cyan/teal tones and elegant dark navy text; airy depth and refined glass surfaces; contrast must work in bright rooms.
- **Nebula Flow:** deep indigo/plum base, vivid magenta/pink/coral and ultraviolet glow, readable near-white text.
- Never derive text color from the gradient alone: set on-surface tokens per theme with >=4.5:1 contrast for normal text where feasible. English strings, filenames, IP addresses and numeric values display in stable LTR spans inside Arabic RTL layout.
- Build reusable tokens: spacing scale, typography scale, elevation/glow, glass fill, blur/fallback, outlined icon family, three gradient palettes, spring/tween profiles.

## Navigation / layout
- Pad 5 LANDSCAPE: collapsible rail or side navigation; large-but-not-empty workspace with 2+ balanced panels when width permits. Upper status surface for local network, connection and active transfers; dashboard shortcut cards below.
- Pad 5 PORTRAIT: adaptive one/two-column layout and compact context status, with bottom navigation only when appropriate.
- Phone: compact single-column screens with a persistent clear transfer-action control.
- Sections: Dashboard, Nearby Devices, Transfer Center, Browser Share, Local Servers (HTTP/FTP/SFTP), Network Tools, Connectivity/Hotspot, Appearance/Settings.
- Never show a screen with mostly dead whitespace while hiding the app's real tools. Background decoration cannot obstruct controls or reading.
- Active transfer surface: selected sender/receiver, filename/type/size, measured current throughput, bytes sent, speed smoothing, progress, ETA or explicit 'calculating', pause/cancel/retry where transport supports.
- Browser share: strong QR and pairing/consent block; use a temporary browser URL with a copy interaction and understandable TLS advice; never print confidential tokens in logs.

## Motion / touch
- Floating liquid depth created with controlled asynchronous gradient movement and radial/refraction-like bubbles, clipped correctly to background (not hovering over content).
- Navigational transitions include fade/slide, selected-pill transitions, stateful button press springs, discovery pulsing, device pairing success/error, real transfer progress fill and completion state.
- Motion has purpose; no animations that suggest fake transfer speed or fake connection progress. Recompute only affected Compose elements rather than blanket invalidation at 60fps.
- Reduce Motion toggle (prefer OS setting when available) plus disable effects for low power; preserve visual hierarchy even fully static.
- Responsive touch targets (normally >=48dp), legible labels, accessible TalkBack, logical focus/order.

## Visual sign-off gate
Capture from **real compiled app** (not a concept mock) on Pad 5:
1. Dashboard + discover devices, both portrait and landscape.
2. Browser Share and Transfer Center with active transfer, completion and error state.
3. Local Servers and Diagnostics, action controls readable and meaningful.
4. Three palette screenshots of same dashboard, demonstrating independently crafted color systems.
5. Short screen recording showing animations in active UI and switch to reduced motion.

PASS only if no dark-on-dark labels, no clipped Arabic or LTR links, no faux/misleading controls, no oversized unused space, and the effect is visibly comparable in craft to the supplied concept.
