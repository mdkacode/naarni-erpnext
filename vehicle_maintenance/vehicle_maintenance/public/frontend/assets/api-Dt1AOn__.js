var d = (a, n, t) =>
	new Promise((i, r) => {
		var l = (e) => {
				try {
					s(t.next(e));
				} catch (c) {
					r(c);
				}
			},
			o = (e) => {
				try {
					s(t.throw(e));
				} catch (c) {
					r(c);
				}
			},
			s = (e) => (e.done ? i(e.value) : Promise.resolve(e.value).then(l, o));
		s((t = t.apply(a, n)).next());
	});
import { x as p } from "./main-C3kezhEI.js";
const g = "vehicle_maintenance.api";
function u(t) {
	return d(this, arguments, function* (a, n = {}) {
		const i = a.includes("vehicle_maintenance") ? a : `${g}.${a}`;
		return p(i, n);
	});
}
function f(a) {
	return `badge-status ${
		{
			Open: "badge-open",
			WIP: "badge-wip",
			"Awaiting Customer Approval": "badge-approval",
			"Awaiting Parts": "badge-parts",
			"Parts Fitted": "badge-fitted",
			"Verification Pending": "badge-verify",
			Closed: "badge-closed",
		}[a] || "badge-open"
	}`;
}
export { u as c, f as s };
//# sourceMappingURL=api-Dt1AOn__.js.map
