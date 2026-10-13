# Invoice Financing: Apple-paradigm design system

Source of truth for the dashboard and the Lab since 2026-10-11. Replaces the 2026-07-18
neo-brutalist style. Written in the DESIGN.md format (mission, foundations, components, rules,
quality gates) so it can be handed to a design tool or a coding agent as-is. Same design
family as amwayi's personal site (`miguel-site`, `docs/design-apple.md`), tuned for a data
dashboard.

## Mission

An operations dashboard that reads like a macOS / iPadOS app: calm, dense where it needs to
be, status you can scan in a second. The data leads; the interface stays out of the way.

## Principles (Apple Human Interface Guidelines, applied)

1. **Deference.** Gray canvas, white grouped cards, no drop shadows, no borders on cards.
2. **Clarity.** System font (San Francisco on Apple devices). Sentence case, semibold
   headings, tabular numbers. Nothing in all caps except data values that are enums.
3. **Depth through material.** Liquid Glass on the global nav only.
4. **Familiar controls.** Segmented controls for filters and the Lab sections, gray buttons,
   one filled button per view, inset tables, disclosure rows with a chevron.
5. **One tint.** Blue. Amber and yellow already mean "pending" here, so the tint must not
   collide with a status.
6. **Status is semantic.** Green, red, yellow, orange, blue and pink appear only as status
   fills, each with a glyph and text, never as decoration.

## Foundations

### Color (light / dark follow the system setting)

| Token | Light | Dark | Use |
|---|---|---|---|
| --canvas | #F5F5F7 | #000000 | Page |
| --surface | #FFFFFF | #1C1C1E | Cards, tables, fields |
| --surface-2 | #F5F5F7 | #2C2C2E | Tiles inside cards (stats, map nodes) |
| --ink | #1D1D1F | #F5F5F7 | Labels |
| --muted | #6E6E73 | #A1A1A6 | Secondary labels |
| --hairline | #D2D2D7 | #38383A | Table rules, separators |
| --fill | rgba(118,118,128,.12) | .24 | Gray buttons, segmented track, neutral badges |
| --tint | #0071E3 | #0071E3 | Filled (primary) button |
| --link | #0066CC | #4AA3FF | Links, gray-button text |
| --green / -fill | #1B7332 / #E3F5E7 | #4CD964 / #16301C | Confirmed, repaid, up |
| --red / -fill | #C8001A / #FDE8EA | #FF7B73 / #3A1A1A | Failed, blocked, down |
| --yellow / -fill | #805000 / #FFF3D1 | #FFD60A / #33290B | Pending, open, unknown |
| --orange / -fill | #A33A00 / #FFEADB | #FFA64D / #3A2410 | Overdue, known gaps |
| --blue / -fill | #0058B0 / #E4EFFB | #6CB6FF / #10263D | Financed, info |
| --pink / -fill | #B0105A / #FCE7F1 | #FF7EB6 / #3A1729 | Rare: secondary info |
| --code-bg | #1D1D1F | #0D0D0E | Code and log panels (dark in both themes, own --code-* colours) |
| --chart-1..6, -err | Apple system blue, purple, green, orange, gray, yellow, red | dark variants | Charts, trace waterfall |

Measured contrast (WCAG 2.2 AA, all pass): muted 5.1:1 on white and 4.7:1 on #F5F5F7;
link 5.6:1; white on tint 4.7:1; every status text on its own fill 5.2-6.2:1 light and
6.2-10.2:1 dark; every code colour on #1D1D1F 6.5:1 or more.

### Type

- `-apple-system, BlinkMacSystemFont, "SF Pro Text", Inter, system-ui`. Mono:
  `ui-monospace, "SF Mono", Menlo`. No web font is downloaded; the build no longer needs
  Google Fonts.
- Page title 28 / 34px semibold, tracking -0.015em. Card title 17px semibold. Body 15px.
  Table 14px, header 12px muted. Badges 12px semibold.

### Shape and spacing

- Cards 18px radius, 16-20px padding, 20px between cards. Tiles inside cards 12px.
- Buttons and badges are capsules. Fields 10px radius, 36px tall. Segmented: 9px track,
  7px segments.
- Content width 1152px (`max-w-6xl`), 16px gutters on phone.

### Motion

| Frequency | Example | Rule |
|---|---|---|
| Frequent | Hover, press, focus | 150ms; press scale .97 |
| Occasional | Disclosure chevron | 200ms rotate |
| Event-driven | A Lab counter moved | `.lab-flash`: 900ms blue fill fading out, once |

Nothing moves on its own. Transform, opacity and colour only. Reduced motion: no
transitions, the flash becomes a static 2px tint outline. Reduced transparency: the glass
nav becomes solid.

## Components

- **Global nav**: sticky 52px glass bar. Blue "IF" tile + name, text links (current one in
  ink and semibold, others muted), role badge, Sign out as a gray button, Log in filled.
  On phone the links drop to a second row that scrolls sideways.
- **Status strip (Lab)**: a white rounded bar; each dependency with a coloured dot, name and
  latency.
- **Lab nav and list filters**: segmented control. `.segmented` track with `.neo-chip`
  segments; `aria-current="page"` or `aria-pressed="true"` marks the selected one.
- **PageHeader**: title + muted subtitle (68ch max), actions on the right.
- **Card**: white grouped card, sentence-case title, optional right slot.
- **Btn**: gray button by default. `tone="yellow"` (kept for existing call sites) is the
  filled tint button: one per view. `tone="red"` / `"green"` are tinted fills for destructive
  and confirm.
- **StatusBadge**: capsule, tinted fill, glyph + enum text.
- **Notice**: tinted fill, round glyph in the tone colour, title in semibold, body in ink.
- **Stat**: gray tile, muted label, 22px semibold tabular value.
- **Table**: white, 1px inset hairline ring, 12px radius, muted header, hairline rows, scrolls
  sideways inside itself.
- **Disclosure**: gray row with a chevron that turns 90°.
- **CodeBox**: #1D1D1F panel, 12px radius, 12.5px mono.
- **Diagrams**: state machines as rounded tinted boxes with muted arrows; "no code path"
  states dashed. Charts use the system palette with hairline gridlines.

## Rules: do

- Use the tokens (`bg-surface`, `text-muted`, `tone-green`, `var(--hairline)`), never raw hex
  in components.
- Keep every status as glyph + text + colour.
- Keep 36px minimum control height (44px rows on touch) and a visible focus ring.
- Test light, dark, 390px, reduced motion, reduced transparency.

## Rules: don't

- No black borders, hard offset shadows, drop shadows on cards or gradients.
- No `uppercase` or `font-black` on authored text.
- No second tint colour; no status colour used for decoration.
- No glass on content, only on the nav.
- No animation that runs without a real event behind it.

## Quality gates

- `npm run build`, `npx tsc --noEmit` and `npm run lint` clean.
- No horizontal page scroll at 390px; tables and the Lab nav scroll inside themselves.
- E2E text assertions unchanged (`e2e/*.spec.ts`), e.g. "live: SSE", "Sandbox health".
