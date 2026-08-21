#!/usr/bin/env python3
"""Generate the app's UI sound set into app/src/main/res/raw/.

Why these are synthesised rather than sourced
---------------------------------------------
Chat feedback fires more often than anything else in the app — a technician in a
busy depot thread hears the send tone dozens of times an hour. That rules out
anything sampled and lush: it has to be short, quiet, and boring enough to stop
being noticed by the second day. It also has to be tiny, because these ship in
the APK.

Synthesis also buys the thing a sound pack cannot: the whole set is one family by
construction. Every tone here is built from the same waveform and the same
envelope shape, and the tones are drawn from one pentatonic set, so no two of
them can ever clash — which is exactly what went wrong with the previous
approach of replaying one `tap.wav` at different playback rates. Rate-shifting a
sample shifts its formants along with its pitch, so the "sent" tick was a
chipmunked version of the "tap" and the "received" tone was a muddy one.

The palette
-----------
A pentatonic scale, so that any two tones sounding at once — a send landing on
top of a receive, which happens constantly in a live thread — are consonant. No
minor seconds, no tritones, nothing that can sound like an error when it is not.

Run:  python3 tools/gen_ui_sounds.py
"""

from __future__ import annotations

import math
import os
import struct
import wave

SAMPLE_RATE = 48_000
OUT_DIR = os.path.join(
	os.path.dirname(os.path.abspath(__file__)),
	"..",
	"app",
	"src",
	"main",
	"res",
	"raw",
)

# A5-rooted major pentatonic. Bright enough to cut through a depot without
# being shrill on a phone speaker, low enough not to become fatiguing.
A4 = 440.0
NOTE = {
	"E5": A4 * 2 ** (7 / 12),
	"F#5": A4 * 2 ** (9 / 12),
	"A5": A4 * 2 ** (12 / 12),
	"B5": A4 * 2 ** (14 / 12),
	"C#6": A4 * 2 ** (16 / 12),
	"E6": A4 * 2 ** (19 / 12),
	"F#6": A4 * 2 ** (21 / 12),
	# Below the pentatonic, for the one sound that must read as "no".
	"D4": A4 * 2 ** (-7 / 12),
	"A4": A4,
}


def tone(
	freq: float,
	ms: float,
	*,
	decay: float = 6.0,
	gain: float = 1.0,
	harmonics: tuple[float, ...] = (1.0, 0.16, 0.05),
) -> list[float]:
	"""One struck note: harmonic stack under an exponential decay.

	The harmonic amounts are what stop this sounding like a test-tone generator.
	A pure sine reads as synthetic and, worse, is hard to localise — the ear has
	almost nothing to work with. A little second and third harmonic gives it a
	struck, bell-like quality that registers as a deliberate sound at a much
	lower volume, which is the whole goal.

	The 4ms attack ramp is not optional: starting a waveform at full amplitude
	puts a step discontinuity into the signal, and that click is audible on every
	phone speaker ever made.
	"""
	n = int(SAMPLE_RATE * ms / 1000.0)
	attack = int(SAMPLE_RATE * 0.004)
	out = []
	for i in range(n):
		t = i / SAMPLE_RATE
		env = math.exp(-decay * t / (ms / 1000.0))
		if i < attack:
			env *= i / attack
		s = sum(amp * math.sin(2 * math.pi * freq * (h + 1) * t) for h, amp in enumerate(harmonics))
		out.append(s * env * gain)
	return out


def sequence(parts: list[tuple[list[float], float]]) -> list[float]:
	"""Lay notes onto one buffer at millisecond offsets, summing overlaps.

	Overlap rather than concatenation: notes that ring into each other read as
	one gesture, where butted-together notes read as two separate events. For a
	confirmation tone that difference is the whole character of the thing.
	"""
	total = max(int(SAMPLE_RATE * off / 1000.0) + len(buf) for buf, off in parts)
	mix = [0.0] * total
	for buf, off in parts:
		start = int(SAMPLE_RATE * off / 1000.0)
		for i, s in enumerate(buf):
			mix[start + i] += s
	return mix


def write(name: str, samples: list[float], peak: float) -> None:
	"""Normalise to `peak` and write 16-bit mono PCM.

	Normalising per file rather than trusting the synthesis gains is what keeps
	the set balanced: `peak` is then a direct statement of how loud each sound is
	*relative to the others*, which is the only thing that matters here. The
	numbers are deliberately low — these play over whatever the user is already
	listening to.
	"""
	high = max(abs(s) for s in samples) or 1.0
	scale = peak / high
	frames = b"".join(struct.pack("<h", max(-32768, min(32767, int(s * scale * 32767)))) for s in samples)
	path = os.path.join(OUT_DIR, name)
	with wave.open(path, "wb") as w:
		w.setnchannels(1)
		w.setsampwidth(2)
		w.setframerate(SAMPLE_RATE)
		w.writeframes(frames)
	print(f"  {name:22s} {len(samples) / SAMPLE_RATE * 1000:6.0f} ms  {len(frames) // 1024:4d} KB")


def build() -> None:
	os.makedirs(OUT_DIR, exist_ok=True)
	print(f"writing to {os.path.normpath(OUT_DIR)}")

	# ── tap ──────────────────────────────────────────────────────────────
	# The most-fired sound in the app, so it is barely a sound at all: one high
	# note, 40ms, steep decay. Anything with a discernible pitch contour becomes
	# infuriating by the fiftieth press.
	write("ui_tap.wav", tone(NOTE["C#6"], 40, decay=9.0, harmonics=(1.0, 0.10)), 0.30)

	# ── message sent ─────────────────────────────────────────────────────
	# Rising, because the message is leaving. Two notes a fourth apart, the
	# second overlapping the first's tail so it reads as one flick rather than
	# two beeps. Short: this fires the instant the thumb lifts.
	write(
		"msg_sent.wav",
		sequence(
			[
				(tone(NOTE["B5"], 55, decay=9.0, gain=0.85), 0),
				(tone(NOTE["E6"], 80, decay=8.0, gain=1.0), 32),
			]
		),
		0.34,
	)

	# ── message received ─────────────────────────────────────────────────
	# Falling and softer, the mirror of sent — the direction alone tells you
	# which happened without you having to think about it. Longer decay so it
	# sits behind whatever the user is doing rather than interrupting it.
	write(
		"msg_received.wav",
		sequence(
			[
				(tone(NOTE["F#6"], 70, decay=7.0, gain=0.7), 0),
				(tone(NOTE["C#6"], 150, decay=5.0, gain=0.9), 45),
			]
		),
		0.30,
	)

	# ── incoming, thread not open ────────────────────────────────────────
	# The notification tray tone. Three notes, more ring, more presence: this one
	# has to be audible from a pocket, which is a different job from the in-app
	# chime and is why it is not the same file at a higher volume.
	write(
		"msg_notify.wav",
		sequence(
			[
				(tone(NOTE["E5"], 120, decay=5.5, gain=0.8), 0),
				(tone(NOTE["A5"], 140, decay=5.0, gain=0.9), 70),
				(tone(NOTE["C#6"], 260, decay=3.5, gain=1.0), 140),
			]
		),
		0.62,
	)

	# ── success ──────────────────────────────────────────────────────────
	# A rising arpeggio with real ring on the last note. Reserved for things that
	# took effort — a job card filed, a process run completed — so it is allowed
	# to be the one genuinely pleasant sound in the set.
	write(
		"ui_success.wav",
		sequence(
			[
				(tone(NOTE["E5"], 110, decay=6.0, gain=0.75), 0),
				(tone(NOTE["A5"], 130, decay=5.5, gain=0.85), 60),
				(tone(NOTE["C#6"], 150, decay=5.0, gain=0.9), 120),
				(tone(NOTE["E6"], 340, decay=3.0, gain=1.0), 180),
			]
		),
		0.55,
	)

	# ── error ────────────────────────────────────────────────────────────
	# The only sound outside the pentatonic, and deliberately so: it has to be
	# unmistakably not-a-success, and the way to guarantee that is to leave the
	# scale everything else lives in. Low, short, two falling notes. Not a buzz —
	# a harsh error tone gets an app's sound switched off entirely, and then the
	# useful sounds go with it.
	write(
		"ui_error.wav",
		sequence(
			[
				(tone(NOTE["A4"], 90, decay=7.0, gain=0.9, harmonics=(1.0, 0.22, 0.10)), 0),
				(tone(NOTE["D4"], 200, decay=5.0, gain=1.0, harmonics=(1.0, 0.22, 0.10)), 55),
			]
		),
		0.42,
	)

	# ── voice recording ──────────────────────────────────────────────────
	# Start rises, cancel falls, and both are single notes — they fire under the
	# user's finger during a gesture, where anything longer than a blip lands
	# after the gesture has already finished.
	write("rec_start.wav", tone(NOTE["A5"], 60, decay=8.0), 0.32)
	write(
		"rec_cancel.wav",
		sequence(
			[
				(tone(NOTE["A5"], 50, decay=9.0, gain=0.8), 0),
				(tone(NOTE["E5"], 110, decay=6.5, gain=0.9), 30),
			]
		),
		0.32,
	)


if __name__ == "__main__":
	build()
