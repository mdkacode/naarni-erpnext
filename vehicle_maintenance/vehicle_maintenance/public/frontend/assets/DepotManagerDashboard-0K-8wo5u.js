var f = (h, o, d) =>
	new Promise((y, u) => {
		var b = (i) => {
				try {
					c(d.next(i));
				} catch (x) {
					u(x);
				}
			},
			_ = (i) => {
				try {
					c(d.throw(i));
				} catch (x) {
					u(x);
				}
			},
			c = (i) => (i.done ? y(i.value) : Promise.resolve(i.value).then(b, _));
		c((d = d.apply(h, o)).next());
	});
import { c as $ } from "./api-BQ4HYjks.js";
import { _ as v } from "./StatusBadge-NvleVmQJ.js";
import {
	i as B,
	o as l,
	a as n,
	b as t,
	F as g,
	t as a,
	n as p,
	r as m,
	j as A,
	h as w,
	k,
	g as C,
	l as S,
} from "./main-CCKo9z3F.js";
const H = { class: "max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6" },
	L = { key: 0, class: "text-center py-16 text-gray-400" },
	M = { class: "grid grid-cols-2 md:grid-cols-4 gap-4 mb-8" },
	U = { class: "bg-white rounded-xl border p-5 text-center" },
	j = { class: "text-3xl font-extrabold text-brand-600" },
	D = { class: "bg-white rounded-xl border p-5 text-center" },
	V = { class: "text-3xl font-extrabold text-green-600" },
	N = { class: "bg-white rounded-xl border p-5 text-center" },
	P = { class: "text-3xl font-extrabold text-amber-600" },
	z = { class: "grid md:grid-cols-2 gap-6 mb-8" },
	E = { class: "bg-white rounded-xl border p-5" },
	F = { class: "space-y-2" },
	J = { class: "text-lg font-bold text-gray-900" },
	O = { class: "bg-white rounded-xl border p-5" },
	Z = { class: "space-y-3" },
	I = { class: "flex-1 bg-gray-100 rounded-full h-4 overflow-hidden" },
	K = { class: "text-sm font-bold w-8 text-right" },
	R = { class: "bg-white rounded-xl border" },
	T = { class: "overflow-x-auto" },
	W = { class: "min-w-full text-sm" },
	q = { class: "divide-y" },
	G = ["onClick"],
	Q = { class: "px-4 py-3 font-mono text-brand-600" },
	X = { class: "px-4 py-3 font-semibold" },
	Y = { class: "px-4 py-3" },
	tt = { class: "px-4 py-3 text-gray-600" },
	et = { class: "px-4 py-3" },
	st = { class: "px-4 py-3" },
	rt = { class: "px-4 py-3 text-center" },
	ot = { key: 0, class: "text-red-600 font-bold text-xs" },
	at = { key: 1, class: "text-green-500 text-xs" },
	xt = {
		__name: "DepotManagerDashboard",
		setup(h) {
			const o = w(null),
				d = w(!0);
			B(() =>
				f(this, null, function* () {
					try {
						const r = yield $("dashboard.get_depot_manager_dashboard");
						o.value = r.data;
					} finally {
						d.value = !1;
					}
				})
			);
			const y = k(() => {
					var r;
					return (r = o.value) != null && r.priorities
						? o.value.priorities
								.filter((s) => s.priority === "Urgent" || s.priority === "High")
								.reduce((s, e) => s + e.count, 0)
						: 0;
				}),
				u = k(() => {
					var r;
					return Math.max(
						...(((r = o.value) == null ? void 0 : r.priorities) || []).map((s) => s.count),
						1
					);
				});
			function b(r) {
				return `${(r / u.value) * 100}%`;
			}
			function _(r) {
				return (
					{
						Urgent: "bg-red-500",
						High: "bg-orange-400",
						Medium: "bg-amber-400",
						Low: "bg-green-400",
					}[r] || "bg-gray-300"
				);
			}
			function c(r) {
				return {
					Urgent: "bg-red-100 text-red-700",
					High: "bg-orange-100 text-orange-700",
					Medium: "bg-amber-100 text-amber-700",
					Low: "bg-green-100 text-green-700",
				}[r];
			}
			function i(r) {
				if (!r) return "";
				let e = r
					.replace(/[-\s]/g, "")
					.toUpperCase()
					.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
				return e ? `${e[1]} ${e[2].padStart(2, "0")} ${e[3]} ${e[4]}` : r;
			}
			function x(r) {
				window.open(`/app/job-card/${r}`, "_blank");
			}
			return (r, s) => (
				l(),
				n("div", H, [
					s[8] ||
						(s[8] = t(
							"h1",
							{ class: "text-2xl font-bold text-gray-900 mb-6" },
							"Depot Manager Dashboard",
							-1
						)),
					d.value
						? (l(), n("div", L, "Loading..."))
						: o.value
						? (l(),
						  n(
								g,
								{ key: 1 },
								[
									t("div", M, [
										t("div", U, [
											t("div", j, a(o.value.total_open), 1),
											s[0] ||
												(s[0] = t(
													"div",
													{
														class: "text-xs text-gray-500 mt-1 uppercase tracking-wider",
													},
													"Open Cards",
													-1
												)),
										]),
										t("div", D, [
											t("div", V, a(o.value.total_closed), 1),
											s[1] ||
												(s[1] = t(
													"div",
													{
														class: "text-xs text-gray-500 mt-1 uppercase tracking-wider",
													},
													"Closed",
													-1
												)),
										]),
										t(
											"div",
											{
												class: p([
													"bg-white rounded-xl border p-5 text-center",
													o.value.sla_breached > 0
														? "border-red-300 bg-red-50"
														: "",
												]),
											},
											[
												t(
													"div",
													{
														class: p([
															"text-3xl font-extrabold",
															o.value.sla_breached > 0
																? "text-red-600"
																: "text-gray-400",
														]),
													},
													a(o.value.sla_breached),
													3
												),
												s[2] ||
													(s[2] = t(
														"div",
														{
															class: "text-xs text-gray-500 mt-1 uppercase tracking-wider",
														},
														"SLA Breaches",
														-1
													)),
											],
											2
										),
										t("div", N, [
											t("div", P, a(y.value), 1),
											s[3] ||
												(s[3] = t(
													"div",
													{
														class: "text-xs text-gray-500 mt-1 uppercase tracking-wider",
													},
													"Urgent/High",
													-1
												)),
										]),
									]),
									t("div", z, [
										t("div", E, [
											s[4] ||
												(s[4] = t(
													"h2",
													{
														class: "text-sm font-semibold text-gray-500 uppercase mb-4",
													},
													"Cards by Status",
													-1
												)),
											t("div", F, [
												(l(!0),
												n(
													g,
													null,
													m(
														o.value.states,
														(e) => (
															l(),
															n(
																"div",
																{
																	key: e.workflow_state,
																	class: "flex items-center justify-between",
																},
																[
																	C(
																		v,
																		{ state: e.workflow_state },
																		null,
																		8,
																		["state"]
																	),
																	t("span", J, a(e.count), 1),
																]
															)
														)
													),
													128
												)),
											]),
										]),
										t("div", O, [
											s[5] ||
												(s[5] = t(
													"h2",
													{
														class: "text-sm font-semibold text-gray-500 uppercase mb-4",
													},
													"Priority Breakdown",
													-1
												)),
											t("div", Z, [
												(l(!0),
												n(
													g,
													null,
													m(
														o.value.priorities,
														(e) => (
															l(),
															n(
																"div",
																{
																	key: e.priority,
																	class: "flex items-center gap-3",
																},
																[
																	t(
																		"div",
																		{
																			class: p([
																				"w-24 text-sm font-medium",
																				e.priority === "Urgent"
																					? "text-red-600"
																					: e.priority === "High"
																					? "text-orange-600"
																					: "text-gray-700",
																			]),
																		},
																		a(e.priority),
																		3
																	),
																	t("div", I, [
																		t(
																			"div",
																			{
																				class: p([
																					"h-full rounded-full",
																					_(e.priority),
																				]),
																				style: S({
																					width: b(e.count),
																				}),
																			},
																			null,
																			6
																		),
																	]),
																	t("span", K, a(e.count), 1),
																]
															)
														)
													),
													128
												)),
											]),
										]),
									]),
									t("div", R, [
										s[7] ||
											(s[7] = t(
												"div",
												{
													class: "px-5 py-4 border-b flex items-center justify-between",
												},
												[
													t(
														"h2",
														{
															class: "text-sm font-semibold text-gray-500 uppercase",
														},
														"Active Job Cards"
													),
												],
												-1
											)),
										t("div", T, [
											t("table", W, [
												s[6] ||
													(s[6] = t(
														"thead",
														{ class: "bg-gray-50" },
														[
															t("tr", null, [
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Job Card"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Vehicle"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Type"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Customer"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Priority"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-left text-gray-600",
																	},
																	"Status"
																),
																t(
																	"th",
																	{
																		class: "px-4 py-3 text-center text-gray-600",
																	},
																	"SLA"
																),
															]),
														],
														-1
													)),
												t("tbody", q, [
													(l(!0),
													n(
														g,
														null,
														m(
															o.value.recent_cards,
															(e) => (
																l(),
																n(
																	"tr",
																	{
																		key: e.name,
																		class: "hover:bg-gray-50 cursor-pointer",
																		onClick: (lt) => x(e.name),
																	},
																	[
																		t("td", Q, a(e.name), 1),
																		t("td", X, a(i(e.vehicle_number)), 1),
																		t("td", Y, a(e.job_card_type), 1),
																		t("td", tt, a(e.customer_name), 1),
																		t("td", et, [
																			t(
																				"span",
																				{
																					class: p([
																						"text-xs font-semibold px-2 py-0.5 rounded-full",
																						c(e.priority),
																					]),
																				},
																				a(e.priority),
																				3
																			),
																		]),
																		t("td", st, [
																			C(
																				v,
																				{ state: e.workflow_state },
																				null,
																				8,
																				["state"]
																			),
																		]),
																		t("td", rt, [
																			e.sla_breached
																				? (l(),
																				  n("span", ot, "BREACH"))
																				: (l(), n("span", at, "OK")),
																		]),
																	],
																	8,
																	G
																)
															)
														),
														128
													)),
												]),
											]),
										]),
									]),
								],
								64
						  ))
						: A("", !0),
				])
			);
		},
	};
export { xt as default };
//# sourceMappingURL=DepotManagerDashboard-0K-8wo5u.js.map
