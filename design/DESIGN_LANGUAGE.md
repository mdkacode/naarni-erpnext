# NaArNi Design Language (NDL)

**Version 1.0** · Owner: Platform · Applies to: Service Web (Vue 3 + Tailwind), Service Engineer App (Kotlin + Compose), and every surface added later.

> This document is normative. If code and this document disagree, the code is wrong.
> Tokens are defined **once** here and mirrored mechanically into
> [`tokens.css`](../vehicle_maintenance/frontend/src/styles/tokens.css) and
> [`Tokens.kt`](../android-app/app/src/main/java/com/naarni/service/ui/theme/Tokens.kt).

---

## 0. Why this exists

NaArNi is used by people who are **at work**: a technician with gloves on in a depot at
6am, a depot manager reconciling forty job cards before a shift handover, a customer
checking whether their bus is back on the road. None of them are browsing. Every one of
them is trying to finish something and leave.

An interface for that reader is not the same artefact as a landing page. It has to be
**dense** (more facts per screen means fewer screens), **quiet** (colour is a signal, and
a screen that is all signal has none), and **utterly predictable** (the same fact must
look the same everywhere, or the reader has to re-learn the screen each visit).

The two NaArNi surfaces had drifted apart and, on the web, drifted toward the visual
defaults of generated UI. Section 2 names those defaults explicitly so they can be
argued with rather than absorbed.

---

## 1. Principles

**1. Neutral carries, accent points.**
A true-grey ramp carries every surface, border and body text. The indigo accent appears
only on things you press and things that are live. *Anything indigo is something to act
on.* Aim for **one filled accent element per screen**; anything else that must look
pressable goes tonal or outlined.

**2. Hairlines, not shadows.**
Structure is communicated by a 1px border and a change of ground, not by a drop shadow.
Shadows are reserved for surfaces that genuinely float above the page — menus, dialogs,
toasts. A shadow on a static card is decoration, and decoration on a work screen is noise.

**3. Density is a feature.**
A 36px table row is not "cramped", it is forty rows instead of twenty-two. Whitespace is
spent on separating *groups*, not on padding every individual element. Where a reader is
scanning, tighten. Where a reader is deciding, loosen.

**4. Five meanings, not fifteen colours.**
Status, priority, severity and SLA all answer the same question: *is this fine, moving,
waiting, broken, or not started?* They share one semantic ramp. A reader should never
need a legend.

**5. Type does the hierarchy.**
Weight and size separate a title from its metadata. Colour is not a hierarchy tool —
`onSurface` and `onSurfaceVariant` are the only two text colours on a normal screen.

**6. The same fact looks the same everywhere.**
A registration number, a job card ID, a money amount, a timestamp, a status — each has
exactly one rendering, defined in §7, implemented once as a component, used everywhere.

---

## 2. What "AI-generated" looks like, and what we do instead

This is a checklist, not a rant. Every row below was found in the NaArNi codebase.

| Tell | Why it reads as generated | NDL rule |
|---|---|---|
| `bg-gradient-to-br from-brand-500 to-brand-700` stat tiles | Gradients carry no information; four of them make four competing focal points and force white text | Stat tiles are `--ndl-surface` with a hairline. Gradients exist in **one** place: the sign-in hero |
| Emoji as icons (`📞`, `🚗`, `💰`) | Renders differently on every OS, cannot be tinted, cannot be sized, reads as a chat message | One icon set (Lucide / Material Rounded), one stroke weight, tinted with a text token |
| Eight named status hues (indigo / amber / violet / pink / cyan / orange / green / red) | Demands a legend; two states that mean the same thing arrive in different colours | Five semantics (§3.3) |
| `rounded-2xl` / `rounded-3xl` on data containers | Consumer-app softness; large radii waste horizontal space and blur the grid | 6 / 8 / 12px (§3.6). 16px+ only on full-bleed sheets |
| `hover:shadow-lg` on every card | Motion and depth without meaning; janky on long lists | Hover changes **ground**, not elevation |
| Centred grey `Loading…` text | Layout jumps when content arrives | Skeletons that match the shape of the content (§6.10) |
| `text-3xl font-extrabold` on every number | Everything shouts, so nothing is emphasised | One display size per screen, on the one figure that matters |
| Colour-coded text as the only signal (`text-red-500` priority) | Fails for colour-blind readers and in sunlight | Colour **plus** a shape or a word: pill, icon, or label |
| Full-width `max-w-7xl mx-auto` centred column on an ops screen | Wastes a 27" monitor; the reader paid for those pixels | Ops surfaces are full-bleed with a fixed sidebar. Reading surfaces cap at 72ch |
| Every page re-implements its own header, its own currency formatter, its own date formatter | Guarantees drift | `PageHeader`, `fmt.*` (§7) |

---

## 3. Foundations

### 3.1 The neutral ramp

A **true grey**, not a blue-tinted slate. On a handset held outdoors, a tinted grey reads
as a colour cast; next to a genuinely coloured status chip it reads as a second competing
hue. Steps are spaced so that any two adjacent steps form a visible boundary without a
border, and any two steps apart survive a sunlit screen.

| Token | Hex | Role |
|---|---|---|
| `n-0`   | `#FFFFFF` | Raised surface (light) |
| `n-50`  | `#FAFAFA` | Canvas (light) |
| `n-100` | `#F4F4F5` | Sunken — fields, wells, table headers (light) |
| `n-200` | `#E4E4E7` | Hairline (light) |
| `n-300` | `#D4D4D8` | Control border (light) |
| `n-400` | `#A1A1AA` | Muted text (dark), disabled text (light) |
| `n-500` | `#71717A` | Muted text (light) |
| `n-700` | `#3F3F46` | Hairline (dark) |
| `n-800` | `#27272A` | Sunken / control border (dark) |
| `n-850` | `#1C1C21` | Sunken surface (dark) |
| `n-900` | `#141418` | Raised surface (dark) |
| `n-950` | `#0B0B0F` | Canvas (dark) — "ink" |

### 3.2 The accent

One hue. `#4F46E5`.

| Token | Light | Dark | Use |
|---|---|---|---|
| `accent`       | `#4F46E5` | `#8B85F5` | Primary buttons, active nav, focus rings, links |
| `accent-hover` | `#4338CA` | `#A5A0F8` | Hover only |
| `accent-soft`  | `#E8E7FD` | `#2E2A6B` | Tonal button ground, selected row, active chip |
| `accent-on`    | `#FFFFFF` | `#1E1B4B` | Text on a filled accent |
| `accent-ink`   | `#3730A3` | `#C7C4FB` | Text on `accent-soft` |

**Rules.** Accent never grounds a large area (no accent page headers, no accent cards).
Accent is never used to mean "good" — that is `positive`. There is no secondary brand
hue; a second hue is just a second thing competing for the eye.

### 3.3 Semantics — five meanings

| Semantic | Meaning | Light | Dark | Examples |
|---|---|---|---|---|
| `positive` | Settled, done, healthy | `#15803D` | `#4ADE80` | Closed, Pass, health ≥ 80, SLA OK |
| `caution`  | Waiting on someone; needs an eye, not now | `#B45309` | `#FBBF24` | Awaiting Parts, Awaiting Approval, High priority |
| `critical` | Blocked, breached, failed | `#B91C1C` | `#F87171` | SLA Breached, Fail, Urgent, Reopened |
| `active`   | Live work, in flight — the only semantic that borrows the accent | `#4F46E5` | `#8B85F5` | WIP, Open, In Progress, syncing |
| `idle`     | Nothing has happened yet — an absence of state, not a state | `#71717A` | `#A1A1AA` | Draft, Not started, no data |

Presence (`online`, `#059669` / `#34D399`) exists only for presence dots.

**Categorical colour — the one exception.** Identity is the single job a semantic ramp
cannot do: there is no sense in which one participant in a group thread is more
"positive" than another. `AuthorPalette` (six hues, assigned by a stable hash of the
user id, authored per theme) therefore exists — for **author names only**. Never a
bubble ground, never a status, never an avatar fill; avatars are neutral. If a future
surface needs categorical colour again (chart series, depot keys), it extends that
palette rather than inventing a second one.

**Two surfaces sit outside the theme**, and only these two: a **legibility scrim**
(transparent → black behind text over a photo) is a function, not a colour choice; and
a **camera overlay** — the recording dot — paints on a live preview that is neither
canvas, and its red is a near-universal signal that a dark-theme-softened red would
weaken. Both are named constants with the reason written next to them.

**Tint.** A semantic chip's ground is the semantic at `12%` alpha (light) / `18%` (dark).
Never invent an alpha at the call site — use `semantic.tint()` / `--ndl-<sem>-tint`.

**The mapping is centralised.** Every workflow state, priority, severity and score maps
to one of the five in exactly one place per platform (`ui/semantic.js`, `UiKit.kt`). Adding
a workflow state means adding a row there, not picking a colour in a template.

| Domain value | Semantic |
|---|---|
| Closed, Parts Fitted (verified), Pass, Low priority | `positive` |
| Awaiting Customer Approval, Awaiting Parts, Verification Pending, High / Medium priority, Warning | `caution` |
| SLA Breached, Reopened, Fail, Urgent / Critical | `critical` |
| Open, WIP, Parts Fitted, Closure from Technician, syncing | `active` |
| Draft, unknown, empty | `idle` |

### 3.4 Surfaces

Material's single `surface` + `surfaceVariant` cannot express a card on a canvas with a
field inset inside it. Three grounds are named instead:

| Token | Light | Dark | Use |
|---|---|---|---|
| `canvas`  | `n-50`  | `n-950` | The page behind everything |
| `raised`  | `n-0`   | `n-900` | Cards, sheets, table bodies, the sidebar |
| `sunken`  | `n-100` | `n-850` | Inputs, wells, table headers, avatars, icon slots |
| `hairline`| `n-200` | `n-800` | Separators. **Lighter** than `border` |
| `border`  | `n-300` | `n-800` | Control outlines — inputs, secondary buttons |
| `overlay` | `rgba(0,0,0,.45)` | `rgba(0,0,0,.65)` | Scrim behind dialogs |

**Never use tonal elevation.** Material composites `surfaceTint` (the accent) over a
surface, so any Compose card with `tonalElevation > 0` comes out tinted lavender under a
single-accent scheme. Use `raised` + a 1px `hairline` border.

### 3.5 Type

**Family.** `Inter` (web, variable, `-apple-system` fallback stack), the platform sans on
Android. **Numerals are tabular everywhere a number can change or be compared** — tables,
stats, currency, odometer, counts, timers. A proportional `1` in a column of figures makes
the column ragged and the comparison slower.

| Token | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|
| `display`   | 28 / 32 | 700 | −0.02em | The one hero figure on a screen |
| `title-lg`  | 20 / 26 | 650 | −0.015em | Page title |
| `title`     | 16 / 22 | 600 | −0.01em | Card / section title, dialog title |
| `title-sm`  | 14 / 20 | 600 | −0.005em | Row primary text, sub-section |
| `body`      | 14 / 20 | 400 | 0 | Default reading text |
| `body-sm`   | 13 / 18 | 400 | 0 | Table cells, dense rows, metadata |
| `label`     | 13 / 16 | 550 | 0 | Buttons, tabs, field labels |
| `label-sm`  | 12 / 16 | 550 | 0 | Chips, badges, table headers |
| `caption`   | 11 / 14 | 500 | +0.02em | Timestamps, helper text, units |
| `mono`      | 12 / 16 | 500 | 0 | IDs, registration numbers, codes |

**Casing.** Sentence case for everything a person reads. `UPPERCASE` is permitted only on
`label-sm` section headers and table headers, where its job is to sit *below* the content
in the hierarchy without needing another colour. Never uppercase a value.

**Colour.** Exactly two on a normal screen: `text` (`n-950` / `n-50`) and `text-muted`
(`n-500` / `n-400`). A third, `text-subtle` (`n-400` / `n-500`), exists for placeholders
and disabled states. Anything else is a semantic and must mean something.

### 3.6 Space, radius, stroke

**Space** is a 4px base. `0 · 2 · 4 · 6 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48 · 64`.
Nothing between the steps. Gaps *inside* a component come from the lower half of the
scale; gaps *between* sections come from the upper half. That contrast is what makes
groups read as groups without a box around each one.

**Radius.**

| Token | Value | Use |
|---|---|---|
| `radius-xs` | 4px | Checkbox, tiny swatch |
| `radius-sm` | 6px | Inputs, buttons, chips, table row hover |
| `radius-md` | 8px | Cards, panels, menus, list rows |
| `radius-lg` | 12px | Dialogs, sheets, the outermost container of a card group |
| `radius-xl` | 16px | Full-bleed bottom sheets, the sign-in hero mark (mobile only) |
| `radius-full` | 9999px | Pills, avatars, presence dots |

**Stroke** is `1px` everywhere. A 2px border is a state (focus, selection, drag target),
never decoration. Focus is a 2px `accent` ring at 2px offset — never a colour change alone,
and never `outline: none`.

**Elevation.** Four levels, and three of them float.

| Token | Shadow | Use |
|---|---|---|
| `e0` | none | Everything static. Cards, tiles, tables, the sidebar |
| `e1` | `0 1px 2px rgba(0,0,0,.06)` | Sticky headers once the page has scrolled |
| `e2` | `0 4px 12px rgba(0,0,0,.10)` | Dropdowns, popovers, tooltips |
| `e3` | `0 16px 40px rgba(0,0,0,.18)` | Dialogs, drawers, toasts |

On the dark canvas a shadow has nothing to fall on; dark mode substitutes a lighter
`raised` ground plus a hairline for `e1`/`e2`.

### 3.7 Density

One language, two densities. Ops/desk surfaces run **compact**; field and customer
surfaces run **comfortable**. The density switch changes only these five values.

| | compact | comfortable |
|---|---|---|
| Table / list row height | 36px | 44px (56dp on mobile) |
| Control height | 30px | 36px (48dp on mobile) |
| Horizontal cell padding | 10px | 14px |
| Vertical stack gap | 8px | 12px |
| Body size | `body-sm` | `body` |

**Touch overrides density.** Any tap target on a handset is at least **48×48dp**
regardless of its painted size — a 32dp icon button gets a 48dp touch box. In the field,
gloves are a real input device.

### 3.8 Motion

Motion exists to explain a change of state, never to decorate.

| Token | Duration | Curve | Use |
|---|---|---|---|
| `motion-instant` | 90ms | `ease-out` | Hover, press, focus ring |
| `motion-fast` | 150ms | `cubic-bezier(.2,0,0,1)` | Chips, tooltips, checkboxes, colour changes |
| `motion-base` | 220ms | `cubic-bezier(.2,0,0,1)` | Menus, dialogs, sheets, expand/collapse |
| `motion-slow` | 320ms | `cubic-bezier(.2,0,0,1)` | Page/route transitions, drawer |

Animate `opacity` and `transform` only. Never animate layout (`height`, `width`, `top`)
on a list. Honour `prefers-reduced-motion` / `Settings.Global.ANIMATOR_DURATION_SCALE`:
under it, transitions become instant, skeleton pulses become static, and nothing loops.

### 3.9 Iconography

**Web:** Lucide at stroke **1.75** in a 24 viewBox — Lucide's own 2 reads heavy next
to a 550-weight label, and 1.75 renders at ~1.2px on a 16px icon, level with the type
rather than shouting over it. **Android:** Material Symbols **Rounded**. Never mix sets,
never mix Filled/Outlined/Default within a screen.

Sizes: `14` (inline with `body-sm`), `16` (default, buttons and rows), `20` (page header,
nav), `24` (empty states, mobile primary actions). Icons take a **text colour token**, not
a semantic, unless the icon *is* the status indicator.

An icon never appears alone on a control a person must identify — it carries a label, or a
tooltip, or both. Decorative icons are `aria-hidden`.

---

## 4. Layout

### 4.1 The shell

Ops and internal surfaces use a **fixed left sidebar** (240px, `raised`, hairline right
edge) and a full-bleed content area. No centred `max-w-7xl` column: an ops user has a wide
monitor and a lot of rows.

- **Sidebar:** brand mark + product name (48px band) → nav groups → user block pinned to
  the bottom. The active item is `accent-soft` ground + `accent` text; nothing else in the
  sidebar is coloured.
- **Content:** `PageHeader` (title, optional breadcrumb/back, right-aligned actions,
  optional toolbar row) → page body at `space-20` horizontal padding.
- **Collapse:** below `1024px` the sidebar becomes a slide-over drawer; below `640px` the
  page header actions collapse into an overflow menu.

Customer-facing and long-form reading surfaces are the exception: a centred column, max
`72ch` of text, comfortable density.

### 4.2 The grid

12 columns, `16px` gutters, no fixed page max-width. Cards in a group are equal height;
ragged card bottoms are the fastest way to make a dashboard look unfinished.

### 4.3 Page anatomy, top to bottom

```
┌ Sidebar ┬ PageHeader ──────────────────────────────────────────┐
│         │  ‹ back   Title              [secondary] [primary]   │
│         ├─ Toolbar ────────────────────────────────────────────┤
│         │  [search]  [filter] [filter]        [density] [view] │
│         ├──────────────────────────────────────────────────────┤
│         │  Content                                             │
└─────────┴──────────────────────────────────────────────────────┘
```

The primary action lives in the page header, right-aligned, and there is **exactly one**.
It never also appears as a floating button.

---

## 5. Components

Every component below has one implementation per platform. Building a variant in a page
file is a bug.

### 5.1 Button

| Variant | Ground | Text | Border | Use |
|---|---|---|---|---|
| `primary` | `accent` | `accent-on` | none | The one committing action |
| `secondary` | `raised` | `text` | `border` | Everything else |
| `tonal` | `accent-soft` | `accent-ink` | none | Secondary action that is still the accent's business |
| `ghost` | transparent | `text-muted` | none | Toolbar and row actions |
| `danger` | `critical` | white | none | Destructive, and only after a confirm |

Sizes `sm` 26px · `md` 30px (compact) / 36px (comfortable) · `lg` 44px (mobile primary).
Radius `sm`. Label is `label`, sentence case, a verb: *Save changes*, not *Submit*.
Loading state keeps the button's width and replaces the label with a spinner — a button
that resizes mid-click moves the thing under the cursor.

Disabled buttons must be explainable: if it is disabled, a tooltip or inline hint says why.

### 5.2 Badge / Status pill

`radius-full`, `label-sm`, semantic text on `semantic.tint()`. Horizontal padding 8px,
vertical 2px (compact) / 3px. A **1.5px leading dot** in the same colour carries the
meaning for readers who cannot separate the hues.

Status text is the **domain word**, humanised, never a raw enum: `WIP` renders as
*In progress*.

### 5.3 Card

`raised`, `radius-md`, 1px `hairline`, `e0`. Padding 16px (comfortable) / 12px (compact).
Optional header row: `title-sm` left, actions right, hairline beneath.
Hover on an interactive card changes the ground to `sunken` — not the shadow.

### 5.4 Stat tile

A card whose content is: a `label-sm` muted label, a `display` figure with tabular
numerals, and an optional `caption` delta. **The figure is the content; the icon, if any,
sits quiet in a `sunken` 28px slot.** A stat's number is coloured only when the number
itself is the alarm (a breach count > 0), never by category.

### 5.5 Table

The workhorse. Header: `sunken` ground, `label-sm` uppercase muted, sticky on scroll.
Rows: `hairline` bottom border, hover `sunken`, row height per density. Cells: `body-sm`,
vertically centred, `10px` horizontal padding.

- **Numbers, dates and money are right-aligned and tabular.** Text is left-aligned.
- The first column is the record's identity (ID or registration) in `mono`.
- Actions live in a trailing column, ghost icon buttons, revealed on row hover but always
  present for keyboard and touch.
- A whole row is clickable only if there is also a visible affordance (the ID reads as a
  link).
- Sorting: one indicator, in the header, on the sorted column only.
- Empty: an `EmptyState` inside the table body, spanning all columns — never a bare
  "No records".

### 5.6 Field

Label above (`label`, `text`), control, then **one** line beneath: helper (`caption`,
`text-muted`) or error (`caption`, `critical`), never both. Required is marked on the
label; optional is not marked — most fields are required, so mark the exception.

Inputs: `sunken` ground, 1px `border`, `radius-sm`, control height per density, `body`
text. Focus is the 2px accent ring. Errors change the border to `critical` **and** show
the message — never colour alone.

Per the platform's field rules: **prefer a searchable select over free text** wherever a
value comes from a known set, and pre-fill anything derivable from context.

### 5.7 Dialog

`raised`, `radius-lg`, `e3`, over an `overlay` scrim. Max width 480px (confirm) / 640px
(form). Title `title`, body `body`, actions bottom-right with the primary last.
Destructive confirms name the object — *Delete job card JC-2026-00412?* — and the
destructive verb is on the `danger` button, never on "OK".

### 5.8 Empty state

`sunken` 56px icon slot with a **neutral** icon (an empty state is the absence of content;
colouring it made "nothing here" the most saturated thing on screen), `title-sm` headline,
`body-sm` muted explanation of one sentence, and at most one action. Three flavours:
*nothing yet* (offer the action), *nothing matched* (offer to clear filters),
*nothing for you* (explain the permission).

### 5.9 Skeleton

Shapes that match what is coming: a table skeleton is rows of bars at the real column
widths, not a spinner. Pulse is `onSurface` at 8–16% alpha, 800ms, `motion` respecting
reduced-motion. Skeletons appear only after **200ms** of loading — flashing a skeleton for
a 60ms response is worse than showing nothing.

### 5.10 Toast

Bottom-right (web) / above the nav bar (mobile), `raised`, `radius-md`, `e3`, hairline,
one line of `body-sm` with a leading semantic icon and an optional single action. Auto-
dismiss 5s, never for errors that need a decision. Never stack more than three.

### 5.11 Also specified

`Avatar` (initials on `sunken`, `radius-full`, presence dot bottom-right) ·
`Tabs` / `Segmented` (underline for page-level, `sunken` track for filters) ·
`Breadcrumb` · `Menu` (`e2`) · `Tooltip` (`e2`, `caption`, 500ms delay) ·
`Meter` (health/progress bar: `sunken` track, semantic fill, value as text beside — never
only inside — the bar) · `KeyValue` (definition rows: muted label left, value right,
hairline between) · `Timeline` (rail + dot per event) · `Wizard` (§6.4).

---

## 6. Patterns

**6.1 List → detail.** A list row shows identity, one status, and at most three metadata
facts. Everything else waits for the detail page. If a row needs a fourth fact, the wrong
three are on it.

**6.2 Detail page.** Header (identity + status + primary action) → a summary `KeyValue`
block → sections in the order the work happens, each a `Card`. Never a tab bar with one
tab's worth of content.

**6.3 Status display.** Always pill + word. Never colour alone, never an icon alone.
Where an SLA can breach, the breach is a `critical` pill next to the status pill, not a
replacement for it.

**6.4 Forms — Eliminate, Automate, Simplify.** Remove any field derivable from context;
pre-fill from the session, the vehicle, the last entry; then split what remains into steps
of **4–5 fields**. A step has one idea and its own heading. Progress is a stepper, and a
completed step is revisitable. Validate on blur, not on keystroke; block submission only
on the current step.

**6.5 Gates warn, they never block.** A hard block in the field does not produce the
missing photo — it produces a guess, because the operator has a vehicle in front of them
and a queue behind them. A gate raises **one** dialog with a prominent safe action that
*actually performs the action* ("Take the photo" opens the camera) and a quiet "Continue
anyway", plus a persistent `caution` strip carrying the same reason.

**6.6 Destructive actions** are never the default focus, are always confirmed by name, and
are always undoable for 5s via a toast where the backend permits it.

**6.7 Loading.** Skeleton for first paint · inline spinner inside the control for an
in-place action · a translucent scrim with a spinner for a blocking action, with the
content still visible behind it. Content never jumps: reserve the space.

**6.8 Errors.** Say what failed, in the reader's words, and what to do next. Field errors
inline; page errors as a `critical` panel where the content would have been, with a Retry;
transient failures as a toast. Never a raw traceback, never "Something went wrong".

**6.9 Offline / sync (field app).** A persistent, quiet status line — *Saved on device ·
syncing 3* — never a modal. Local writes always succeed; the queue is visible and
inspectable.

**6.10 Permissions.** If a role cannot use something, **omit it from the DOM**. Never hide
with CSS, never disable without explanation.

---

## 7. Content, numbers and voice

**Voice.** Plain, specific, second person, no exclamation marks, no "Oops". Buttons are
verbs. Titles are nouns. Never expose a field name (`registration_plate`), a doctype, or
a workflow enum to a non-admin.

**One rendering per fact:**

| Fact | Rendering | Example |
|---|---|---|
| Registration number | Grouped, `mono`, **last 4 characters heavier and accent-coloured** | `TN 01 AB **1234**` |
| Record ID | `mono`, `text-muted`, links to the record | `JC-2026-00412` |
| Money | `en-IN`, INR, no decimals; compact only in stat tiles | `₹1,24,500` · `₹1.2L` |
| Date | `12 Aug 2026`. Same year may drop the year. Never `08/12/26` |
| Time | 24-hour, `14:05`. With a date: `12 Aug, 14:05` |
| Relative time | Under 7 days only, then absolute | `4h ago`, then `12 Aug` |
| Duration | `4h 20m`, never `4.33 hrs` |
| Odometer / distance | Thousands-grouped + unit | `1,24,500 km` |
| Percentage | Integer + `%`, tabular | `82%` |
| Empty value | An em dash, `text-subtle` | `—` |
| Person | Display name; role in `caption` beneath where it disambiguates |

All of these live in `fmt` (`ui/format.js`, `ui/Format.kt`). A page that formats a date
itself is a bug.

---

## 8. Accessibility

Non-negotiable, and most of it falls out of the rules above.

- Text contrast ≥ **4.5:1** (≥ 3:1 for ≥ 19px semibold). Every token pair in §3 is checked
  against both canvases.
- Non-text contrast (borders, focus rings, icons that carry meaning) ≥ **3:1**.
- **Colour is never the only signal** — pair with a word, a dot, or an icon.
- Visible focus on every interactive element. Tab order follows reading order. Dialogs trap
  focus and restore it on close.
- Every control has an accessible name; icon-only controls carry a label.
- Tap targets ≥ 48×48dp on mobile, ≥ 24×24px with spacing on desktop.
- Respect reduced motion, and support 200% browser zoom without horizontal scrolling.
- Live regions announce toasts and async results.

---

## 9. Platform mapping

| NDL token | Web (CSS var / Tailwind) | Compose |
|---|---|---|
| `canvas` | `--ndl-canvas` / `bg-canvas` | `MaterialTheme.colorScheme.background` |
| `raised` | `--ndl-raised` / `bg-raised` | `AppSurface.raised` |
| `sunken` | `--ndl-sunken` / `bg-sunken` | `AppSurface.sunken` |
| `hairline` | `--ndl-hairline` / `border-hairline` | `AppSurface.hairline` |
| `border` | `--ndl-border` / `border-line` | `colorScheme.outline` |
| `text` / `text-muted` | `text-ink` / `text-muted` | `onSurface` / `onSurfaceVariant` |
| `accent` | `--ndl-accent` / `bg-accent` | `colorScheme.primary` |
| `accent-soft` | `--ndl-accent-soft` | `colorScheme.primaryContainer` |
| `positive`…`idle` | `--ndl-positive` / `text-positive` | `Semantic.positive` … |
| `radius-sm/md/lg` | `rounded-sm/md/lg` (remapped) | `Radii.sm/md/lg` |
| `space-*` | Tailwind spacing scale | `Space.*` |
| `motion-*` | `--ndl-motion-*` | `Motion.*` |
| `e0`…`e3` | `shadow-e0`…`shadow-e3` | `Elevation.e0`…`e3` (never `tonalElevation`) |
| `display` | `text-display` | `headlineLarge` / `displayLarge` |
| `title-lg` | `text-title-lg` | `titleLarge` / `headlineSmall` |
| `title` | `text-title` | `titleMedium` |
| `title-sm` | `text-title-sm` | `titleSmall` |
| `body` / `body-sm` | `text-body` / `text-body-sm` | `bodyLarge` / `bodyMedium` |
| `label` / `label-sm` | `text-label` / `text-label-sm` | `labelLarge` / `labelMedium` |
| `caption` | `text-caption` | `labelSmall` |

Compose has thirteen type roles and NDL has nine steps, so several roles land on the
same step. That is deliberate: a component should be unable to produce a size outside
the system, whichever role it happens to reach for.

The **Android app runs `comfortable` density throughout** — it is a field surface, held
one-handed, often with gloves. Web ops surfaces run `compact`; web customer, form and
long-form surfaces declare `comfortable` on their own root.

Tailwind's default `rounded-*` and `shadow-*` scales are **remapped** to NDL values in
`tailwind.config.js`, so an author who reaches for `rounded-lg` out of habit still lands
inside the system.

---

## 10. Governance

**Adding a token.** Tokens are added here first, then mirrored to `tokens.css` and
`Tokens.kt` in the same change. A hex literal in a component file is a review blocker.

**Where a colour may be authored.** Exactly four files: `src/styles/tokens.css` and
`src/ui/semantic.js` on the web, `ui/theme/Theme.kt` and `ui/theme/Tokens.kt` on Android.
A surface with genuinely surface-specific grounds may add a fifth — `ui/chat/ChatDesign.kt`
is the precedent, because a thread needs three grounds legible against each other
(canvas, incoming, outgoing) that no general scheme supplies — but it must be a named
token object with a written rationale, not hexes scattered through the screen.

**Adding a component.** It belongs in the kit if it appears on two screens or encodes a
rule from §5–§7. Ship it with: both themes, both densities, focus and disabled states,
an empty/loading state where it can have one, and an accessible name.

**Review checklist** — every UI change:

- [ ] No hex literal, no raw `rgb()`, outside the token files
- [ ] No gradient outside the sign-in hero
- [ ] No emoji standing in for an icon
- [ ] At most one filled accent element on the screen
- [ ] Status shown as pill + word, never colour alone
- [ ] Numbers tabular and right-aligned in tables; money/date/duration via `fmt`
- [ ] Hairline + ground change, not shadow, for static structure
- [ ] Radius from §3.6; no `rounded-2xl`/`3xl` on a data container
- [ ] Skeleton (not a "Loading…" string) for first paint; no layout jump
- [ ] Focus visible; tap targets ≥ 48dp; contrast checked in both themes
- [ ] Unauthorised UI omitted from the DOM, not hidden

**Versioning.** NDL is versioned with the repo. Breaking a token's meaning is a major
bump and requires a migration note here.
