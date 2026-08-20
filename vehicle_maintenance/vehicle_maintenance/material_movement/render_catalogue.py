"""Render Appendix A of MATERIAL_MOVEMENT_PRD.md from `catalogue.py`.

    python3 -m vehicle_maintenance.material_movement.render_catalogue

Rewrites the region between the `catalogue:start` / `catalogue:end` markers in
place. The catalogue is 151 rows; a hand-maintained second copy in the document
would be wrong within a week, and a wrong appendix is worse than no appendix
because people quote it.
"""

from __future__ import annotations

import pathlib
import re

from vehicle_maintenance.material_movement import catalogue as cat

START = "<!-- catalogue:start -->"
END = "<!-- catalogue:end -->"

#: Repo root, five levels up from this file (…/vehicle_maintenance/vehicle_maintenance/material_movement/).
DOC = pathlib.Path(__file__).resolve().parents[3] / "MATERIAL_MOVEMENT_PRD.md"


def _escape(text: str) -> str:
	"""Markdown table cells cannot contain a bare pipe."""
	return text.replace("|", "\\|")


def render() -> str:
	cat.verify()
	out: list[str] = []
	for index, (group, system, blurb) in enumerate(cat.GROUPS, start=1):
		rows = cat.by_group()[group]
		out.append(f"### A.{index} {group} — {len(rows)} items")
		out.append("")
		out.append(f"*{blurb}* · Bus system: **{system}**")
		out.append("")
		out.append("| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |")
		out.append("|---|---|---|---|---|---|---|")
		for code, name, _group, uom, qty, has_qr, sheet_rows, spec, sheet_name in rows:
			note = spec
			if sheet_name:
				note = f"{spec} — sheet: “{sheet_name}”" if spec else f"sheet: “{sheet_name}”"
			out.append(
				"| `{code}` | {name} | {qty} | {uom} | {qr} | {sheet} | {note} |".format(
					code=code,
					name=_escape(name),
					qty=qty or "",
					uom=uom,
					qr="●" if has_qr else "",
					sheet=", ".join(str(r) for r in sheet_rows),
					note=_escape(note),
				)
			)
		out.append("")
	return "\n".join(out).rstrip() + "\n"


def main() -> None:
	text = DOC.read_text(encoding="utf-8")
	if START not in text or END not in text:
		raise SystemExit(f"Markers {START} / {END} not found in {DOC}")
	replaced = re.sub(
		re.escape(START) + r".*?" + re.escape(END),
		f"{START}\n\n{render()}\n{END}",
		text,
		flags=re.DOTALL,
	)
	DOC.write_text(replaced, encoding="utf-8")
	print(f"{DOC}: rendered {len(cat.ITEMS)} items across {len(cat.GROUPS)} groups")


if __name__ == "__main__":
	main()
