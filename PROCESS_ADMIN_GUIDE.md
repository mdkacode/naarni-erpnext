# Creating a Process — Admin Guide

How to build a checklist that operators run on the phone, using Frappe Desk only.
No code, no developer.

The app ships a **renderer**, not a screen per checklist. Whatever you author here
is what appears on the phone — publishing a new process needs no app release.

---

## The shape of a process

```
Process Definition          ← the whole document, versioned
  └── Stages                ← "Before Installation", "After Installation"
        └── Steps           ← the individual checks
              └── Outcome Set   ← Pass / Fail / N-A (shared, reusable)
```

**One rule to know before you start: a Published process cannot be edited.**
Runs point at the exact version they were performed against, so a certificate
printed next year shows the checklist that actually applied. To change a live
process you clone it to a new version — covered at the end.

---

## Step 1 — Create the Process Definition

**Process Definition → New**

| Field | What to put | Why it matters |
|---|---|---|
| **Process Code** | `BATTERY_QC-v1` | Becomes the record name. Convention: `FAMILY-v1`. |
| **Process Name** | Battery Assembly QC | What the operator sees in the app list. |
| **Family** | `BATTERY_QC` | Ties all versions together. **Leave the version number out.** |
| **Subject Label** | Battery Pack | The app says "Scan the **Battery Pack** label". |
| **Identifier Mode** | Scan QR | Or Manual Entry / Auto Generate / Pick From List. |
| **Stage Label** | Module | What you call a stage — shown as "2 **modules**". |
| **Icon** | 🔋 | One emoji, shown in the process list. |
| **Expected Minutes** | 30 | Sets expectations; never enforced. |

### Allowed Roles

Add **Process Operator** under *Allowed Roles*.

> **This is the single most common reason a process appears broken.** A published
> process whose role nobody holds shows up as *"No processes yet"* on every phone,
> with no error to explain it. Leaving Allowed Roles empty means "anyone holding
> Process Operator" — naming roles narrows it further.

---

## Step 2 — Add the Stages

In the **Stages** table:

| Field | What to put |
|---|---|
| **Stage Code** | `BEFORE_INSTALL` — uppercase, no spaces |
| **Label** | Before Installation |
| **Sequence** | 10, 20, 30… (leave gaps so you can insert later) |
| **Screen Grouping** | **One Per Screen** |
| **Signoff Role** | Who submits the stage |

### Screen Grouping — the setting that decides the whole feel

| Option | What the operator sees |
|---|---|
| **One Per Screen** | One big question filling the screen, answers under the thumb, camera on every step. **Use this.** |
| By Section | All the checks in a section on one screen, as small cards. No camera. |
| Fixed Count | N checks per screen (set *Steps Per Screen*). |
| Single Screen | The whole stage as one long list. |

> Two things to know. First, an oversized checklist is the documented cause of
> box-ticking without doing the work — one question at a time is the main defence.
> Second, **the photo affordance only exists in the one-per-screen layout.** If a
> stage is grouped any other way, operators cannot attach photos to its steps at all.

---

## Step 3 — Create an Outcome Set (once, then reuse)

**Process Outcome Set → New**

| Field | Value |
|---|---|
| Set Code | `PASS_FAIL_NA` |
| Set Name | Pass / Fail / N-A |

Then add the options:

| Value | Label | Is Pass | Is Critical | Requires Remark | Colour |
|---|---|---|---|---|---|
| Pass | Pass | ✅ | | | green |
| Fail | Fail | | ✅ | ✅ | red |
| NA | N/A | ✅ | | ✅ | grey |

> **The verdict comes from `Is Pass`, never from the word.** You can label an
> option "Acceptable", "ठीक है" or "OK" and the engine still scores it correctly,
> because the meaning lives in the checkbox. This is also how "N/A counts as a
> pass" is expressed — a policy decision, not a code change.

Build this once and point every Pass/Fail step at it.

---

## Step 4 — Add the Steps

In the **Steps** table on the Definition. One row per check.

### Every step needs

| Field | What to put |
|---|---|
| **Step Code** | `BEF_01` — unique, permanent, never reused |
| **Stage** | `BEFORE_INSTALL` — must match a Stage Code exactly |
| **Sequence** | 10, 20, 30… |
| **Display No** | `1` — the number on the paper sheet, shown to the operator |
| **Section** | Bottom Cooling Plate — groups related checks |
| **Label** | The question, in plain language |
| **Response Type** | See the table below |

### Choosing a Response Type

| Type | Use it for | Also fill in |
|---|---|---|
| **Choice** | Pass / Fail / N-A | *Outcome Set* |
| **Yes No** | "Bolt head marked after torquing?" | *Outcome Set* (or leave for Yes/No default) |
| **Number with Tolerance** | Torque: 10 ± 1 Nm | *Nominal Value*, *Tolerance*, *Unit* |
| **Number in Range** | Insulation resistance > 2 MΩ | *Min Value* / *Max Value*, *Pass Condition* |
| **Number** | A reading with no pass/fail | *Unit* |
| **Computed** | Max − Min of earlier steps | *Computed Expression* |
| **Scan** | Read a QR or barcode | *Scan Entity Type*, *Scan Count* |
| **Text Short / Long** | Serial numbers, notes | — |
| **Photo Only** | Evidence with no question | Set *Requires Photo* |
| **Signature** | Operator's attestation at the end | — |
| **Section Note** | A heading or instruction, not a question | — |

> **For numeric steps, always set *Pass Condition*.** A zero bound is
> indistinguishable from an unset one, so `Pass Condition` is what tells the engine
> whether Min, Max or Nominal are the operands. Get it wrong and the band shown to
> the operator is wrong too.

### Evidence and severity

| Field | Effect |
|---|---|
| **Requires Photo** + **Photo Policy** | `Always` demands one; `On Fail` demands one only when a failing option is chosen — so the defect is photographed while the operator is still in front of it. |
| **Photo Hint** | "Show the full weld line" — shown under the camera button. |
| **Is Critical** | A failure here quarantines the whole run, whatever the score. |
| **Is Mandatory** | Blocks stage submit until answered. **The only hard block in the engine.** |
| **Allow Skip** + **Skip Reasons** | One reason per line. The reason lands on the report. |
| **Weight** | Relative scoring weight. Leave at 1 unless a check genuinely matters more. |

> Photos are offered on **every** step in the one-per-screen layout, and required
> automatically once a Pass/Fail verdict is marked. `Requires Photo` decides whether
> it is *demanded*, not whether it is *possible*.

---

## Step 5 — Scoring

On the Definition:

| Field | Suggested | Meaning |
|---|---|---|
| **Scoring Enabled** | ✅ | Off = pass/fail only, no percentage. |
| **Scoring Mode** | Weighted | Or Simple, where every step counts the same. |
| **Pass Threshold %** | 100 | Below this the run does not pass. |
| **Critical Fails Allowed** | 0 | Almost always zero. |
| **Skipped Steps Count As** | Excluded | Excluded from both sides of the fraction — or `Fail` if skipping should hurt. |

---

## Step 6 — Scan Entity Types *(only if you scan)*

**Process Entity Type → New**

| Field | Example |
|---|---|
| Entity Code | `MODULE` |
| Label | Battery Module |
| QR Pattern | A regex with named groups: `(?P<serial>...)` |
| Sample Payload | A real string off a real label |
| Duplicate Policy | Warn / Block / Ignore |

Use **Test QR Pattern** to confirm the pattern works *before* saving. Checking at
the station is the expensive way to find out.

> A payload no pattern matches is still stored, never rejected. A shortfall shows
> up as traceability completeness on the report — it never blocks a submit.

---

## Step 7 — Lint, then Publish

1. Click **Lint** on the Definition.
2. Fix everything marked **Error**. Warnings are advisory.
3. Click **Publish**.

Lint blocks publishing on: no steps, no stages, unreachable steps (a condition
referring to a later step), a Scan step with no entity type, a Link step with no
doctype, and a Computed step with no expression.

**Publishing retires the previous version automatically**, so the whole floor
moves in one step and nobody is left on the old checklist.

---

## Step 8 — Check it on a phone

Confirm all three, in order:

1. **Status is Published** — a Draft is invisible to the app.
2. **Operators hold `Process Operator`** — User → Roles.
3. **Screen Grouping is One Per Screen** on every stage.

Then open the app → **Battery** tab. The process should be listed. If an operator
already had the app open, they must **force-stop and reopen it** — definitions are
cached in memory for the session.

> The **Battery tab only appears** for someone holding one of the process roles.
> If a person cannot see the tab at all, that is the role, not the process.

---

## Reviewing what operators did

**Inspection Review** (search it in the awesomebar, or `/app/inspection-review`).

Filter by date range, operator, status or serial; click any row for the full
report — every answer as the operator gave it, with the photos taken at each
step and the GPS fix underneath.

Open to **System Manager, Process Author, Process Verifier and Process Viewer**.
Not to operators, and that is the point of it:

> **Each operator sees only their own inspections.** In the app, in the Desk list
> and through the API — an operator cannot open, and cannot write into, anyone
> else's run. Supervisors see everything, because verifying a run means reading
> it. If someone reports that an inspection "disappeared", check who started it
> before assuming data loss.

A practical consequence: **an inspection cannot be handed over mid-run.** If an
operator starts a pack and goes off shift, the person taking over starts their
own run against that pack. Two short runs with the right names on them are worth
more than one run with the wrong name on it.

---

## Changing a process that is already live

You cannot edit it. Open the Definition → **Clone to New Version**.

That creates `FAMILY-v2` as a **Draft** with everything copied. Edit the draft,
lint it, publish it — v1 retires automatically. Runs performed against v1 keep
pointing at v1 and still print correctly.

Only three fields stay editable on a published process: *Status*, *Effective From*
and *Default Brand*. They change neither what was asked nor how it was judged,
which is the test for whether something may move under a completed run.

---

## When something looks broken

| What you see | What it is |
|---|---|
| **"No processes yet"** on every phone | Nobody holds `Process Operator`, or the process is still Draft. |
| **Several questions on one screen, no camera** | Stage *Screen Grouping* is not `One Per Screen`. |
| **Publish refuses** | Lint has Errors. Read them — each names the step. |
| **A new step shows "needs a newer version of the app"** | The step type is newer than the installed app build. Update the app. |
| **The process list is stale after an admin change** | Force-stop and reopen the app; definitions are cached per session. |
| **A number step shows the wrong accepted band** | *Pass Condition* is not set, so the engine cannot tell which bounds are operands. |
| **No Battery tab at all for one person** | They hold none of the process roles. |
| **An operator says their inspection "vanished"** | Someone else started it. Each operator sees only their own — check Inspection Review. |
| **"You do not have access to the inspection overview"** | Inspection Review needs a review role; `Process Operator` is not one. |

---

## The principles behind the design

Worth knowing, because they explain why some things are deliberately not
configurable:

- **The server judges, never the phone.** The app posts the raw answer; the engine
  decides pass or fail. That is what makes a quarantine impossible to dodge.
- **There is no "mark all as pass".** It costs an honest operator a few taps and is
  the single highest-leverage thing the design does for data quality.
- **Almost nothing blocks.** Missing photos, missing scans and blank checks are all
  recorded and reported, never refused. The one exception is an unanswered
  *mandatory* step at stage submit. A hard block on a plant floor does not produce
  the missing answer — it produces a guess.
- **Every photo is stamped** with the serial, the GPS fix, the time and the
  operator's name, burnt into the pixels. An un-stamped photo of a battery pack is
  evidence of nothing, because there is no way to tell which pack, when, or who.
