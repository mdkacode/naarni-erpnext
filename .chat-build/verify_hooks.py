"""Pre-deploy gate: every module hooks.py names must exist in git.

The Azure deploy rsyncs the repo over apps/vehicle_maintenance with --delete and
then runs `bench migrate` under `set -e`. A hook that points at a module which is
only in someone's working tree therefore aborts the deploy partway through a
migration. This catches that before it reaches production.
"""

import ast
import re
import subprocess
import sys

HOOKS = "vehicle_maintenance/vehicle_maintenance/hooks.py"
ROOT = "vehicle_maintenance/"

tracked = set(
	subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()
)

src = open(HOOKS).read()
tree = ast.parse(src)

# Collect dotted paths from the hook containers that get resolved at runtime.
refs: list[tuple[str, str]] = []


def walk(node, origin):
	if isinstance(node, ast.Constant) and isinstance(node.value, str):
		if node.value.startswith("vehicle_maintenance."):
			refs.append((origin, node.value))
	for child in ast.iter_child_nodes(node):
		walk(child, origin)


WATCHED = {
	"after_migrate",
	"scheduler_events",
	"doc_events",
	"permission_query_conditions",
	"has_permission",
	"override_whitelisted_methods",
}

for node in tree.body:
	if isinstance(node, ast.Assign):
		for target in node.targets:
			name = getattr(target, "id", None)
			if name in WATCHED:
				walk(node.value, name)
	elif isinstance(node, ast.AnnAssign) and getattr(node.target, "id", None) in WATCHED:
		walk(node.value, node.target.id)


def resolves(dotted: str) -> bool:
	"""True if `dotted` names a real module plus a callable.

	Only 1 or 2 trailing attributes are dropped (`module.func` and
	`module.Class.method`). Walking further down would happily resolve
	`patches.v1_7.seed_process_engine.execute` to the tracked `patches/__init__.py`
	and wave through the exact breakage this gate exists to catch.
	"""
	parts = dotted.split(".")[1:]  # drop the leading app package
	for drop in (1, 2):
		mod = "/".join(parts[:-drop])
		if not mod:
			continue
		if f"{ROOT}vehicle_maintenance/{mod}.py" in tracked:
			return True
		if f"{ROOT}vehicle_maintenance/{mod}/__init__.py" in tracked:
			return True
	return False


missing = [(origin, dotted) for origin, dotted in refs if not resolves(dotted)]

# JS assets referenced by doctype_js must also survive the rsync --delete.
for m in re.finditer(r'"(public/js/[\w./-]+)"', src):
	path = f"{ROOT}vehicle_maintenance/{m.group(1)}"
	if path not in tracked:
		missing.append(("doctype_js", m.group(1)))

if missing:
	print("DEPLOY GATE FAILED — hooks.py references files not in git:\n")
	for origin, ref in missing:
		print(f"  [{origin}] {ref}")
	sys.exit(1)

print(f"DEPLOY GATE PASSED — {len(refs)} hook references, all tracked in git.")
