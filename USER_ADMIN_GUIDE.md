# Creating a User — Admin Guide

How to give someone an account, using Frappe Desk only. No code, no developer.

---

## The one thing to understand first

**The phone number is the identity. The email address is not.**

Frappe insists every user record is named by an email, so the app synthesises one
(`9876543210@naarni.phone`) and nobody ever sees it or logs in with it. What people
type on the phone is their number, and what proves it is an OTP.

Two consequences that catch everyone out:

* **A phone number is mandatory and unique.** Saving a user without one is refused
  outright, and a number already on another account is refused by name.
* **Whatever you type is normalised to the last ten digits.** `+91 98765 43210`,
  `+919876543210`, `98765-43210` and `9876543210` are all stored as `9876543210` —
  the same person, however it was typed.

---

## Do you even need to create the user?

Often not. **The first time someone signs in with a phone the app has never seen, an
account is created for them automatically**, and they are given the **Service
Engineer** role.

| Situation | What to do |
|---|---|
| A Service Engineer, and someone will assign their depot afterwards | Let them log in. The account creates itself. |
| Anyone who needs a *different* role — Technician, Depot Manager, Process Operator, Central Ops | **Create them first.** Otherwise their first login silently makes them a Service Engineer, and you are correcting it afterwards instead of setting it. |
| Anyone who must never reach the system | Do nothing — but see *Removing access* below, because doing nothing is not the same as blocking. |

> The auto-created account is deliberately minimal: a phone, a name that is just the
> phone number, and one role. It is a starting point, not a finished user.

---

## Step 1 — Create the User

**User → New** (or `/app/user/new`)

| Field | What to put | Why it matters |
|---|---|---|
| **Email** | `9876543210@naarni.phone` | Follow the same pattern the app uses — the number, then `@naarni.phone`. It is never emailed and never typed by the user. |
| **First Name** | Their real name | This is what appears on their inspections, job cards and check-ins. Leave it as a phone number and every record they touch is signed with a phone number. |
| **Mobile No** | `9876543210` | **The actual login credential.** |
| **Send Welcome Email** | ☐ unticked | There is no real mailbox behind that address. |
| **User Type** | System User | Everyone in this app is a System User, including technicians. |

Save.

> If saving is refused with *"Phone number is already registered to user …"*, the
> person already has an account — most likely auto-created by an earlier login. Open
> **that** record and edit it. Do not make a second one.

---

## Step 2 — Give them their roles

On the User form, **Roles** section. The roles that matter day to day:

| Role | What it unlocks |
|---|---|
| **Service Engineer** | The default. Job cards, vehicles, duty check-in. |
| **Technician** | Executes job card work. Can check in. |
| **Depot Manager** | Runs the roster, publishes it, sees the attendance board. Can check in. |
| **Central Ops** | Cross-depot oversight. |
| **Process Operator** | **Makes the Battery tab appear.** Runs inspections. |
| **Process Author** | Writes and publishes process definitions. Privileged — a bad publish reaches every phone on the floor. |
| **Process Verifier** / **Process Viewer** | Reads other people's inspections in Inspection Review. |
| **Fleet Reports** | View-only access to the KM & SLA reports. |
| **KM Checker L1 / L2** | The two-level sign-off before a KM report emails the customer. |

Two rules worth knowing:

> **A tab that is missing is almost always a missing role, not a broken app.** The
> Battery tab only exists for someone holding a process role. If a person cannot see
> the tab at all, check Roles before anything else.

> **Roles are additive and nothing is taken away automatically.** Promoting a
> technician to Depot Manager does not remove Technician. Untick what no longer
> applies, or they keep both sets of powers.

---

## Step 3 — Put them on a depot

**This is the step people forget, and its symptom looks like a bug.**

Open **Depot → the depot → Assigned Service Engineers**, and add the user.

Without it:

* they cannot be put on a duty roster — **Fill Roster** picks engineers from the
  depot's roll, and an empty roll produces *"No engineers at this depot"*;
* their check-in has no depot to measure distance from.

One person can be on more than one depot.

---

## Step 4 — Let them check in *(only if they do duty)*

**Roster Settings → Punch Roles** lists the roles allowed to check in. Out of the box
that is **Service Engineer, Technician and Depot Manager**.

Someone whose role is not on that list gets a clear refusal when they try:

> *Your role is not enabled for check-in. Ask your Depot Manager to add it in Roster
> Settings.*

Add the role there rather than handing out Service Engineer to make check-in work —
that grants far more than intended.

### What check-in now requires

Check-in is **geofenced at 100 metres** and **a location fix is mandatory**. A punch
from outside the radius is refused, naming the distance:

> *You are 340 m from Mumbai North Depot — outside its 100 m radius.*

Two things follow, both worth telling people before they hit them:

> **A phone with location denied cannot check in at all.** That is deliberate: without
> a fix there is nothing to measure, and accepting the punch anyway would let anyone
> bypass the geofence by turning GPS off. Make sure location permission is granted at
> install time.

> **A depot with no Latitude/Longitude enforces nothing.** There is nothing to measure
> against, so check-in there is accepted from anywhere while appearing enforced. If
> the geofence seems not to be working, check the Depot record's coordinates first —
> that is the cause far more often than the setting.

---

## Step 5 — Check it worked

Ask them to open the app and sign in with their number.

If something is wrong, work down this list in order — it is ordered by how often each
one is actually the cause:

1. **Is the phone number right on the User record?** Last ten digits, no country code.
2. **Do they hold the role for what they are missing?** No Process Operator → no
   Battery tab.
3. **Are they on the depot's Assigned Service Engineers?** No → no roster, no duty.
4. **Is their role in Roster Settings → Punch Roles?** No → check-in is refused.
5. **Is the account enabled?** A disabled account fails login with *"This account is
   disabled."*
6. **Has the depot got coordinates?** No → the geofence silently allows everything.

> If they already had the app open, they must **force-stop and reopen it**. Roles and
> definitions are cached for the session, so a change you just made in Desk will not
> appear until the app restarts.

---

## Removing access

**Untick `Enabled` on the User.** Their next login is refused by name, and — this is
the point — the account is *not* recreated by a later OTP. Deleting the user instead
means the very next login silently creates a fresh account with the default role, and
the person you just removed is back in.

Disabling also preserves their history. Every inspection, job card and punch they
signed stays attributable, which is the entire reason those records carry a name.

---

## Two things you cannot do

**You cannot change someone's phone number to one already in use.** Move the number
off the old account first, or disable the old account.

**You cannot merge two accounts.** If someone ends up with two — usually one
auto-created before an admin made a "proper" one — pick the one with history on it,
correct its name and roles, and disable the other. If both already carry records,
there is no supported way to combine them.

---

## Service accounts (integrations, not people)

An account for a machine — the vehicle sync, a reporting job — still hits the
mandatory-phone rule, and it has no phone. Creating one needs **both** of these flags
set at creation time, not one:

* `ignore_phone_requirement`
* `ignore_mandatory`

Setting only one still fails. This is a developer task via bench, not something to do
from the Desk form.
