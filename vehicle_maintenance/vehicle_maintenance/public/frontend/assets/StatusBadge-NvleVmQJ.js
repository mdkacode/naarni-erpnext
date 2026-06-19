import { s as o } from "./api-BQ4HYjks.js";
import { o as i, a as p, t as l, n as c, k as a } from "./main-CCKo9z3F.js";
const d = {
	__name: "StatusBadge",
	props: { state: { type: String, required: !0 } },
	setup(s) {
		const t = s,
			e = a(() => o(t.state)),
			r = a(
				() =>
					({
						Open: "Open",
						WIP: "In Progress",
						"Awaiting Customer Approval": "Awaiting Approval",
						"Awaiting Parts": "Awaiting Parts",
						"Parts Fitted": "Parts Fitted",
						"Verification Pending": "Verification",
						Closed: "Closed",
					}[t.state] || t.state)
			);
		return (n, m) => (i(), p("span", { class: c(e.value) }, l(r.value), 3));
	},
};
export { d as _ };
//# sourceMappingURL=StatusBadge-NvleVmQJ.js.map
