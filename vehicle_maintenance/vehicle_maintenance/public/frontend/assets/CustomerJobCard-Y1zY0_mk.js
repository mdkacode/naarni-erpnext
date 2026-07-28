var ct = Object.defineProperty,
	vt = Object.defineProperties;
var mt = Object.getOwnPropertyDescriptors;
var Ie = Object.getOwnPropertySymbols;
var bt = Object.prototype.hasOwnProperty,
	pt = Object.prototype.propertyIsEnumerable;
var De = (m, C, p) =>
		C in m ? ct(m, C, { enumerable: !0, configurable: !0, writable: !0, value: p }) : (m[C] = p),
	Q = (m, C) => {
		for (var p in C || (C = {})) bt.call(C, p) && De(m, p, C[p]);
		if (Ie) for (var p of Ie(C)) pt.call(C, p) && De(m, p, C[p]);
		return m;
	},
	te = (m, C) => vt(m, mt(C));
var q = (m, C, p) =>
	new Promise((A, c) => {
		var x = ($) => {
				try {
					R(p.next($));
				} catch (S) {
					c(S);
				}
			},
			w = ($) => {
				try {
					R(p.throw($));
				} catch (S) {
					c(S);
				}
			},
			R = ($) => ($.done ? A($.value) : Promise.resolve($.value).then(x, w));
		R((p = p.apply(m, C)).next());
	});
import {
	i as Ae,
	d as Le,
	o as t,
	a as s,
	b as e,
	t as a,
	F as V,
	g as se,
	f as Fe,
	p as J,
	n as D,
	m as G,
	s as K,
	j as d,
	r as I,
	h as _,
	k as T,
	z as Me,
	w as Be,
	x as U,
	v as je,
	A as Ue,
	c as gt,
	u as Pe,
	e as X,
	B as yt,
	C as ae,
} from "./main-C3kezhEI.js";
import { c as Re } from "./api-Dt1AOn__.js";
import { _ as We } from "./StatusBadge-DZuo5DLS.js";
import { _ as xt } from "./HealthScoreCard-Dv8NSqQs.js";
import { _ as Z } from "./_plugin-vue_export-helper-DlAUqK2U.js";
const _t = { class: "max-w-lg mx-auto px-4 py-6" },
	ft = { key: 0, class: "text-center py-16" },
	ht = { key: 1, class: "text-center py-16" },
	kt = { class: "text-red-600 text-sm" },
	$t = { class: "mb-8" },
	Ct = { class: "mt-3 flex items-start justify-between" },
	wt = { class: "text-xl font-bold text-gray-900" },
	St = { class: "text-sm text-gray-500 mt-0.5" },
	Rt = { class: "text-xs text-gray-400 mt-1 font-mono" },
	jt = { class: "font-semibold text-base" },
	Pt = { class: "text-sm mt-1 opacity-80" },
	At = { key: 0, class: "mb-8 bg-white border-2 border-violet-200 rounded-xl p-5" },
	Mt = { class: "bg-violet-50 rounded-lg p-4 mb-4" },
	Et = { class: "flex justify-between items-baseline" },
	Nt = { class: "text-2xl font-bold text-gray-900" },
	Ft = { class: "text-xs text-gray-500 mt-2" },
	Vt = { class: "mb-4" },
	Ot = { class: "grid grid-cols-2 gap-3" },
	Tt = ["disabled"],
	It = ["disabled"],
	Dt = { key: 0, class: "mt-3 text-sm text-red-600" },
	qt = { key: 1, class: "mt-3 text-sm text-green-600" },
	Lt = { class: "mb-8" },
	Bt = { class: "relative border-l-2 border-gray-200 ml-4 space-y-0" },
	Ut = { key: 0, class: "w-3 h-3 text-white", fill: "currentColor", viewBox: "0 0 20 20" },
	Wt = { key: 1, class: "w-2 h-2 bg-brand-500 rounded-full animate-pulse" },
	Jt = { class: "text-xs text-gray-400 space-y-1 border-t border-gray-100 pt-4" },
	Ht = { key: 0 },
	Gt = {
		__name: "CustomerTrackingTimeline",
		props: { jobCardName: { type: String, required: !0 } },
		setup(m) {
			const C = m,
				p = _(null),
				A = _(null),
				c = _(!0),
				x = _(""),
				w = _(""),
				R = _(!1),
				$ = _(""),
				S = _(""),
				O = [
					"Open",
					"WIP",
					"Awaiting Customer Approval",
					"Awaiting Parts",
					"Parts Fitted",
					"Verification Pending",
					"Closed",
				],
				j = {
					Open: "Received",
					WIP: "Work in progress",
					"Awaiting Customer Approval": "Waiting for your approval",
					"Awaiting Parts": "Ordering parts",
					"Parts Fitted": "Parts installed",
					"Verification Pending": "Final quality check",
					Closed: "Complete",
				};
			function o() {
				return q(this, null, function* () {
					var P;
					(c.value = !0), (x.value = "");
					try {
						const i = yield Re("job_card.get_job_card_summary", { job_card_name: C.jobCardName });
						(p.value = i.data),
							p.value.workflow_state === "Awaiting Customer Approval" && (yield h());
					} catch (i) {
						x.value =
							((P = i == null ? void 0 : i.messages) == null ? void 0 : P[0]) ||
							"Could not load your service status.";
					} finally {
						c.value = !1;
					}
				});
			}
			function h() {
				return q(this, null, function* () {
					var P;
					try {
						if (!((P = p.value) != null && P.name)) return;
						const i = yield Re("vehicle_maintenance.api.estimate.get_estimate_for_card", {
							job_card_name: p.value.name,
						}).catch(() => null);
						i != null && i.data
							? (A.value = i.data)
							: p.value.estimated_cost > 0 &&
							  (A.value = { total_amount: p.value.estimated_cost, name: null });
					} catch (i) {
						p.value.estimated_cost > 0 &&
							(A.value = { total_amount: p.value.estimated_cost, name: null });
					}
				});
			}
			Ae(o);
			const g = T(() => {
					if (!p.value) return [];
					const P = O.indexOf(p.value.workflow_state);
					return O.map((i, L) => ({
						state: i,
						label: j[i] || i,
						sublabel: L === P ? "Current step" : "",
						completed: L < P,
						active: L === P,
						future: L > P,
					}));
				}),
				r = T(() =>
					p.value
						? {
								Open: {
									headline: "We've received your vehicle",
									body: "Our team will begin the inspection shortly.",
								},
								WIP: {
									headline: "Your vehicle is being serviced",
									body: "Our technicians are working on it. We'll update you as soon as there's news.",
								},
								"Awaiting Customer Approval": {
									headline: "We need your approval",
									body: "Please review the estimate below and approve or decline to continue.",
								},
								"Awaiting Parts": {
									headline: "Parts have been ordered",
									body: "We're waiting for the required parts to arrive. Work will resume once they're in.",
								},
								"Parts Fitted": {
									headline: "Parts have been installed",
									body: "The new parts are in place. Work is continuing on your vehicle.",
								},
								"Verification Pending": {
									headline: "Almost done!",
									body: "Your vehicle is undergoing a final quality check before handover.",
								},
								Closed: {
									headline: "Your vehicle is ready!",
									body: "Service is complete. You can pick up your vehicle at the workshop.",
								},
						  }[p.value.workflow_state] || { headline: p.value.workflow_state, body: "" }
						: { headline: "", body: "" }
				),
				v = T(() =>
					p.value
						? {
								Open: "border-indigo-200 bg-indigo-50 text-indigo-900",
								WIP: "border-amber-200 bg-amber-50 text-amber-900",
								"Awaiting Customer Approval":
									"border-violet-300 bg-violet-50 text-violet-900",
								"Awaiting Parts": "border-pink-200 bg-pink-50 text-pink-900",
								"Parts Fitted": "border-cyan-200 bg-cyan-50 text-cyan-900",
								"Verification Pending": "border-orange-200 bg-orange-50 text-orange-900",
								Closed: "border-green-300 bg-green-50 text-green-900",
						  }[p.value.workflow_state] || "border-gray-200 bg-gray-50 text-gray-900"
						: ""
				);
			function y(P) {
				return P.completed
					? "bg-green-500 border-green-500"
					: P.active
					? "bg-white border-brand-500"
					: "bg-white border-gray-300";
			}
			function b() {
				return q(this, null, function* () {
					var P, i;
					if (!((P = A.value) != null && P.name)) {
						$.value = "No estimate reference found. Please contact the workshop.";
						return;
					}
					(R.value = !0), ($.value = ""), (S.value = "");
					try {
						yield Re("estimate.approve_estimate", {
							estimate_name: A.value.name,
							remarks: w.value,
						}),
							(S.value = "Estimate approved! Work will continue."),
							setTimeout(() => o(), 1500);
					} catch (L) {
						$.value =
							((i = L == null ? void 0 : L.messages) == null ? void 0 : i[0]) ||
							"Could not approve. Please try again.";
					} finally {
						R.value = !1;
					}
				});
			}
			function u() {
				return q(this, null, function* () {
					var P, i;
					if (!w.value.trim()) {
						$.value = "Please add a reason for declining.";
						return;
					}
					if (!((P = A.value) != null && P.name)) {
						$.value = "No estimate reference found. Please contact the workshop.";
						return;
					}
					(R.value = !0), ($.value = ""), (S.value = "");
					try {
						yield Re("estimate.reject_estimate", {
							estimate_name: A.value.name,
							remarks: w.value,
						}),
							(S.value = "Estimate declined. The team will contact you."),
							setTimeout(() => o(), 1500);
					} catch (L) {
						$.value =
							((i = L == null ? void 0 : L.messages) == null ? void 0 : i[0]) ||
							"Could not decline. Please try again.";
					} finally {
						R.value = !1;
					}
				});
			}
			function n(P) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					minimumFractionDigits: 0,
				}).format(P || 0);
			}
			function B(P) {
				return P
					? new Date(P).toLocaleDateString("en-IN", {
							day: "numeric",
							month: "short",
							year: "numeric",
					  })
					: "";
			}
			return (P, i) => {
				const L = Le("router-link");
				return (
					t(),
					s("div", _t, [
						c.value
							? (t(),
							  s("div", ft, [
									...(i[1] ||
										(i[1] = [
											e(
												"div",
												{
													class: "inline-block w-8 h-8 border-3 border-gray-200 border-t-brand-500 rounded-full animate-spin",
												},
												null,
												-1
											),
											e(
												"p",
												{ class: "mt-3 text-sm text-gray-400" },
												"Loading your service status...",
												-1
											),
										])),
							  ]))
							: x.value
							? (t(),
							  s("div", ht, [
									e("p", kt, a(x.value), 1),
									e(
										"button",
										{
											onClick: o,
											class: "mt-3 px-4 py-2 text-sm text-brand-600 border border-brand-200 rounded-lg hover:bg-brand-50",
										},
										" Try again "
									),
							  ]))
							: p.value
							? (t(),
							  s(
									V,
									{ key: 2 },
									[
										e("div", $t, [
											se(
												L,
												{
													to: "/service-portal",
													class: "text-sm text-brand-600 hover:underline",
												},
												{
													default: Fe(() => [
														...(i[2] || (i[2] = [J(" ← My vehicles ", -1)])),
													]),
													_: 1,
												}
											),
											e("div", Ct, [
												e("div", null, [
													e("h1", wt, a(p.value.vehicle_number), 1),
													e("p", St, a(p.value.vehicle_make_model), 1),
													e("p", Rt, a(p.value.name), 1),
												]),
												se(We, { state: p.value.workflow_state }, null, 8, ["state"]),
											]),
										]),
										e(
											"div",
											{ class: D(["mb-8 p-4 rounded-xl border-2", v.value]) },
											[
												e("p", jt, a(r.value.headline), 1),
												e("p", Pt, a(r.value.body), 1),
											],
											2
										),
										p.value.workflow_state === "Awaiting Customer Approval" && A.value
											? (t(),
											  s("div", At, [
													i[5] ||
														(i[5] = e(
															"h2",
															{
																class: "text-base font-semibold text-gray-900 mb-3",
															},
															"Estimate for your approval",
															-1
														)),
													e("div", Mt, [
														e("div", Et, [
															i[3] ||
																(i[3] = e(
																	"span",
																	{ class: "text-sm text-gray-600" },
																	"Estimated total",
																	-1
																)),
															e("span", Nt, a(n(A.value.total_amount)), 1),
														]),
														e(
															"p",
															Ft,
															a(p.value.service_type) +
																" · " +
																a(p.value.vehicle_number),
															1
														),
													]),
													e("div", Vt, [
														i[4] ||
															(i[4] = e(
																"label",
																{
																	class: "block text-sm font-medium text-gray-700 mb-1",
																},
																" Any remarks? (optional) ",
																-1
															)),
														G(
															e(
																"textarea",
																{
																	"onUpdate:modelValue":
																		i[0] || (i[0] = (H) => (w.value = H)),
																	rows: "2",
																	placeholder: "Questions or conditions...",
																	class: "w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-brand-500",
																},
																null,
																512
															),
															[[K, w.value]]
														),
													]),
													e("div", Ot, [
														e(
															"button",
															{
																onClick: u,
																disabled: R.value,
																class: "py-3 text-sm font-semibold rounded-xl border-2 border-red-300 text-red-700 bg-red-50 hover:bg-red-100 transition-colors disabled:opacity-50",
															},
															" Decline ",
															8,
															Tt
														),
														e(
															"button",
															{
																onClick: b,
																disabled: R.value,
																class: "py-3 text-sm font-semibold rounded-xl border-2 border-green-400 text-green-800 bg-green-50 hover:bg-green-100 transition-colors disabled:opacity-50",
															},
															" Approve ",
															8,
															It
														),
													]),
													$.value ? (t(), s("div", Dt, a($.value), 1)) : d("", !0),
													S.value ? (t(), s("div", qt, a(S.value), 1)) : d("", !0),
											  ]))
											: d("", !0),
										e("div", Lt, [
											i[7] ||
												(i[7] = e(
													"h2",
													{
														class: "text-sm font-semibold text-gray-500 uppercase tracking-wider mb-4",
													},
													"Progress",
													-1
												)),
											e("ol", Bt, [
												(t(!0),
												s(
													V,
													null,
													I(
														g.value,
														(H, xe) => (
															t(),
															s(
																"li",
																{
																	key: H.state,
																	class: "relative pl-8 pb-8 last:pb-0",
																},
																[
																	e(
																		"div",
																		{
																			class: D([
																				"absolute -left-[11px] top-0.5 flex items-center justify-center w-5 h-5 rounded-full border-2",
																				y(H),
																			]),
																		},
																		[
																			H.completed
																				? (t(),
																				  s("svg", Ut, [
																						...(i[6] ||
																							(i[6] = [
																								e(
																									"path",
																									{
																										"fill-rule":
																											"evenodd",
																										d: "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z",
																										"clip-rule":
																											"evenodd",
																									},
																									null,
																									-1
																								),
																							])),
																				  ]))
																				: H.active
																				? (t(), s("div", Wt))
																				: d("", !0),
																		],
																		2
																	),
																	e("div", null, [
																		e(
																			"p",
																			{
																				class: D([
																					"text-sm font-medium",
																					H.completed
																						? "text-gray-900"
																						: H.active
																						? "text-brand-700"
																						: "text-gray-400",
																				]),
																			},
																			a(H.label),
																			3
																		),
																		H.sublabel
																			? (t(),
																			  s(
																					"p",
																					{
																						key: 0,
																						class: D([
																							"text-xs mt-0.5",
																							H.active
																								? "text-brand-500"
																								: "text-gray-400",
																						]),
																					},
																					a(H.sublabel),
																					3
																			  ))
																			: d("", !0),
																	]),
																]
															)
														)
													),
													128
												)),
											]),
										]),
										e("div", Jt, [
											e("p", null, "Service type: " + a(p.value.service_type), 1),
											p.value.opened_at
												? (t(), s("p", Ht, "Opened: " + a(B(p.value.opened_at)), 1))
												: d("", !0),
										]),
									],
									64
							  ))
							: d("", !0),
					])
				);
			};
		},
	},
	zt = { class: "space-y-3" },
	Yt = {
		key: 0,
		class: "text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl",
	},
	Qt = { class: "flex items-start gap-3" },
	Kt = { class: "flex-1" },
	Xt = ["value", "onChange"],
	Zt = ["value"],
	es = ["onClick"],
	ts = { class: "flex gap-2" },
	ss = ["onClick"],
	as = ["value", "onInput"],
	ns = { class: "grid grid-cols-3 gap-2" },
	os = ["value", "onInput"],
	ls = ["value", "onInput"],
	rs = { class: "input-field bg-gray-50 text-gray-500 flex items-center" },
	is = {
		key: 0,
		class: "flex items-center gap-2 p-2 text-xs bg-amber-50 border border-amber-200 rounded-lg text-amber-800",
	},
	ds = { class: "text-xs text-gray-600" },
	us = { class: "grid grid-cols-2 gap-3 mt-2" },
	cs = ["onChange"],
	vs = { key: 0, class: "mt-1 text-green-600" },
	ms = ["onChange"],
	bs = { key: 0, class: "mt-1 text-green-600" },
	ps = { key: 1, class: "text-right text-sm text-gray-600 pt-2 border-t border-gray-100" },
	gs = { key: 0, class: "ml-3 text-amber-700 font-medium" },
	ys = 1e3,
	xs = {
		__name: "RepairJobTable",
		props: {
			modelValue: { type: Array, default: () => [] },
			partGroups: { type: Array, default: () => [] },
		},
		emits: ["update:modelValue"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = ["Only Repair", "Spare Replacement", "Both"],
				x = T(() => p.modelValue || []),
				w = T(() => x.value.reduce((g, r) => g + (Number(r.qty) || 0) * (Number(r.rate) || 0), 0)),
				R = T(() => x.value.some((g) => $(g)));
			function $(g) {
				return g.activity_type !== "Spare Replacement" && g.activity_type !== "Both"
					? !1
					: (Number(g.qty) || 0) * (Number(g.rate) || 0) > ys;
			}
			function S(g) {
				A("update:modelValue", g);
			}
			function O() {
				S([
					...x.value,
					{
						part_group: "",
						activity_type: "Only Repair",
						description: "",
						qty: 1,
						rate: 0,
						pre_repair_photo: null,
						post_repair_photo: null,
					},
				]);
			}
			function j(g) {
				S(x.value.filter((r, v) => v !== g));
			}
			function o(g, r, v) {
				const y = x.value.map((b, u) => (u === g ? te(Q({}, b), { [r]: v }) : b));
				S(y);
			}
			function h(g, r, v) {
				var b;
				const y = (b = v.target.files) == null ? void 0 : b[0];
				y && o(g, r, y);
			}
			return (g, r) => (
				t(),
				s("div", zt, [
					e("div", { class: "flex items-center justify-between" }, [
						r[0] ||
							(r[0] = e(
								"h3",
								{ class: "text-sm font-semibold text-gray-800" },
								"Repair Jobs",
								-1
							)),
						e(
							"button",
							{
								type: "button",
								onClick: O,
								class: "px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg transition-colors",
							},
							" + Add Repair "
						),
					]),
					x.value.length
						? d("", !0)
						: (t(), s("div", Yt, ' No repair jobs yet — tap "Add Repair" to start. ')),
					(t(!0),
					s(
						V,
						null,
						I(
							x.value,
							(v, y) => (
								t(),
								s(
									"div",
									{
										key: y,
										class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3",
									},
									[
										e("div", Qt, [
											e("div", Kt, [
												r[2] ||
													(r[2] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Part Group",
														-1
													)),
												e(
													"select",
													{
														value: v.part_group,
														onChange: (b) => o(y, "part_group", b.target.value),
														class: "input-field",
													},
													[
														r[1] ||
															(r[1] = e(
																"option",
																{ value: "" },
																"Select part group…",
																-1
															)),
														(t(!0),
														s(
															V,
															null,
															I(
																m.partGroups,
																(b) => (
																	t(),
																	s(
																		"option",
																		{ key: b.name, value: b.name },
																		a(b.part_group_name || b.name) +
																			" (" +
																			a(b.bus_system) +
																			") ",
																		9,
																		Zt
																	)
																)
															),
															128
														)),
													],
													40,
													Xt
												),
											]),
											e(
												"button",
												{
													type: "button",
													onClick: (b) => j(y),
													class: "mt-5 text-red-500 hover:text-red-600 text-sm px-2 py-1",
													"aria-label": "Remove row",
												},
												" ✕ ",
												8,
												es
											),
										]),
										e("div", null, [
											r[3] ||
												(r[3] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Activity Type",
													-1
												)),
											e("div", ts, [
												(t(),
												s(
													V,
													null,
													I(c, (b) =>
														e(
															"button",
															{
																key: b,
																type: "button",
																onClick: (u) => o(y, "activity_type", b),
																class: D([
																	"flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all",
																	v.activity_type === b
																		? "border-brand-500 bg-brand-50 text-brand-700"
																		: "border-gray-200 text-gray-500 hover:border-gray-300",
																]),
															},
															a(b),
															11,
															ss
														)
													),
													64
												)),
											]),
										]),
										e("div", null, [
											r[4] ||
												(r[4] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Work Description",
													-1
												)),
											e(
												"input",
												{
													value: v.description,
													onInput: (b) => o(y, "description", b.target.value),
													type: "text",
													placeholder: "e.g. Replace worn brake pads",
													class: "input-field",
												},
												null,
												40,
												as
											),
										]),
										e("div", ns, [
											e("div", null, [
												r[5] ||
													(r[5] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Qty",
														-1
													)),
												e(
													"input",
													{
														value: v.qty,
														onInput: (b) =>
															o(y, "qty", Number(b.target.value) || 0),
														type: "number",
														min: "0",
														step: "0.5",
														class: "input-field",
													},
													null,
													40,
													os
												),
											]),
											e("div", null, [
												r[6] ||
													(r[6] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Est. Rate (₹)",
														-1
													)),
												e(
													"input",
													{
														value: v.rate,
														onInput: (b) =>
															o(y, "rate", Number(b.target.value) || 0),
														type: "number",
														min: "0",
														step: "1",
														class: "input-field",
													},
													null,
													40,
													ls
												),
											]),
											e("div", null, [
												r[7] ||
													(r[7] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Line Total",
														-1
													)),
												e(
													"div",
													rs,
													" ₹ " +
														a(((v.qty || 0) * (v.rate || 0)).toLocaleString()),
													1
												),
											]),
										]),
										$(v)
											? (t(),
											  s("div", is, [
													...(r[8] ||
														(r[8] = [
															e("span", { class: "font-bold" }, "!", -1),
															J(
																" Over ₹1,000 — customer approval required before parts are allocated. ",
																-1
															),
														])),
											  ]))
											: d("", !0),
										e("details", ds, [
											r[11] ||
												(r[11] = e(
													"summary",
													{ class: "cursor-pointer select-none font-medium" },
													"Evidence photos",
													-1
												)),
											e("div", us, [
												e("div", null, [
													r[9] ||
														(r[9] = e(
															"label",
															{ class: "block text-xs text-gray-500 mb-1" },
															"Pre-Repair",
															-1
														)),
													e(
														"input",
														{
															type: "file",
															accept: "image/*",
															onChange: (b) => h(y, "pre_repair_photo", b),
															class: "text-xs",
														},
														null,
														40,
														cs
													),
													v.pre_repair_photo
														? (t(), s("div", vs, "✓ Attached"))
														: d("", !0),
												]),
												e("div", null, [
													r[10] ||
														(r[10] = e(
															"label",
															{ class: "block text-xs text-gray-500 mb-1" },
															"Post-Repair",
															-1
														)),
													e(
														"input",
														{
															type: "file",
															accept: "image/*",
															onChange: (b) => h(y, "post_repair_photo", b),
															class: "text-xs",
														},
														null,
														40,
														ms
													),
													v.post_repair_photo
														? (t(), s("div", bs, "✓ Attached"))
														: d("", !0),
												]),
											]),
										]),
									]
								)
							)
						),
						128
					)),
					x.value.length
						? (t(),
						  s("div", ps, [
								r[12] || (r[12] = e("span", { class: "font-medium" }, "Total Estimate:", -1)),
								J(" ₹ " + a(w.value.toLocaleString()) + " ", 1),
								R.value ? (t(), s("span", gs, " · needs customer approval ")) : d("", !0),
						  ]))
						: d("", !0),
				])
			);
		},
	},
	_s = Z(xs, [["__scopeId", "data-v-3f9734b9"]]),
	fs = { class: "space-y-3" },
	hs = {
		key: 0,
		class: "text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl",
	},
	ks = { class: "flex items-start gap-3" },
	$s = { class: "flex-1" },
	Cs = ["value", "onChange"],
	ws = ["value"],
	Ss = ["onClick"],
	Rs = { class: "flex gap-2" },
	js = ["onClick"],
	Ps = { class: "grid grid-cols-2 gap-2" },
	As = ["value", "onInput"],
	Ms = { class: "block text-xs font-medium text-gray-500 mb-1" },
	Es = { class: "flex gap-2" },
	Ns = ["value", "onInput"],
	Fs = ["value", "onChange"],
	Vs = ["value"],
	Os = { class: "grid grid-cols-2 gap-3" },
	Ts = { class: "block text-xs text-gray-500 mb-1" },
	Is = ["onChange"],
	Ds = { key: 0, class: "mt-1 text-xs text-green-600" },
	qs = { class: "block text-xs text-gray-500 mb-1" },
	Ls = ["onChange"],
	Bs = { key: 0, class: "mt-1 text-xs text-green-600" },
	Us = {
		__name: "MaintenanceJobTable",
		props: { modelValue: { type: Array, default: () => [] } },
		emits: ["update:modelValue"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = ["Oil/Lubricant", "Coolant", "Grease", "Filter", "Consumable"],
				x = ["Litres", "Kg", "Pcs", "Set"],
				w = T(() => p.modelValue || []);
			function R(r) {
				return r.maintenance_type === "Filter"
					? ["Top-up", "Cleaning", "Replacement"]
					: ["Top-up", "Replacement"];
			}
			function $(r) {
				return r.action === "Cleaning"
					? "Dirty (before cleaning)"
					: r.action === "Replacement"
					? r.maintenance_type === "Filter"
						? "Old filter"
						: "Drained fluid"
					: "Before";
			}
			function S(r) {
				return r.action === "Cleaning"
					? "Cleaned"
					: r.action === "Replacement"
					? r.maintenance_type === "Filter"
						? "New filter installed"
						: "New fluid + volume"
					: "After top-up";
			}
			function O(r) {
				A("update:modelValue", r);
			}
			function j() {
				O([
					...w.value,
					{
						maintenance_type: "",
						action: "Replacement",
						description: "",
						qty: 1,
						unit: "Litres",
						pre_photo: null,
						post_photo: null,
					},
				]);
			}
			function o(r) {
				O(w.value.filter((v, y) => y !== r));
			}
			function h(r, v, y) {
				const b = w.value.map((u, n) => (n === r ? te(Q({}, u), { [v]: y }) : u));
				O(b);
			}
			function g(r, v, y) {
				var u;
				const b = (u = y.target.files) == null ? void 0 : u[0];
				b && h(r, v, b);
			}
			return (r, v) => (
				t(),
				s("div", fs, [
					e("div", { class: "flex items-center justify-between" }, [
						v[0] ||
							(v[0] = e(
								"h3",
								{ class: "text-sm font-semibold text-gray-800" },
								"Maintenance Jobs",
								-1
							)),
						e(
							"button",
							{
								type: "button",
								onClick: j,
								class: "px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg transition-colors",
							},
							" + Add Maintenance "
						),
					]),
					w.value.length
						? d("", !0)
						: (t(),
						  s(
								"div",
								hs,
								' No maintenance items yet — tap "Add Maintenance" for oil, coolant, or filters. '
						  )),
					(t(!0),
					s(
						V,
						null,
						I(
							w.value,
							(y, b) => (
								t(),
								s(
									"div",
									{
										key: b,
										class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3",
									},
									[
										e("div", ks, [
											e("div", $s, [
												v[2] ||
													(v[2] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Type",
														-1
													)),
												e(
													"select",
													{
														value: y.maintenance_type,
														onChange: (u) =>
															h(b, "maintenance_type", u.target.value),
														class: "input-field",
													},
													[
														v[1] ||
															(v[1] = e(
																"option",
																{ value: "" },
																"Select…",
																-1
															)),
														(t(),
														s(
															V,
															null,
															I(c, (u) =>
																e("option", { key: u, value: u }, a(u), 9, ws)
															),
															64
														)),
													],
													40,
													Cs
												),
											]),
											e(
												"button",
												{
													type: "button",
													onClick: (u) => o(b),
													class: "mt-5 text-red-500 hover:text-red-600 text-sm px-2 py-1",
													"aria-label": "Remove row",
												},
												" ✕ ",
												8,
												Ss
											),
										]),
										e("div", null, [
											v[3] ||
												(v[3] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Action",
													-1
												)),
											e("div", Rs, [
												(t(!0),
												s(
													V,
													null,
													I(
														R(y),
														(u) => (
															t(),
															s(
																"button",
																{
																	key: u,
																	type: "button",
																	onClick: (n) => h(b, "action", u),
																	class: D([
																		"flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all",
																		y.action === u
																			? "border-brand-500 bg-brand-50 text-brand-700"
																			: "border-gray-200 text-gray-500 hover:border-gray-300",
																	]),
																},
																a(u),
																11,
																js
															)
														)
													),
													128
												)),
											]),
										]),
										e("div", Ps, [
											e("div", null, [
												v[4] ||
													(v[4] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Item",
														-1
													)),
												e(
													"input",
													{
														value: y.description,
														onInput: (u) => h(b, "description", u.target.value),
														type: "text",
														placeholder: "e.g. 15W-40 Engine Oil",
														class: "input-field",
													},
													null,
													40,
													As
												),
											]),
											e("div", null, [
												e(
													"label",
													Ms,
													" Quantity" + a(y.unit ? ` (${y.unit})` : ""),
													1
												),
												e("div", Es, [
													e(
														"input",
														{
															value: y.qty,
															onInput: (u) =>
																h(b, "qty", Number(u.target.value) || 0),
															type: "number",
															min: "0",
															step: "0.1",
															class: "input-field flex-1",
														},
														null,
														40,
														Ns
													),
													e(
														"select",
														{
															value: y.unit,
															onChange: (u) => h(b, "unit", u.target.value),
															class: "input-field w-20",
														},
														[
															(t(),
															s(
																V,
																null,
																I(x, (u) =>
																	e(
																		"option",
																		{ key: u, value: u },
																		a(u),
																		9,
																		Vs
																	)
																),
																64
															)),
														],
														40,
														Fs
													),
												]),
											]),
										]),
										e("div", Os, [
											e("div", null, [
												e("label", Ts, a($(y)), 1),
												e(
													"input",
													{
														type: "file",
														accept: "image/*",
														onChange: (u) => g(b, "pre_photo", u),
														class: "text-xs",
													},
													null,
													40,
													Is
												),
												y.pre_photo ? (t(), s("div", Ds, "✓ Attached")) : d("", !0),
											]),
											e("div", null, [
												e("label", qs, a(S(y)), 1),
												e(
													"input",
													{
														type: "file",
														accept: "image/*",
														onChange: (u) => g(b, "post_photo", u),
														class: "text-xs",
													},
													null,
													40,
													Ls
												),
												y.post_photo ? (t(), s("div", Bs, "✓ Attached")) : d("", !0),
											]),
										]),
									]
								)
							)
						),
						128
					)),
				])
			);
		},
	},
	Ws = Z(Us, [["__scopeId", "data-v-5c9876eb"]]),
	Js = ["disabled"],
	Hs = { class: "bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4" },
	Gs = { class: "space-y-2" },
	zs = ["onClick"],
	Ys = { class: "flex items-start gap-2" },
	Qs = { class: "font-semibold text-sm" },
	Ks = { class: "text-xs text-gray-500 ml-auto" },
	Xs = { class: "text-xs text-gray-600 mt-1" },
	Zs = { key: 0, class: "text-sm text-red-600" },
	ea = { class: "flex gap-2 justify-end pt-2" },
	ta = ["disabled"],
	sa = {
		__name: "ForceCloseButton",
		props: { disabled: { type: Boolean, default: !1 } },
		emits: ["submit"],
		setup(m, { emit: C }) {
			const p = C,
				A = _(!1),
				c = _(""),
				x = _(""),
				w = _(!1),
				R = _(""),
				$ = [
					{
						value: "Minor",
						label: "Minor",
						description: "Cosmetic or minor functional issue. Safe to defer to next PMS.",
						authority: "SE can force close",
						activeClasses: "border-amber-500 bg-amber-50",
					},
					{
						value: "Major",
						label: "Major",
						description: "Operational but with a significant issue. Needs follow-up within 24h.",
						authority: "DM or Aftersales approval",
						activeClasses: "border-orange-500 bg-orange-50",
					},
					{
						value: "Critical",
						label: "Critical",
						description: "Vehicle non-operational or safety risk. Auto-creates follow-up in 24h.",
						authority: "N. Maintenance Head only",
						activeClasses: "border-red-500 bg-red-50",
					},
				],
				S = T(() => c.value && x.value.trim().length > 0);
			function O() {
				(A.value = !1), (c.value = ""), (x.value = ""), (R.value = ""), (w.value = !1);
			}
			function j() {
				return q(this, null, function* () {
					if (S.value) {
						(w.value = !0), (R.value = "");
						try {
							yield p("submit", { severity: c.value, reason: x.value.trim() }), O();
						} catch (o) {
							(R.value =
								(o == null ? void 0 : o.message) || "Force close failed. Please try again."),
								(w.value = !1);
						}
					}
				});
			}
			return (o, h) => (
				t(),
				s("div", null, [
					e(
						"button",
						{
							type: "button",
							disabled: m.disabled,
							onClick: h[0] || (h[0] = (g) => (A.value = !0)),
							class: "px-3 py-2 text-sm font-medium text-red-700 border border-red-300 hover:bg-red-50 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed transition-colors",
						},
						" Force Close ",
						8,
						Js
					),
					A.value
						? (t(),
						  s(
								"div",
								{
									key: 0,
									class: "fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4",
									onClick: Me(O, ["self"]),
								},
								[
									e("div", Hs, [
										h[3] ||
											(h[3] = e(
												"h3",
												{ class: "text-lg font-semibold text-gray-900" },
												"Force Close Job Card",
												-1
											)),
										h[4] ||
											(h[4] = e(
												"p",
												{ class: "text-sm text-gray-600" },
												" Select the severity that best describes why this job card cannot complete normally. ",
												-1
											)),
										e("div", Gs, [
											(t(),
											s(
												V,
												null,
												I($, (g) =>
													e(
														"button",
														{
															key: g.value,
															type: "button",
															onClick: (r) => (c.value = g.value),
															class: D([
																"w-full text-left p-3 rounded-lg border-2 transition-all",
																c.value === g.value
																	? `${g.activeClasses}`
																	: "border-gray-200 hover:border-gray-300",
															]),
														},
														[
															e("div", Ys, [
																e("div", Qs, a(g.label), 1),
																e("div", Ks, a(g.authority), 1),
															]),
															e("div", Xs, a(g.description), 1),
														],
														10,
														zs
													)
												),
												64
											)),
										]),
										e("div", null, [
											h[2] ||
												(h[2] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													[
														J(" Reason "),
														e("span", { class: "text-red-500" }, "*"),
													],
													-1
												)),
											G(
												e(
													"textarea",
													{
														"onUpdate:modelValue":
															h[1] || (h[1] = (g) => (x.value = g)),
														rows: "3",
														placeholder:
															"What prevented normal closure? Inventory unavailable, issue resolved on its own, etc.",
														class: "input-field",
													},
													null,
													512
												),
												[[K, x.value]]
											),
										]),
										R.value ? (t(), s("div", Zs, a(R.value), 1)) : d("", !0),
										e("div", ea, [
											e(
												"button",
												{
													type: "button",
													onClick: O,
													class: "px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg",
												},
												" Cancel "
											),
											e(
												"button",
												{
													type: "button",
													onClick: j,
													disabled: w.value || !S.value,
													class: "px-4 py-2 text-sm font-medium text-white bg-red-600 hover:bg-red-700 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed",
												},
												a(w.value ? "Closing…" : "Confirm Force Close"),
												9,
												ta
											),
										]),
									]),
								]
						  ))
						: d("", !0),
				])
			);
		},
	},
	aa = Z(sa, [["__scopeId", "data-v-561bc7a8"]]),
	na = { key: 0, class: "flex flex-wrap gap-1.5 mb-2" },
	oa = ["onClick"],
	la = { key: 1, class: "relative" },
	ra = { class: "text-xs text-gray-400" },
	ia = {
		key: 0,
		class: "absolute z-10 mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg max-h-64 overflow-y-auto p-2",
	},
	da = { key: 0, class: "text-center text-xs text-gray-400 py-2" },
	ua = { key: 1, class: "text-center text-xs text-gray-400 py-2" },
	ca = { class: "text-[10px] uppercase tracking-wide text-gray-400 font-semibold mt-2 mb-1 px-1" },
	va = ["onClick"],
	ma = { key: 0, class: "text-brand-600 font-bold" },
	ba = { key: 2, class: "text-xs text-amber-700 mt-2" },
	pa = {
		__name: "SubsystemPicker",
		props: { modelValue: { type: Array, default: () => [] }, disabled: { type: Boolean, default: !1 } },
		emits: ["update:modelValue"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = _([]),
				x = _(!1),
				w = _(!1),
				R = _(""),
				$ = T(() => p.modelValue || []),
				S = T(() => {
					const o = R.value.trim().toLowerCase(),
						h = new Map();
					for (const g of c.value) {
						if (o && !g.subsystem_name.toLowerCase().includes(o)) continue;
						const r = g.category || "Other";
						h.has(r) || h.set(r, []), h.get(r).push(g);
					}
					return h;
				});
			function O() {
				return q(this, null, function* () {
					x.value = !0;
					try {
						const o = yield U("vehicle_maintenance.api.job_card.list_subsystems");
						c.value = (o == null ? void 0 : o.data) || [];
					} catch (o) {
						c.value = [];
					} finally {
						x.value = !1;
					}
				});
			}
			function j(o) {
				const h = $.value.includes(o) ? $.value.filter((g) => g !== o) : [...$.value, o];
				A("update:modelValue", h);
			}
			return (
				Ae(O),
				Be(w, (o) => {
					o || (R.value = "");
				}),
				(o, h) => (
					t(),
					s("div", null, [
						$.value.length
							? (t(),
							  s("div", na, [
									(t(!0),
									s(
										V,
										null,
										I(
											$.value,
											(g) => (
												t(),
												s(
													"span",
													{
														key: g,
														class: "inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded-full bg-brand-50 text-brand-700",
													},
													[
														J(a(g) + " ", 1),
														m.disabled
															? d("", !0)
															: (t(),
															  s(
																	"button",
																	{
																		key: 0,
																		type: "button",
																		onClick: (r) => j(g),
																		class: "text-brand-400 hover:text-red-500",
																		"aria-label": "Remove",
																	},
																	" × ",
																	8,
																	oa
															  )),
													]
												)
											)
										),
										128
									)),
							  ]))
							: d("", !0),
						m.disabled
							? d("", !0)
							: (t(),
							  s("div", la, [
									e(
										"button",
										{
											type: "button",
											onClick: h[0] || (h[0] = (g) => (w.value = !w.value)),
											class: "w-full flex items-center justify-between px-3 py-2 text-sm text-gray-600 bg-white border border-gray-300 rounded-lg hover:border-gray-400",
										},
										[
											e("span", null, a(w.value ? "Close" : "Add subsystems"), 1),
											e("span", ra, a($.value.length) + " selected", 1),
										]
									),
									w.value
										? (t(),
										  s("div", ia, [
												G(
													e(
														"input",
														{
															"onUpdate:modelValue":
																h[1] || (h[1] = (g) => (R.value = g)),
															type: "text",
															placeholder: "Search…",
															class: "w-full px-2 py-1.5 text-sm border border-gray-200 rounded mb-2 focus:outline-none focus:border-brand-500",
														},
														null,
														512
													),
													[[K, R.value]]
												),
												x.value
													? (t(), s("div", da, " Loading… "))
													: S.value.size
													? d("", !0)
													: (t(),
													  s(
															"div",
															ua,
															' No subsystems match "' + a(R.value) + '". ',
															1
													  )),
												(t(!0),
												s(
													V,
													null,
													I(
														S.value,
														([g, r]) => (
															t(),
															s(
																V,
																{ key: g },
																[
																	e("div", ca, a(g || "Uncategorized"), 1),
																	(t(!0),
																	s(
																		V,
																		null,
																		I(
																			r,
																			(v) => (
																				t(),
																				s(
																					"button",
																					{
																						key: v.name,
																						type: "button",
																						onClick: (y) =>
																							j(v.name),
																						class: "w-full text-left px-2 py-1.5 text-sm rounded hover:bg-brand-50 flex items-center justify-between",
																					},
																					[
																						e(
																							"span",
																							null,
																							a(
																								v.subsystem_name
																							),
																							1
																						),
																						$.value.includes(
																							v.name
																						)
																							? (t(),
																							  s(
																									"span",
																									ma,
																									" ✓ "
																							  ))
																							: d("", !0),
																					],
																					8,
																					va
																				)
																			)
																		),
																		128
																	)),
																],
																64
															)
														)
													),
													128
												)),
										  ]))
										: d("", !0),
							  ])),
						!m.disabled && !x.value && !c.value.length
							? (t(),
							  s(
									"div",
									ba,
									" No subsystems configured yet — add them under Setup › Fleet Service › Subsystem. "
							  ))
							: d("", !0),
					])
				)
			);
		},
	},
	ga = { class: "space-y-4" },
	ya = { key: 0, class: "bg-gray-50 border border-gray-200 rounded-xl p-3 text-xs grid grid-cols-2 gap-2" },
	xa = { class: "font-medium text-gray-800" },
	_a = { class: "font-medium text-gray-800" },
	fa = { key: 0 },
	ha = { class: "font-medium text-gray-800" },
	ka = { key: 1 },
	$a = { class: "font-medium text-gray-800" },
	Ca = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	wa = { class: "flex gap-2" },
	Sa = ["disabled", "onClick"],
	Ra = { class: "grid grid-cols-3 gap-2" },
	ja = { class: "block text-xs font-medium text-gray-500 mb-1" },
	Pa = ["value", "disabled", "onChange"],
	Aa = { class: "pt-1" },
	Ma = { key: 0, class: "flex flex-wrap gap-1.5 mb-1" },
	Ea = ["onClick"],
	Na = { key: 1, class: "text-xs text-gray-400 mb-1" },
	Fa = { key: 2, class: "relative" },
	Va = {
		key: 0,
		class: "absolute z-20 left-0 mt-1 w-64 bg-white border border-gray-200 rounded-lg shadow-lg max-h-56 overflow-y-auto p-2",
	},
	Oa = { key: 0, class: "text-xs text-gray-400 text-center py-2" },
	Ta = ["onClick"],
	Ia = { class: "text-gray-400" },
	Da = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	qa = { class: "flex items-start gap-2 text-sm" },
	La = ["checked", "disabled"],
	Ba = ["value", "disabled"],
	Ua = { class: "flex items-start gap-2 text-sm" },
	Wa = ["checked", "disabled"],
	Ja = ["value", "disabled"],
	Ha = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	Ga = { class: "flex items-center justify-between" },
	za = { class: "text-xs text-gray-500 grid grid-cols-2 gap-2" },
	Ya = { class: "font-medium text-gray-800" },
	Qa = { class: "flex gap-2" },
	Ka = ["disabled"],
	Xa = ["disabled"],
	Za = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	en = { class: "grid grid-cols-3 gap-2 text-xs" },
	tn = { class: "font-medium text-gray-800" },
	sn = { class: "font-medium text-gray-800" },
	an = { class: "font-medium text-gray-800" },
	nn = { class: "flex gap-2" },
	on = ["disabled"],
	ln = ["disabled"],
	rn = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	dn = { class: "flex gap-2" },
	un = ["disabled", "onClick"],
	cn = { key: 0 },
	vn = ["value", "disabled"],
	mn = { class: "grid grid-cols-2 gap-2" },
	bn = { class: "flex gap-2" },
	pn = ["disabled", "onClick"],
	gn = { class: "flex gap-2" },
	yn = ["disabled", "onClick"],
	xn = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	_n = { class: "grid grid-cols-4 gap-2 text-xs" },
	fn = { class: "font-medium text-gray-800" },
	hn = ["value", "disabled"],
	kn = { class: "font-medium text-gray-800" },
	$n = ["value", "disabled"],
	Cn = { class: "grid grid-cols-2 gap-2 text-xs" },
	wn = { class: "bg-gray-50 p-2 rounded" },
	Sn = { class: "font-medium text-gray-800" },
	Rn = { class: "bg-gray-50 p-2 rounded" },
	jn = { class: "font-medium text-gray-800" },
	Pn = { class: "flex gap-2" },
	An = ["disabled"],
	Mn = ["disabled"],
	En = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3" },
	Nn = { class: "grid grid-cols-2 gap-2 text-xs" },
	Fn = { class: "font-medium text-gray-800" },
	Vn = { class: "font-medium text-gray-800" },
	On = ["disabled"],
	Tn = { class: "bg-white border border-gray-200 rounded-xl p-4 space-y-2" },
	In = { class: "flex items-center justify-between" },
	Dn = { key: 0, class: "text-xs text-green-700" },
	qn = ["value", "disabled"],
	Ln = { key: 0, class: "text-xs text-gray-400" },
	Bn = {
		__name: "BreakdownDiagnosisPanel",
		props: {
			modelValue: { type: Object, default: () => ({}) },
			disabled: { type: Boolean, default: !1 },
			canEditRca: { type: Boolean, default: !0 },
		},
		emits: ["save", "save-groups"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = T(() => p.modelValue || {}),
				x = _(!1),
				w = _(""),
				R = _([]),
				$ = T(() => {
					const u = w.value.trim().toLowerCase();
					return u
						? R.value.filter((n) => (n.part_group_name || n.name || "").toLowerCase().includes(u))
						: R.value;
				});
			function S() {
				return q(this, null, function* () {
					try {
						const u = yield U("vehicle_maintenance.api.job_card.list_part_groups");
						R.value = (u == null ? void 0 : u.data) || [];
					} catch (u) {
						R.value = [];
					}
				});
			}
			function O(u) {
				const n = c.value.groups_impacted || [],
					B = n.includes(u) ? n.filter((P) => P !== u) : [...n, u];
				A("save-groups", B);
			}
			Ae(S);
			const j = T(() => {
					if (!c.value.remote_resolution_started_at) return 0;
					const u = new Date(c.value.remote_resolution_started_at),
						n = c.value.remote_resolution_failed_at
							? new Date(c.value.remote_resolution_failed_at)
							: new Date(),
						B = Math.round((n - u) / 6e4);
					return Number.isFinite(B) && B > 0 ? B : 0;
				}),
				o = T(
					() =>
						({
							"In Progress": "bg-amber-100 text-amber-700",
							Resolved: "bg-green-100 text-green-700",
							Failed: "bg-red-100 text-red-700",
						}[c.value.remote_resolution_status] || "bg-gray-100 text-gray-600")
				);
			function h(u) {
				A("save", u);
			}
			function g() {
				const u = (B) => String(B).padStart(2, "0"),
					n = new Date();
				return `${n.getFullYear()}-${u(n.getMonth() + 1)}-${u(n.getDate())} ${u(n.getHours())}:${u(
					n.getMinutes()
				)}:${u(n.getSeconds())}`;
			}
			function r(u) {
				if (!u) return "—";
				try {
					return new Date(u).toLocaleDateString("en-IN", {
						day: "numeric",
						month: "short",
						year: "numeric",
					});
				} catch (n) {
					return u;
				}
			}
			function v(u) {
				if (!u) return "";
				try {
					return new Date(u).toLocaleString("en-IN", {
						day: "numeric",
						month: "short",
						hour: "2-digit",
						minute: "2-digit",
					});
				} catch (n) {
					return u;
				}
			}
			function y(u) {
				return {
					Permanent: "border-green-500 bg-green-50 text-green-700",
					Temporary: "border-amber-500 bg-amber-50 text-amber-700",
					"Force Closed": "border-red-500 bg-red-50 text-red-700",
				}[u];
			}
			function b(u) {
				return u === "High"
					? "border-red-500 bg-red-50 text-red-700"
					: "border-green-500 bg-green-50 text-green-700";
			}
			return (u, n) => {
				var B, P;
				return (
					t(),
					s("div", ga, [
						c.value.last_pms_date || c.value.last_pms_odometer
							? (t(),
							  s("div", ya, [
									e("div", null, [
										n[17] ||
											(n[17] = e("span", { class: "text-gray-500" }, "Last PMS", -1)),
										e("div", xa, a(r(c.value.last_pms_date)), 1),
									]),
									e("div", null, [
										n[18] ||
											(n[18] = e(
												"span",
												{ class: "text-gray-500" },
												"Last Serviced Odometer",
												-1
											)),
										e(
											"div",
											_a,
											a((c.value.last_pms_odometer || 0).toLocaleString()) + " km ",
											1
										),
									]),
									c.value.last_service_tolerance_level
										? (t(),
										  s("div", fa, [
												n[19] ||
													(n[19] = e(
														"span",
														{ class: "text-gray-500" },
														"Last Tolerance",
														-1
													)),
												e("div", ha, a(c.value.last_service_tolerance_level), 1),
										  ]))
										: d("", !0),
									c.value.last_serviced_by
										? (t(),
										  s("div", ka, [
												n[20] ||
													(n[20] = e(
														"span",
														{ class: "text-gray-500" },
														"Last Serviced By",
														-1
													)),
												e("div", $a, a(c.value.last_serviced_by), 1),
										  ]))
										: d("", !0),
							  ]))
							: d("", !0),
						e("section", Ca, [
							n[23] ||
								(n[23] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"Incident",
									-1
								)),
							e("div", null, [
								n[21] ||
									(n[21] = e(
										"label",
										{ class: "block text-xs font-medium text-gray-500 mb-1" },
										"Incident Place",
										-1
									)),
								e("div", wa, [
									(t(),
									s(
										V,
										null,
										I(["Depot", "En Route"], (i) =>
											e(
												"button",
												{
													key: i,
													type: "button",
													disabled: m.disabled,
													onClick: (L) => h({ incident_place: i }),
													class: D([
														"flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all disabled:opacity-50",
														c.value.incident_place === i
															? "border-brand-500 bg-brand-50 text-brand-700"
															: "border-gray-200 text-gray-500 hover:border-gray-300",
													]),
												},
												a(i),
												11,
												Sa
											)
										),
										64
									)),
								]),
							]),
							e("div", Ra, [
								(t(),
								s(
									V,
									null,
									I([1, 2, 3], (i) =>
										e("div", { key: i }, [
											e("label", ja, " Fault Code " + a(i), 1),
											e(
												"input",
												{
													value: c.value[`fault_code_${i}`],
													disabled: m.disabled,
													onChange: (L) =>
														h({ [`fault_code_${i}`]: L.target.value }),
													type: "text",
													placeholder: "optional",
													class: "input-field",
												},
												null,
												40,
												Pa
											),
										])
									),
									64
								)),
							]),
							e("div", Aa, [
								n[22] ||
									(n[22] = e(
										"label",
										{ class: "block text-xs font-medium text-gray-500 mb-1" },
										[
											J(" Groups Impacted "),
											e(
												"span",
												{ class: "text-gray-400 font-normal" },
												"(multi-select)"
											),
										],
										-1
									)),
								(B = c.value.groups_impacted) != null && B.length
									? (t(),
									  s("div", Ma, [
											(t(!0),
											s(
												V,
												null,
												I(
													c.value.groups_impacted,
													(i) => (
														t(),
														s(
															"span",
															{
																key: i,
																class: "inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-brand-50 text-brand-700",
															},
															[
																J(a(i) + " ", 1),
																m.disabled
																	? d("", !0)
																	: (t(),
																	  s(
																			"button",
																			{
																				key: 0,
																				type: "button",
																				onClick: (L) => O(i),
																				class: "text-brand-400 hover:text-red-500",
																				"aria-label": "Remove",
																			},
																			" × ",
																			8,
																			Ea
																	  )),
															]
														)
													)
												),
												128
											)),
									  ]))
									: (t(), s("div", Na, "None selected")),
								m.disabled
									? d("", !0)
									: (t(),
									  s("div", Fa, [
											e(
												"button",
												{
													type: "button",
													onClick: n[0] || (n[0] = (i) => (x.value = !x.value)),
													class: "text-xs text-brand-600 hover:underline",
												},
												a(x.value ? "Close" : "+ Add groups"),
												1
											),
											x.value
												? (t(),
												  s("div", Va, [
														G(
															e(
																"input",
																{
																	"onUpdate:modelValue":
																		n[1] || (n[1] = (i) => (w.value = i)),
																	type: "text",
																	placeholder: "Search groups…",
																	class: "w-full px-2 py-1.5 text-xs border border-gray-200 rounded mb-2 focus:outline-none focus:border-brand-500",
																},
																null,
																512
															),
															[[K, w.value]]
														),
														$.value.length
															? d("", !0)
															: (t(), s("div", Oa, " No groups match. ")),
														(t(!0),
														s(
															V,
															null,
															I(
																$.value,
																(i) => (
																	t(),
																	s(
																		"button",
																		{
																			key: i.name,
																			type: "button",
																			onClick: (L) => O(i.name),
																			class: "w-full text-left px-2 py-1.5 text-xs rounded hover:bg-brand-50 flex justify-between",
																		},
																		[
																			e(
																				"span",
																				null,
																				a(
																					i.part_group_name ||
																						i.name
																				),
																				1
																			),
																			e("span", Ia, a(i.bus_system), 1),
																		],
																		8,
																		Ta
																	)
																)
															),
															128
														)),
												  ]))
												: d("", !0),
									  ])),
							]),
						]),
						e("section", Da, [
							n[26] ||
								(n[26] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"SOP Override",
									-1
								)),
							n[27] ||
								(n[27] = e(
									"p",
									{ class: "text-xs text-gray-500 -mt-1" },
									" PRD p.15 step 8 — tick only if no documented process applies or the documented process didn't resolve the issue. ",
									-1
								)),
							e("div", null, [
								e("label", qa, [
									e(
										"input",
										{
											type: "checkbox",
											checked: !!c.value.force_override,
											disabled: m.disabled,
											onChange:
												n[2] ||
												(n[2] = (i) =>
													h({ force_override: i.target.checked ? 1 : 0 })),
											class: "mt-0.5",
										},
										null,
										40,
										La
									),
									n[24] ||
										(n[24] = e(
											"span",
											null,
											[
												e("span", { class: "font-medium" }, "Force Override"),
												e(
													"span",
													{ class: "block text-xs text-gray-500" },
													"No SOP available for this group"
												),
											],
											-1
										)),
								]),
								c.value.force_override
									? (t(),
									  s(
											"textarea",
											{
												key: 0,
												value: c.value.force_override_reason || "",
												disabled: m.disabled,
												onChange:
													n[3] ||
													(n[3] = (i) =>
														h({ force_override_reason: i.target.value })),
												rows: "2",
												placeholder: "Why was there no documented process?",
												class: "input-field mt-2",
											},
											null,
											40,
											Ba
									  ))
									: d("", !0),
							]),
							e("div", null, [
								e("label", Ua, [
									e(
										"input",
										{
											type: "checkbox",
											checked: !!c.value.process_override,
											disabled: m.disabled,
											onChange:
												n[4] ||
												(n[4] = (i) =>
													h({ process_override: i.target.checked ? 1 : 0 })),
											class: "mt-0.5",
										},
										null,
										40,
										Wa
									),
									n[25] ||
										(n[25] = e(
											"span",
											null,
											[
												e("span", { class: "font-medium" }, "Process Override"),
												e(
													"span",
													{ class: "block text-xs text-gray-500" },
													"SOP was attempted but didn't resolve — record alternate steps taken"
												),
											],
											-1
										)),
								]),
								c.value.process_override
									? (t(),
									  s(
											"textarea",
											{
												key: 0,
												value: c.value.process_override_steps || "",
												disabled: m.disabled,
												onChange:
													n[5] ||
													(n[5] = (i) =>
														h({ process_override_steps: i.target.value })),
												rows: "3",
												placeholder:
													"Step-by-step alternate troubleshooting actions the SE performed",
												class: "input-field mt-2",
											},
											null,
											40,
											Ja
									  ))
									: d("", !0),
							]),
						]),
						e("section", Ha, [
							e("div", Ga, [
								n[28] ||
									(n[28] = e(
										"h4",
										{ class: "text-sm font-semibold text-gray-800" },
										"Remote Resolution (30-min SLA)",
										-1
									)),
								e(
									"span",
									{ class: D(["text-xs font-medium px-2 py-0.5 rounded-full", o.value]) },
									a(c.value.remote_resolution_status || "Not started"),
									3
								),
							]),
							e("div", za, [
								e("div", null, [
									n[29] || (n[29] = e("span", null, "Started", -1)),
									e("div", Ya, a(v(c.value.remote_resolution_started_at)), 1),
								]),
								e("div", null, [
									n[30] || (n[30] = e("span", null, "Elapsed", -1)),
									e(
										"div",
										{
											class: D([
												"font-medium",
												j.value >= 30 ? "text-red-700" : "text-gray-800",
											]),
										},
										a(j.value) + "m " + a(j.value >= 30 ? "· SLA breached" : ""),
										3
									),
								]),
							]),
							e("div", Qa, [
								e(
									"button",
									{
										type: "button",
										disabled:
											m.disabled || c.value.remote_resolution_status === "Resolved",
										onClick:
											n[6] ||
											(n[6] = (i) => h({ remote_resolution_status: "Resolved" })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50",
									},
									" Mark Resolved ",
									8,
									Ka
								),
								e(
									"button",
									{
										type: "button",
										disabled: m.disabled || c.value.remote_resolution_status === "Failed",
										onClick:
											n[7] || (n[7] = (i) => h({ remote_resolution_status: "Failed" })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-red-500 text-red-700 hover:bg-red-50 disabled:opacity-50",
									},
									" Mark Failed ",
									8,
									Xa
								),
							]),
						]),
						e("section", Za, [
							n[34] ||
								(n[34] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"Travel to BD Location",
									-1
								)),
							e("div", en, [
								e("div", null, [
									n[31] ||
										(n[31] = e("span", { class: "text-gray-500" }, "Start Travel", -1)),
									e("div", tn, a(v(c.value.travel_started_at) || "—"), 1),
								]),
								e("div", null, [
									n[32] || (n[32] = e("span", { class: "text-gray-500" }, "Arrived", -1)),
									e("div", sn, a(v(c.value.arrived_at_location) || "—"), 1),
								]),
								e("div", null, [
									n[33] || (n[33] = e("span", { class: "text-gray-500" }, "Duration", -1)),
									e(
										"div",
										an,
										a(
											c.value.travel_duration_minutes
												? `${c.value.travel_duration_minutes.toFixed(0)} min`
												: "—"
										),
										1
									),
								]),
							]),
							e("div", nn, [
								e(
									"button",
									{
										type: "button",
										disabled: m.disabled || c.value.travel_started_at,
										onClick: n[8] || (n[8] = (i) => h({ travel_started_at: g() })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50",
									},
									" Mark Start Travel ",
									8,
									on
								),
								e(
									"button",
									{
										type: "button",
										disabled:
											m.disabled ||
											!c.value.travel_started_at ||
											c.value.arrived_at_location,
										onClick: n[9] || (n[9] = (i) => h({ arrived_at_location: g() })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50",
									},
									" Mark Arrived ",
									8,
									ln
								),
							]),
						]),
						e("section", rn, [
							n[39] ||
								(n[39] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"Resolution",
									-1
								)),
							e("div", null, [
								n[35] ||
									(n[35] = e(
										"label",
										{ class: "block text-xs font-medium text-gray-500 mb-1" },
										"Fix Type",
										-1
									)),
								e("div", dn, [
									(t(),
									s(
										V,
										null,
										I(["Permanent", "Temporary", "Force Closed"], (i) =>
											e(
												"button",
												{
													key: i,
													type: "button",
													disabled: m.disabled,
													onClick: (L) => h({ fix_type: i }),
													class: D([
														"flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all disabled:opacity-50",
														c.value.fix_type === i
															? y(i)
															: "border-gray-200 text-gray-500 hover:border-gray-300",
													]),
												},
												a(i),
												11,
												un
											)
										),
										64
									)),
								]),
							]),
							c.value.fix_type === "Temporary"
								? (t(),
								  s("div", cn, [
										n[36] ||
											(n[36] = e(
												"label",
												{ class: "block text-xs font-medium text-gray-500 mb-1" },
												"Escalate to Next-Level Engineer",
												-1
											)),
										e(
											"input",
											{
												value: c.value.next_level_engineer,
												disabled: m.disabled,
												onChange:
													n[10] ||
													(n[10] = (i) =>
														h({ next_level_engineer: i.target.value })),
												type: "text",
												placeholder: "User ID of senior engineer",
												class: "input-field",
											},
											null,
											40,
											vn
										),
								  ]))
								: d("", !0),
							e("div", mn, [
								e("div", null, [
									n[37] ||
										(n[37] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Recurrence Risk (this vehicle)",
											-1
										)),
									e("div", bn, [
										(t(),
										s(
											V,
											null,
											I(["Low", "High"], (i) =>
												e(
													"button",
													{
														key: i,
														type: "button",
														disabled: m.disabled,
														onClick: (L) => h({ recurrence_risk: i }),
														class: D([
															"flex-1 py-1.5 text-xs font-medium rounded-lg border-2 disabled:opacity-50",
															c.value.recurrence_risk === i
																? b(i)
																: "border-gray-200 text-gray-500 hover:border-gray-300",
														]),
													},
													a(i),
													11,
													pn
												)
											),
											64
										)),
									]),
								]),
								e("div", null, [
									n[38] ||
										(n[38] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Occurrence Risk (fleet-wide)",
											-1
										)),
									e("div", gn, [
										(t(),
										s(
											V,
											null,
											I(["Low", "High"], (i) =>
												e(
													"button",
													{
														key: i,
														type: "button",
														disabled: m.disabled,
														onClick: (L) => h({ occurrence_risk: i }),
														class: D([
															"flex-1 py-1.5 text-xs font-medium rounded-lg border-2 disabled:opacity-50",
															c.value.occurrence_risk === i
																? b(i)
																: "border-gray-200 text-gray-500 hover:border-gray-300",
														]),
													},
													a(i),
													11,
													yn
												)
											),
											64
										)),
									]),
								]),
							]),
						]),
						e("section", xn, [
							n[46] ||
								(n[46] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"Trial Trip",
									-1
								)),
							e("div", _n, [
								e("div", null, [
									n[40] || (n[40] = e("span", { class: "text-gray-500" }, "Start", -1)),
									e("div", fn, a(v(c.value.trial_trip_started_at) || "—"), 1),
								]),
								e("div", null, [
									n[41] ||
										(n[41] = e(
											"label",
											{ class: "block text-gray-500 mb-1" },
											"Start km",
											-1
										)),
									e(
										"input",
										{
											value: c.value.trial_trip_start_km,
											disabled: m.disabled,
											onChange:
												n[11] ||
												(n[11] = (i) =>
													h({ trial_trip_start_km: Number(i.target.value) || 0 })),
											type: "number",
											min: "0",
											class: "input-field",
										},
										null,
										40,
										hn
									),
								]),
								e("div", null, [
									n[42] || (n[42] = e("span", { class: "text-gray-500" }, "End", -1)),
									e("div", kn, a(v(c.value.trial_trip_ended_at) || "—"), 1),
								]),
								e("div", null, [
									n[43] ||
										(n[43] = e(
											"label",
											{ class: "block text-gray-500 mb-1" },
											"End km",
											-1
										)),
									e(
										"input",
										{
											value: c.value.trial_trip_end_km,
											disabled: m.disabled,
											onChange:
												n[12] ||
												(n[12] = (i) =>
													h({ trial_trip_end_km: Number(i.target.value) || 0 })),
											type: "number",
											min: "0",
											class: "input-field",
										},
										null,
										40,
										$n
									),
								]),
							]),
							e("div", Cn, [
								e("div", wn, [
									n[44] || (n[44] = e("span", { class: "text-gray-500" }, "Dead km", -1)),
									e(
										"div",
										Sn,
										a((P = c.value.trial_trip_distance_km) != null ? P : "—"),
										1
									),
								]),
								e("div", Rn, [
									n[45] || (n[45] = e("span", { class: "text-gray-500" }, "Duration", -1)),
									e(
										"div",
										jn,
										a(
											c.value.trial_trip_duration_minutes
												? `${c.value.trial_trip_duration_minutes.toFixed(0)} min`
												: "—"
										),
										1
									),
								]),
							]),
							e("div", Pn, [
								e(
									"button",
									{
										type: "button",
										disabled: m.disabled || c.value.trial_trip_started_at,
										onClick: n[13] || (n[13] = (i) => h({ trial_trip_started_at: g() })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50",
									},
									" Start Trial Trip ",
									8,
									An
								),
								e(
									"button",
									{
										type: "button",
										disabled:
											m.disabled ||
											!c.value.trial_trip_started_at ||
											c.value.trial_trip_ended_at,
										onClick: n[14] || (n[14] = (i) => h({ trial_trip_ended_at: g() })),
										class: "flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50",
									},
									" End Trial Trip ",
									8,
									Mn
								),
							]),
						]),
						e("section", En, [
							n[49] ||
								(n[49] = e(
									"h4",
									{ class: "text-sm font-semibold text-gray-800" },
									"Handover",
									-1
								)),
							e("div", Nn, [
								e("div", null, [
									n[47] ||
										(n[47] = e(
											"span",
											{ class: "text-gray-500" },
											"Vehicle Handed Over",
											-1
										)),
									e("div", Fn, a(v(c.value.vehicle_handover_at) || "—"), 1),
								]),
								e("div", null, [
									n[48] ||
										(n[48] = e("span", { class: "text-gray-500" }, "Total Downtime", -1)),
									e(
										"div",
										Vn,
										a(
											c.value.total_downtime_minutes
												? `${c.value.total_downtime_minutes.toFixed(0)} min`
												: "—"
										),
										1
									),
								]),
							]),
							e(
								"button",
								{
									type: "button",
									disabled: m.disabled || c.value.vehicle_handover_at,
									onClick: n[15] || (n[15] = (i) => h({ vehicle_handover_at: g() })),
									class: "w-full py-2 text-xs font-medium rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50",
								},
								" Mark Vehicle Handed Over ",
								8,
								On
							),
						]),
						e("section", Tn, [
							e("div", In, [
								n[50] ||
									(n[50] = e(
										"h4",
										{ class: "text-sm font-semibold text-gray-800" },
										"Root Cause Analysis",
										-1
									)),
								c.value.rca_received_at
									? (t(), s("span", Dn, " Received " + a(v(c.value.rca_received_at)), 1))
									: d("", !0),
							]),
							e(
								"textarea",
								{
									value: c.value.rca_notes || "",
									disabled: m.disabled || !m.canEditRca,
									onChange: n[16] || (n[16] = (i) => h({ rca_notes: i.target.value })),
									rows: "4",
									placeholder:
										"Aftersales Engineer — root cause, fix narrative, customer-facing summary…",
									class: "input-field",
								},
								null,
								40,
								qn
							),
							!m.canEditRca && !m.disabled
								? (t(),
								  s(
										"div",
										Ln,
										" Only Aftersales Eng / DM / N. Maint. Head can edit the RCA. "
								  ))
								: d("", !0),
						]),
					])
				);
			};
		},
	},
	Un = Z(Bn, [["__scopeId", "data-v-8efbda22"]]),
	Wn = { class: "space-y-3" },
	Jn = { class: "flex items-center justify-between" },
	Hn = {
		key: 0,
		class: "text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl",
	},
	Gn = { class: "flex items-start gap-3" },
	zn = { class: "flex-1" },
	Yn = ["value", "disabled", "onChange"],
	Qn = { class: "w-36" },
	Kn = { key: 0, class: "ml-1 text-gray-500" },
	Xn = ["onClick"],
	Zn = { class: "flex gap-2" },
	eo = ["disabled", "onClick"],
	to = { class: "grid grid-cols-2 gap-3" },
	so = ["value", "disabled", "onChange"],
	ao = ["onChange"],
	no = { key: 1, class: "text-xs text-green-600 mt-0.5" },
	oo = ["value", "disabled", "onChange"],
	lo = ["onChange"],
	ro = { key: 1, class: "text-xs text-green-600 mt-0.5" },
	io = { key: 0, class: "text-xs text-gray-600" },
	uo = { class: "mt-2 space-y-2" },
	co = ["value", "disabled", "onChange"],
	vo = { key: 1 },
	mo = ["value", "disabled", "onChange"],
	bo = { key: 2, class: "flex flex-wrap gap-2 pt-1" },
	po = ["disabled", "onClick"],
	go = ["disabled", "onClick"],
	yo = ["disabled", "onClick"],
	xo = ["onClick"],
	_o = {
		__name: "SoftwareUpdatePanel",
		props: {
			modelValue: { type: Array, default: () => [] },
			disabled: { type: Boolean, default: !1 },
			customerView: { type: Boolean, default: !1 },
		},
		emits: ["update:modelValue"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = ["Performance Improvement", "Regular Update", "Emergency Update (Bug Fix)"],
				x = T(() => p.modelValue || []);
			function w(r) {
				A("update:modelValue", r);
			}
			function R() {
				w([
					...x.value,
					{
						component: "",
						reason: "Regular Update",
						status: "Pending",
						retry_count: 0,
						pre_version: "",
						post_version: "",
						pre_version_photo: null,
						post_version_photo: null,
						calibration_values: "",
						failure_notes: "",
					},
				]);
			}
			function $(r) {
				w(x.value.filter((v, y) => y !== r));
			}
			function S(r, v, y) {
				const b = x.value.map((u, n) => (n === r ? te(Q({}, u), { [v]: y }) : u));
				w(b);
			}
			function O(r) {
				S(r, "status", "Success");
			}
			function j(r) {
				S(r, "status", "Failed");
			}
			function o(r) {
				x.value[r];
				const v = x.value.map((y, b) =>
					b === r
						? te(Q({}, y), { status: "In Progress", retry_count: (y.retry_count || 0) + 1 })
						: y
				);
				w(v);
			}
			function h(r, v, y) {
				var u;
				const b = (u = y.target.files) == null ? void 0 : u[0];
				b && S(r, v, b);
			}
			function g(r) {
				return {
					Pending: "bg-gray-100 text-gray-600",
					"In Progress": "bg-amber-100 text-amber-700",
					Success: "bg-green-100 text-green-700",
					Failed: "bg-red-100 text-red-700",
				}[r || "Pending"];
			}
			return (r, v) => (
				t(),
				s("div", Wn, [
					e("div", Jn, [
						v[0] ||
							(v[0] = e(
								"h3",
								{ class: "text-sm font-semibold text-gray-800" },
								"Software Components",
								-1
							)),
						m.disabled
							? d("", !0)
							: (t(),
							  s(
									"button",
									{
										key: 0,
										type: "button",
										onClick: R,
										class: "px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg",
									},
									" + Add Component "
							  )),
					]),
					x.value.length
						? d("", !0)
						: (t(), s("div", Hn, ' No components added yet — tap "+ Add Component" to start. ')),
					(t(!0),
					s(
						V,
						null,
						I(
							x.value,
							(y, b) => (
								t(),
								s(
									"div",
									{
										key: b,
										class: "bg-white border border-gray-200 rounded-xl p-4 space-y-3",
									},
									[
										e("div", Gn, [
											e("div", zn, [
												v[1] ||
													(v[1] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Component",
														-1
													)),
												e(
													"input",
													{
														value: y.component,
														disabled: m.disabled,
														onChange: (u) => S(b, "component", u.target.value),
														type: "text",
														placeholder: "e.g. BMS, VCU, HVAC controller",
														class: "input-field",
													},
													null,
													40,
													Yn
												),
											]),
											e("div", Qn, [
												v[2] ||
													(v[2] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Status",
														-1
													)),
												e(
													"span",
													{
														class: D([
															"inline-flex items-center px-2 py-1.5 text-xs font-medium rounded-lg w-full justify-center",
															g(y.status),
														]),
													},
													[
														J(a(y.status || "Pending") + " ", 1),
														y.retry_count
															? (t(),
															  s(
																	"span",
																	Kn,
																	" (retry " + a(y.retry_count) + ") ",
																	1
															  ))
															: d("", !0),
													],
													2
												),
											]),
											m.disabled
												? d("", !0)
												: (t(),
												  s(
														"button",
														{
															key: 0,
															type: "button",
															onClick: (u) => $(b),
															class: "mt-6 text-red-500 hover:text-red-600 text-sm px-2",
															"aria-label": "Remove",
														},
														" ✕ ",
														8,
														Xn
												  )),
										]),
										e("div", null, [
											v[3] ||
												(v[3] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Reason for Update",
													-1
												)),
											e("div", Zn, [
												(t(),
												s(
													V,
													null,
													I(c, (u) =>
														e(
															"button",
															{
																key: u,
																type: "button",
																disabled: m.disabled,
																onClick: (n) => S(b, "reason", u),
																class: D([
																	"flex-1 py-1.5 text-[11px] font-medium rounded-lg border-2 transition-all disabled:opacity-50",
																	y.reason === u
																		? "border-brand-500 bg-brand-50 text-brand-700"
																		: "border-gray-200 text-gray-500 hover:border-gray-300",
																]),
															},
															a(u),
															11,
															eo
														)
													),
													64
												)),
											]),
										]),
										e("div", to, [
											e("div", null, [
												v[4] ||
													(v[4] = e(
														"label",
														{ class: "block text-xs text-gray-500 mb-1" },
														"Pre Version",
														-1
													)),
												e(
													"input",
													{
														value: y.pre_version,
														disabled: m.disabled,
														onChange: (u) => S(b, "pre_version", u.target.value),
														type: "text",
														placeholder: "e.g. 2.4.1",
														class: "input-field",
													},
													null,
													40,
													so
												),
												m.disabled
													? d("", !0)
													: (t(),
													  s(
															"input",
															{
																key: 0,
																type: "file",
																accept: "image/*",
																onChange: (u) => h(b, "pre_version_photo", u),
																class: "mt-1 text-xs",
															},
															null,
															40,
															ao
													  )),
												y.pre_version_photo
													? (t(), s("div", no, " ✓ Photo attached "))
													: d("", !0),
											]),
											e("div", null, [
												v[5] ||
													(v[5] = e(
														"label",
														{ class: "block text-xs text-gray-500 mb-1" },
														"Post Version",
														-1
													)),
												e(
													"input",
													{
														value: y.post_version,
														disabled: m.disabled,
														onChange: (u) => S(b, "post_version", u.target.value),
														type: "text",
														placeholder: "e.g. 2.4.2",
														class: "input-field",
													},
													null,
													40,
													oo
												),
												m.disabled
													? d("", !0)
													: (t(),
													  s(
															"input",
															{
																key: 0,
																type: "file",
																accept: "image/*",
																onChange: (u) =>
																	h(b, "post_version_photo", u),
																class: "mt-1 text-xs",
															},
															null,
															40,
															lo
													  )),
												y.post_version_photo
													? (t(), s("div", ro, " ✓ Photo attached "))
													: d("", !0),
											]),
										]),
										m.customerView
											? d("", !0)
											: (t(),
											  s("details", io, [
													v[6] ||
														(v[6] = e(
															"summary",
															{ class: "cursor-pointer font-medium" },
															" Calibration (internal only) ",
															-1
														)),
													e("div", uo, [
														e(
															"textarea",
															{
																value: y.calibration_values,
																disabled: m.disabled,
																onChange: (u) =>
																	S(
																		b,
																		"calibration_values",
																		u.target.value
																	),
																rows: "2",
																placeholder: "Calibration values per SOP…",
																class: "input-field",
															},
															null,
															40,
															co
														),
													]),
											  ])),
										y.status === "Failed"
											? (t(),
											  s("div", vo, [
													v[7] ||
														(v[7] = e(
															"label",
															{ class: "block text-xs text-red-600 mb-1" },
															"Failure Notes",
															-1
														)),
													e(
														"textarea",
														{
															value: y.failure_notes,
															disabled: m.disabled,
															onChange: (u) =>
																S(b, "failure_notes", u.target.value),
															rows: "2",
															placeholder:
																"What failed? (log messages, error codes, tool feedback)",
															class: "input-field",
														},
														null,
														40,
														mo
													),
											  ]))
											: d("", !0),
										m.disabled
											? d("", !0)
											: (t(),
											  s("div", bo, [
													e(
														"button",
														{
															type: "button",
															disabled: y.status === "In Progress",
															onClick: (u) => S(b, "status", "In Progress"),
															class: "px-3 py-1.5 text-xs rounded-lg border-2 border-amber-400 text-amber-700 hover:bg-amber-50 disabled:opacity-50",
														},
														" Start ",
														8,
														po
													),
													e(
														"button",
														{
															type: "button",
															disabled: y.status === "Success",
															onClick: (u) => O(b),
															class: "px-3 py-1.5 text-xs rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50",
														},
														" Mark Success ",
														8,
														go
													),
													e(
														"button",
														{
															type: "button",
															disabled: y.status === "Failed",
															onClick: (u) => j(b),
															class: "px-3 py-1.5 text-xs rounded-lg border-2 border-red-500 text-red-700 hover:bg-red-50 disabled:opacity-50",
														},
														" Mark Failed ",
														8,
														yo
													),
													y.status === "Failed"
														? (t(),
														  s(
																"button",
																{
																	key: 0,
																	type: "button",
																	onClick: (u) => o(b),
																	class: "px-3 py-1.5 text-xs rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50",
																},
																" Retry ",
																8,
																xo
														  ))
														: d("", !0),
											  ])),
									]
								)
							)
						),
						128
					)),
				])
			);
		},
	},
	qe = Z(_o, [["__scopeId", "data-v-ff72e8a2"]]),
	fo = { class: "space-y-3" },
	ho = { class: "flex items-center justify-between" },
	ko = {
		key: 1,
		class: "text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl",
	},
	$o = { key: 2, class: "text-center py-4 text-sm text-gray-400" },
	Co = { class: "flex-1" },
	wo = { class: "font-medium text-gray-800" },
	So = { class: "text-xs text-gray-500" },
	Ro = ["disabled", "onClick"],
	jo = { class: "bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4" },
	Po = { class: "relative" },
	Ao = {
		key: 0,
		class: "absolute z-10 mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg max-h-48 overflow-y-auto",
	},
	Mo = ["onClick"],
	Eo = { class: "font-medium text-gray-800" },
	No = { class: "text-xs text-gray-500" },
	Fo = { key: 0, class: "text-xs text-green-700 mt-1" },
	Vo = { class: "grid grid-cols-2 gap-3" },
	Oo = ["value"],
	To = { key: 0, class: "text-sm text-red-600" },
	Io = { class: "flex gap-2 justify-end pt-2" },
	Do = ["disabled"],
	qo = {
		__name: "InventoryRequestPanel",
		props: { jobCardName: { type: String, required: !0 }, roles: { type: Array, default: () => [] } },
		setup(m) {
			const C = m,
				p = _([]),
				A = _(!1),
				c = _(null),
				x = _(null),
				w = _(!1),
				R = _(!1),
				$ = _(""),
				S = _(""),
				O = _([]),
				j = _(!1),
				o = _({ part: "", quantity: 1, urgency_level: "Medium", notes: "" }),
				h = ["Low", "Medium", "High", "Critical"],
				g = T(() =>
					C.roles.some((F) => ["Service Engineer", "Technician", "Depot Manager"].includes(F))
				),
				r = T(() => C.roles.some((F) => ["Depot Manager", "Central Ops"].includes(F))),
				v = T(() => C.roles.some((F) => ["Technician", "Service Engineer"].includes(F)));
			function y() {
				return q(this, null, function* () {
					A.value = !0;
					try {
						const F = yield U(
							"vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card",
							{ job_card_ref: C.jobCardName }
						);
						p.value = (F == null ? void 0 : F.data) || [];
					} catch (F) {
						p.value = [];
					} finally {
						A.value = !1;
					}
				});
			}
			function b(F) {
				return F.status === "Requested" && r.value
					? { label: "Allocate", target: "Parts Allocated" }
					: F.status === "Parts Allocated" && r.value
					? { label: "Mark Issued", target: "Parts Issued" }
					: F.status === "Parts Issued" && v.value
					? { label: "Acknowledge Receipt", target: "Received" }
					: null;
			}
			function u(F) {
				return q(this, null, function* () {
					var E;
					const N = b(F);
					if (N) {
						(x.value = F.name), (c.value = null);
						try {
							yield U("vehicle_maintenance.api.job_card.advance_inventory_status", {
								inventory_request_name: F.name,
								next_status: N.target,
							}),
								(c.value = { ok: !0, msg: `Moved to ${N.target}.` }),
								yield y();
						} catch (Y) {
							c.value = {
								ok: !1,
								msg:
									((E = Y == null ? void 0 : Y.messages) == null ? void 0 : E[0]) ||
									`Failed to move to ${N.target}.`,
							};
						} finally {
							x.value = null;
						}
					}
				});
			}
			function n() {
				(o.value = { part: "", quantity: 1, urgency_level: "Medium", notes: "" }),
					(S.value = ""),
					(O.value = []),
					($.value = ""),
					(w.value = !0);
			}
			function B() {
				w.value = !1;
			}
			let P = null;
			function i() {
				if ((clearTimeout(P), S.value.length < 2)) {
					O.value = [];
					return;
				}
				P = setTimeout(
					() =>
						q(this, null, function* () {
							try {
								const F = yield U(
									"vehicle_maintenance.fleet_service.doctype.part.part.search_parts",
									{ query: S.value, limit: 10 }
								);
								O.value = (F == null ? void 0 : F.data) || [];
							} catch (F) {
								O.value = [];
							}
						}),
					250
				);
			}
			function L(F) {
				(o.value.part = F.part_code),
					(S.value = `${F.part_code} — ${F.part_name}`),
					(j.value = !1),
					(O.value = []);
			}
			function H() {
				return q(this, null, function* () {
					var F;
					(R.value = !0), ($.value = "");
					try {
						yield U("vehicle_maintenance.api.job_card.create_inventory_request", {
							job_card_name: C.jobCardName,
							part: o.value.part,
							quantity: o.value.quantity,
							urgency_level: o.value.urgency_level,
							notes: o.value.notes,
						}),
							(c.value = { ok: !0, msg: "Inventory request raised." }),
							B(),
							yield y();
					} catch (N) {
						$.value =
							((F = N == null ? void 0 : N.messages) == null ? void 0 : F[0]) ||
							"Failed to raise request.";
					} finally {
						R.value = !1;
					}
				});
			}
			function xe(F) {
				return (
					{
						Requested: "bg-amber-100 text-amber-700",
						"Parts Allocated": "bg-blue-100 text-blue-700",
						"Parts Issued": "bg-indigo-100 text-indigo-700",
						Received: "bg-green-100 text-green-700",
					}[F] || "bg-gray-100 text-gray-600"
				);
			}
			function Ee(F) {
				return {
					Low: "text-gray-500",
					Medium: "text-amber-600",
					High: "text-orange-600",
					Critical: "text-red-600 font-medium",
				}[F];
			}
			return (
				Ae(y),
				(F, N) => (
					t(),
					s("div", fo, [
						e("div", ho, [
							N[5] ||
								(N[5] = e(
									"h3",
									{ class: "text-sm font-semibold text-gray-800" },
									"Inventory Requests",
									-1
								)),
							g.value
								? (t(),
								  s(
										"button",
										{
											key: 0,
											type: "button",
											onClick: n,
											class: "px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg",
										},
										" + New Request "
								  ))
								: d("", !0),
						]),
						c.value
							? (t(),
							  s(
									"div",
									{
										key: 0,
										class: D([
											"p-2 text-xs rounded-lg",
											c.value.ok
												? "bg-green-50 text-green-700"
												: "bg-red-50 text-red-700",
										]),
									},
									a(c.value.msg),
									3
							  ))
							: d("", !0),
						!A.value && !p.value.length
							? (t(), s("div", ko, " No inventory requests yet. "))
							: d("", !0),
						A.value ? (t(), s("div", $o, "Loading…")) : d("", !0),
						(t(!0),
						s(
							V,
							null,
							I(
								p.value,
								(E) => (
									t(),
									s(
										"div",
										{
											key: E.name,
											class: "bg-white border border-gray-200 rounded-xl p-3 flex items-center gap-3 text-sm",
										},
										[
											e("div", Co, [
												e("div", wo, a(E.part_name || E.part), 1),
												e("div", So, [
													J(
														a(E.part_group) +
															" · " +
															a(E.quantity) +
															" " +
															a(E.uom || "") +
															" · ",
														1
													),
													e(
														"span",
														{ class: D(Ee(E.urgency_level)) },
														a(E.urgency_level),
														3
													),
												]),
											]),
											e(
												"span",
												{
													class: D([
														"text-xs font-medium px-2 py-0.5 rounded-full",
														xe(E.status),
													]),
												},
												a(E.status),
												3
											),
											b(E)
												? (t(),
												  s(
														"button",
														{
															key: 0,
															type: "button",
															disabled: x.value === E.name,
															onClick: (Y) => u(E),
															class: "px-3 py-1.5 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50",
														},
														a(b(E).label),
														9,
														Ro
												  ))
												: d("", !0),
										]
									)
								)
							),
							128
						)),
						w.value
							? (t(),
							  s(
									"div",
									{
										key: 3,
										class: "fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4",
										onClick: Me(B, ["self"]),
									},
									[
										e("div", jo, [
											N[10] ||
												(N[10] = e(
													"h3",
													{ class: "text-lg font-semibold text-gray-900" },
													"Raise Inventory Request",
													-1
												)),
											e("div", null, [
												N[6] ||
													(N[6] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Part",
														-1
													)),
												e("div", Po, [
													G(
														e(
															"input",
															{
																"onUpdate:modelValue":
																	N[0] || (N[0] = (E) => (S.value = E)),
																type: "text",
																placeholder: "Search parts…",
																class: "input-field",
																onInput: i,
																onFocus:
																	N[1] || (N[1] = (E) => (j.value = !0)),
															},
															null,
															544
														),
														[[K, S.value]]
													),
													j.value && O.value.length
														? (t(),
														  s("div", Ao, [
																(t(!0),
																s(
																	V,
																	null,
																	I(
																		O.value,
																		(E) => (
																			t(),
																			s(
																				"button",
																				{
																					key: E.part_code,
																					type: "button",
																					onClick: (Y) => L(E),
																					class: "w-full text-left px-3 py-2 text-sm hover:bg-brand-50 border-b border-gray-50 last:border-0",
																				},
																				[
																					e(
																						"div",
																						Eo,
																						a(E.part_name),
																						1
																					),
																					e(
																						"div",
																						No,
																						a(E.part_code) +
																							" · " +
																							a(E.part_group),
																						1
																					),
																				],
																				8,
																				Mo
																			)
																		)
																	),
																	128
																)),
														  ]))
														: d("", !0),
												]),
												o.value.part
													? (t(), s("div", Fo, " Selected: " + a(o.value.part), 1))
													: d("", !0),
											]),
											e("div", Vo, [
												e("div", null, [
													N[7] ||
														(N[7] = e(
															"label",
															{
																class: "block text-xs font-medium text-gray-500 mb-1",
															},
															"Quantity",
															-1
														)),
													G(
														e(
															"input",
															{
																"onUpdate:modelValue":
																	N[2] ||
																	(N[2] = (E) => (o.value.quantity = E)),
																type: "number",
																min: "0.001",
																step: "0.5",
																class: "input-field",
															},
															null,
															512
														),
														[[K, o.value.quantity, void 0, { number: !0 }]]
													),
												]),
												e("div", null, [
													N[8] ||
														(N[8] = e(
															"label",
															{
																class: "block text-xs font-medium text-gray-500 mb-1",
															},
															"Urgency",
															-1
														)),
													G(
														e(
															"select",
															{
																"onUpdate:modelValue":
																	N[3] ||
																	(N[3] = (E) =>
																		(o.value.urgency_level = E)),
																class: "input-field",
															},
															[
																(t(),
																s(
																	V,
																	null,
																	I(h, (E) =>
																		e(
																			"option",
																			{ key: E, value: E },
																			a(E),
																			9,
																			Oo
																		)
																	),
																	64
																)),
															],
															512
														),
														[[je, o.value.urgency_level]]
													),
												]),
											]),
											e("div", null, [
												N[9] ||
													(N[9] = e(
														"label",
														{
															class: "block text-xs font-medium text-gray-500 mb-1",
														},
														"Notes",
														-1
													)),
												G(
													e(
														"textarea",
														{
															"onUpdate:modelValue":
																N[4] || (N[4] = (E) => (o.value.notes = E)),
															rows: "2",
															class: "input-field",
														},
														null,
														512
													),
													[[K, o.value.notes]]
												),
											]),
											$.value ? (t(), s("div", To, a($.value), 1)) : d("", !0),
											e("div", Io, [
												e(
													"button",
													{
														type: "button",
														onClick: B,
														class: "px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg",
													},
													" Cancel "
												),
												e(
													"button",
													{
														type: "button",
														disabled:
															R.value || !o.value.part || !o.value.quantity,
														onClick: H,
														class: "px-4 py-2 text-sm font-medium text-white bg-brand-600 hover:bg-brand-700 rounded-lg disabled:opacity-50",
													},
													a(R.value ? "Creating…" : "Raise Request"),
													9,
													Do
												),
											]),
										]),
									]
							  ))
							: d("", !0),
					])
				)
			);
		},
	},
	Lo = Z(qo, [["__scopeId", "data-v-ca6d02c1"]]),
	Bo = { class: "bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4" },
	Uo = { class: "flex gap-1" },
	Wo = ["onClick"],
	Jo = { class: "flex gap-1" },
	Ho = ["onClick"],
	Go = { class: "flex gap-2" },
	zo = ["onClick"],
	Yo = { key: 0, class: "text-sm text-red-600" },
	Qo = { key: 1, class: "text-sm text-green-600" },
	Ko = { class: "flex gap-2 justify-end pt-2" },
	Xo = ["disabled"],
	Zo = {
		__name: "FeedbackModal",
		props: { jobCardName: { type: String, required: !0 }, open: { type: Boolean, default: !1 } },
		emits: ["update:open", "submitted"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
				x = _({ rating: 0, nps_score: null, would_recommend: "Yes", comments: "" }),
				w = _(!1),
				R = _(""),
				$ = _("");
			function S() {
				A("update:open", !1), ($.value = ""), (R.value = "");
			}
			function O() {
				return q(this, null, function* () {
					var g;
					try {
						const r = yield U("vehicle_maintenance.api.job_card.get_customer_feedback", {
							job_card_name: p.jobCardName,
						});
						r != null &&
							r.data &&
							(x.value = {
								rating: r.data.rating || 0,
								nps_score: (g = r.data.nps_score) != null ? g : null,
								would_recommend: r.data.would_recommend || "Yes",
								comments: r.data.comments || "",
							});
					} catch (r) {}
				});
			}
			function j() {
				return q(this, null, function* () {
					var g;
					(w.value = !0), (R.value = ""), ($.value = "");
					try {
						yield U("vehicle_maintenance.api.job_card.submit_customer_feedback", {
							job_card_name: p.jobCardName,
							rating: x.value.rating,
							nps_score: x.value.nps_score,
							comments: x.value.comments,
							would_recommend: x.value.would_recommend,
						}),
							($.value = "Thanks for your feedback!"),
							A("submitted"),
							setTimeout(S, 1e3);
					} catch (r) {
						R.value =
							((g = r == null ? void 0 : r.messages) == null ? void 0 : g[0]) ||
							"Failed to submit feedback.";
					} finally {
						w.value = !1;
					}
				});
			}
			function o(g) {
				return g <= 6
					? "border-red-500 bg-red-50 text-red-700"
					: g <= 8
					? "border-amber-500 bg-amber-50 text-amber-700"
					: "border-green-500 bg-green-50 text-green-700";
			}
			function h(g) {
				return {
					Yes: "border-green-500 bg-green-50 text-green-700",
					Maybe: "border-amber-500 bg-amber-50 text-amber-700",
					No: "border-red-500 bg-red-50 text-red-700",
				}[g];
			}
			return (
				Be(
					() => p.open,
					(g) => {
						g && O();
					}
				),
				(g, r) =>
					m.open
						? (t(),
						  s(
								"div",
								{
									key: 0,
									class: "fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4",
									onClick: Me(S, ["self"]),
								},
								[
									e("div", Bo, [
										e("div", { class: "flex items-start justify-between" }, [
											r[1] ||
												(r[1] = e(
													"div",
													null,
													[
														e(
															"h3",
															{ class: "text-lg font-semibold text-gray-900" },
															"How did we do?"
														),
														e(
															"p",
															{ class: "text-sm text-gray-500" },
															"Your feedback helps us improve."
														),
													],
													-1
												)),
											e(
												"button",
												{
													type: "button",
													onClick: S,
													class: "text-gray-400 hover:text-gray-600 text-xl leading-none",
												},
												"×"
											),
										]),
										e("div", null, [
											r[2] ||
												(r[2] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Service Rating",
													-1
												)),
											e("div", Uo, [
												(t(),
												s(
													V,
													null,
													I(5, (v) =>
														e(
															"button",
															{
																key: v,
																type: "button",
																onClick: (y) => (x.value.rating = v),
																class: D([
																	"w-10 h-10 text-2xl",
																	v <= x.value.rating
																		? "text-amber-400"
																		: "text-gray-300 hover:text-amber-200",
																]),
																"aria-label": "`Rate ${n} star`",
															},
															" ★ ",
															10,
															Wo
														)
													),
													64
												)),
											]),
										]),
										e("div", null, [
											r[3] ||
												(r[3] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													[
														J(" How likely to recommend us? "),
														e("span", { class: "text-gray-400" }, "(0–10)"),
													],
													-1
												)),
											e("div", Jo, [
												(t(),
												s(
													V,
													null,
													I(c, (v) =>
														e(
															"button",
															{
																key: v,
																type: "button",
																onClick: (y) => (x.value.nps_score = v),
																class: D([
																	"flex-1 py-1.5 text-xs font-medium rounded border transition-colors",
																	x.value.nps_score === v
																		? o(v)
																		: "border-gray-200 text-gray-500 hover:border-gray-300",
																]),
															},
															a(v),
															11,
															Ho
														)
													),
													64
												)),
											]),
										]),
										e("div", null, [
											r[4] ||
												(r[4] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Would recommend?",
													-1
												)),
											e("div", Go, [
												(t(),
												s(
													V,
													null,
													I(["Yes", "Maybe", "No"], (v) =>
														e(
															"button",
															{
																key: v,
																type: "button",
																onClick: (y) => (x.value.would_recommend = v),
																class: D([
																	"flex-1 py-1.5 text-xs font-medium rounded-lg border-2",
																	x.value.would_recommend === v
																		? h(v)
																		: "border-gray-200 text-gray-500 hover:border-gray-300",
																]),
															},
															a(v),
															11,
															zo
														)
													),
													64
												)),
											]),
										]),
										e("div", null, [
											r[5] ||
												(r[5] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													"Comments",
													-1
												)),
											G(
												e(
													"textarea",
													{
														"onUpdate:modelValue":
															r[0] || (r[0] = (v) => (x.value.comments = v)),
														rows: "3",
														placeholder: "What went well, what could be better…",
														class: "input-field",
													},
													null,
													512
												),
												[[K, x.value.comments]]
											),
										]),
										R.value ? (t(), s("div", Yo, a(R.value), 1)) : d("", !0),
										$.value ? (t(), s("div", Qo, a($.value), 1)) : d("", !0),
										e("div", Ko, [
											e(
												"button",
												{
													type: "button",
													onClick: S,
													class: "px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg",
												},
												" Cancel "
											),
											e(
												"button",
												{
													type: "button",
													disabled: w.value || !x.value.rating,
													onClick: j,
													class: "px-4 py-2 text-sm font-medium text-white bg-brand-600 hover:bg-brand-700 rounded-lg disabled:opacity-50",
												},
												a(w.value ? "Submitting…" : "Submit Feedback"),
												9,
												Xo
											),
										]),
									]),
								]
						  ))
						: d("", !0)
			);
		},
	},
	el = Z(Zo, [["__scopeId", "data-v-2f5240d0"]]),
	tl = ["disabled"],
	sl = { class: "bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4" },
	al = { key: 0, class: "text-sm text-red-600" },
	nl = { class: "flex gap-2 justify-end pt-2" },
	ol = ["disabled"],
	ll = {
		__name: "ReopenButton",
		props: { jobCardName: { type: String, required: !0 }, disabled: { type: Boolean, default: !1 } },
		emits: ["reopened"],
		setup(m, { emit: C }) {
			const p = m,
				A = C,
				c = _(!1),
				x = _(""),
				w = _(!1),
				R = _("");
			function $() {
				(c.value = !1), (x.value = ""), (R.value = "");
			}
			function S() {
				return q(this, null, function* () {
					var O;
					(w.value = !0), (R.value = "");
					try {
						yield U("vehicle_maintenance.api.job_card.reopen_job_card", {
							job_card_name: p.jobCardName,
							reason: x.value,
						}),
							A("reopened"),
							$();
					} catch (j) {
						R.value =
							((O = j == null ? void 0 : j.messages) == null ? void 0 : O[0]) ||
							"Failed to reopen.";
					} finally {
						w.value = !1;
					}
				});
			}
			return (O, j) => (
				t(),
				s("div", null, [
					e(
						"button",
						{
							type: "button",
							disabled: m.disabled,
							onClick: j[0] || (j[0] = (o) => (c.value = !0)),
							class: "px-3 py-2 text-sm font-medium text-amber-700 border border-amber-300 hover:bg-amber-50 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed",
						},
						" Reopen Job Card ",
						8,
						tl
					),
					c.value
						? (t(),
						  s(
								"div",
								{
									key: 0,
									class: "fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4",
									onClick: Me($, ["self"]),
								},
								[
									e("div", sl, [
										j[3] ||
											(j[3] = e(
												"h3",
												{ class: "text-lg font-semibold text-gray-900" },
												"Reopen Job Card",
												-1
											)),
										j[4] ||
											(j[4] = e(
												"p",
												{ class: "text-sm text-gray-600" },
												" The Service Engineer will be notified and the card will move back into the work queue. ",
												-1
											)),
										e("div", null, [
											j[2] ||
												(j[2] = e(
													"label",
													{ class: "block text-xs font-medium text-gray-500 mb-1" },
													[
														J(" Reason "),
														e("span", { class: "text-red-500" }, "*"),
													],
													-1
												)),
											G(
												e(
													"textarea",
													{
														"onUpdate:modelValue":
															j[1] || (j[1] = (o) => (x.value = o)),
														rows: "3",
														placeholder:
															"Why are you reopening? The issue wasn't fixed, something new came up, etc.",
														class: "input-field",
													},
													null,
													512
												),
												[[K, x.value]]
											),
										]),
										R.value ? (t(), s("div", al, a(R.value), 1)) : d("", !0),
										e("div", nl, [
											e(
												"button",
												{
													type: "button",
													onClick: $,
													class: "px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg",
												},
												" Cancel "
											),
											e(
												"button",
												{
													type: "button",
													disabled: w.value || !x.value.trim(),
													onClick: S,
													class: "px-4 py-2 text-sm font-medium text-white bg-amber-600 hover:bg-amber-700 rounded-lg disabled:opacity-50",
												},
												a(w.value ? "Reopening…" : "Confirm Reopen"),
												9,
												ol
											),
										]),
									]),
								]
						  ))
						: d("", !0),
				])
			);
		},
	},
	rl = Z(ll, [["__scopeId", "data-v-90c64359"]]),
	il = { class: "max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8" },
	dl = { key: 0, class: "text-center py-12 text-gray-400" },
	ul = { class: "mb-6" },
	cl = { class: "bg-white border border-gray-200 rounded-xl p-6" },
	vl = { class: "flex items-start justify-between mb-6" },
	ml = { class: "text-sm font-mono text-gray-500" },
	bl = { class: "text-xl font-bold text-gray-900" },
	pl = { class: "text-gray-600" },
	gl = { class: "text-xs text-gray-400 mt-1" },
	yl = { class: "flex flex-col items-end gap-2" },
	xl = { key: 0, class: "text-xs font-medium text-red-600 bg-red-50 px-2 py-1 rounded" },
	_l = { key: 1, class: "text-xs font-medium text-red-700 bg-red-100 px-2 py-1 rounded" },
	fl = { key: 0, class: "mb-6 flex flex-wrap gap-2" },
	hl = ["onClick", "disabled"],
	kl = { key: 2, class: "mb-6" },
	$l = { key: 0, class: "mt-2 text-right" },
	Cl = { key: 3, class: "mb-6 p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm text-amber-800" },
	wl = { class: "mb-6" },
	Sl = { class: "flex items-center justify-between mb-3" },
	Rl = { key: 1, class: "flex gap-2" },
	jl = ["disabled"],
	Pl = { key: 0, class: "grid grid-cols-2 gap-x-6 gap-y-4 text-sm" },
	Al = { class: "font-medium" },
	Ml = { class: "font-medium" },
	El = { class: "font-medium" },
	Nl = { key: 0 },
	Fl = { class: "font-medium" },
	Vl = { key: 1 },
	Ol = { class: "font-medium" },
	Tl = { key: 2 },
	Il = { class: "font-medium" },
	Dl = { class: "col-span-2" },
	ql = { class: "font-medium whitespace-pre-line" },
	Ll = { key: 3, class: "col-span-2" },
	Bl = { class: "font-medium whitespace-pre-line text-red-700" },
	Ul = { key: 1, class: "space-y-4" },
	Wl = { class: "grid grid-cols-2 gap-4" },
	Jl = { class: "grid grid-cols-4 gap-2" },
	Hl = ["onClick"],
	Gl = { class: "grid grid-cols-2 gap-4" },
	zl = { class: "block text-sm font-medium text-gray-700 mb-1" },
	Yl = { key: 0, class: "ml-1 text-xs font-normal text-gray-400" },
	Ql = ["disabled"],
	Kl = ["value"],
	Xl = { key: 0 },
	Zl = { class: "block text-sm font-medium text-gray-700 mb-1" },
	er = { key: 0, class: "ml-1 text-xs font-normal text-gray-400" },
	tr = ["disabled"],
	sr = ["value"],
	ar = { key: 0 },
	nr = { key: 0, class: "text-sm text-red-600" },
	or = { key: 4, class: "mb-6" },
	lr = { class: "flex items-center justify-between mb-3" },
	rr = { class: "flex gap-2" },
	ir = { key: 1, class: "flex gap-2" },
	dr = ["disabled"],
	ur = { key: 1, class: "space-y-2" },
	cr = { class: "flex-1" },
	vr = { class: "font-medium text-gray-800" },
	mr = { class: "text-xs text-gray-500 mt-0.5" },
	br = { class: "text-right text-xs text-gray-500" },
	pr = { class: "font-medium text-gray-800" },
	gr = {
		key: 2,
		class: "text-sm text-gray-400 text-center py-4 border border-dashed border-gray-200 rounded-xl",
	},
	yr = { key: 3, class: "mt-2 text-sm text-red-600" },
	xr = { key: 5, class: "mb-6" },
	_r = { class: "flex items-center justify-between mb-3" },
	fr = { class: "flex gap-2" },
	hr = { key: 1, class: "flex gap-2" },
	kr = ["disabled"],
	$r = { key: 1, class: "space-y-2" },
	Cr = { class: "flex-1" },
	wr = { class: "font-medium text-gray-800" },
	Sr = { class: "text-xs text-gray-500 mt-0.5" },
	Rr = { class: "text-right text-xs text-gray-500" },
	jr = { class: "font-medium text-gray-800" },
	Pr = {
		key: 2,
		class: "text-sm text-gray-400 text-center py-4 border border-dashed border-gray-200 rounded-xl",
	},
	Ar = { key: 3, class: "mt-2 text-sm text-red-600" },
	Mr = { key: 6, class: "mb-6" },
	Er = { class: "flex items-center justify-between mb-3" },
	Nr = { key: 1, class: "flex gap-2" },
	Fr = ["disabled"],
	Vr = { key: 1, class: "flex flex-wrap gap-1.5" },
	Or = { key: 2, class: "text-sm text-gray-400" },
	Tr = { key: 3, class: "mt-2 text-sm text-red-600" },
	Ir = { key: 7, class: "mb-6" },
	Dr = { key: 8, class: "mb-6" },
	qr = { class: "flex items-center justify-between mb-3" },
	Lr = { class: "flex gap-2" },
	Br = { key: 1, class: "flex gap-2" },
	Ur = ["disabled"],
	Wr = { key: 2, class: "mt-2 text-sm text-red-600" },
	Jr = { key: 9, class: "mb-6" },
	Hr = { class: "text-xs text-gray-400 border-t border-gray-100 pt-4 flex gap-6" },
	Gr = { key: 0 },
	zr = { key: 1 },
	Yr = {
		__name: "JobCardDetail",
		props: { name: { type: String, required: !0 } },
		setup(m) {
			const C = m,
				{ roles: p } = Ue(),
				A = T(() => ae(p, ["Service Engineer", "Depot Manager"])),
				c = T(
					() =>
						ae(p, ["Service Engineer", "Technician", "Depot Manager"]) &&
						!["Closed"].includes(o.value.workflow_state)
				),
				x = T(
					() =>
						ae(p, [
							"Service Engineer",
							"Depot Manager",
							"Aftersales Eng",
							"N. Maintenance Head",
						]) && !["Closed"].includes(o.value.workflow_state)
				),
				w = T(() => o.value.workflow_state === "Closed" && ae(p, ["Customer", "Central Ops"])),
				R = T(() => o.value.workflow_state === "Closed"),
				$ = T(() => ae(p, ["Service Engineer", "Technician", "Depot Manager", "Central Ops"])),
				S = _(!1);
			function O() {
				(i.value = !0), (P.value = "Job Card reopened."), j.fetch();
			}
			const j = gt({
					url: "vehicle_maintenance.api.job_card.get_job_card_summary",
					params: { job_card_name: C.name },
					auto: !0,
					onSuccess() {
						L(), Ye();
					},
				}),
				o = T(() => {
					var k;
					return ((k = j.data) == null ? void 0 : k.data) || {};
				}),
				h = T(() => o.value.job_card_type === "PMS + Repair"),
				g = T(() => o.value.job_card_type === "Software Update"),
				r = T(() => o.value.job_card_type === "Breakdown"),
				v = T(() => ["PMS + Repair", "Only Repair"].includes(o.value.job_card_type)),
				y = T(() => ["Only Repair", "Software Update", "Breakdown"].includes(o.value.job_card_type)),
				b = T(() => v.value),
				u = T(() => ae(p, ["Aftersales Eng", "Depot Manager", "N. Maintenance Head"])),
				n = _([]),
				B = _(!1),
				P = _(""),
				i = _(!0);
			function L() {
				return q(this, null, function* () {
					var k;
					try {
						const l = yield U("vehicle_maintenance.api.job_card.get_available_actions", {
							job_card_name: C.name,
						});
						n.value = ((k = l == null ? void 0 : l.data) == null ? void 0 : k.actions) || [];
					} catch (l) {
						n.value = [];
					}
				});
			}
			function H(k) {
				return q(this, null, function* () {
					var l, M;
					(B.value = !0), (P.value = "");
					try {
						const z = yield U("vehicle_maintenance.api.job_card.transition_job_card", {
							job_card_name: C.name,
							action: k,
						});
						(i.value = !0),
							(P.value =
								(z == null ? void 0 : z.message) ||
								`Moved to ${
									(l = z == null ? void 0 : z.data) == null ? void 0 : l.workflow_state
								}`),
							j.fetch();
					} catch (z) {
						(i.value = !1),
							(P.value =
								((M = z == null ? void 0 : z.messages) == null ? void 0 : M[0]) ||
								"Transition failed.");
					} finally {
						B.value = !1;
					}
				});
			}
			function xe(k) {
				return q(this, null, function* () {
					var l;
					(B.value = !0), (P.value = "");
					try {
						const M = yield U("vehicle_maintenance.api.job_card.force_close_job_card", {
							job_card_name: C.name,
							severity: k.severity,
							reason: k.reason,
						});
						(i.value = !0),
							(P.value = (M == null ? void 0 : M.message) || "Job Card force-closed."),
							j.fetch();
					} catch (M) {
						throw (
							((i.value = !1),
							(P.value =
								((l = M == null ? void 0 : M.messages) == null ? void 0 : l[0]) ||
								"Force close failed."),
							M)
						);
					} finally {
						B.value = !1;
					}
				});
			}
			function Ee(k) {
				return k === "Close Job Card"
					? "border-green-400 bg-green-50 text-green-700 hover:bg-green-100"
					: k === "Verification Failed" || k === "Customer Rejects"
					? "border-red-300 bg-red-50 text-red-700 hover:bg-red-100"
					: "border-brand-300 bg-brand-50 text-brand-700 hover:bg-brand-100";
			}
			function F(k) {
				return (
					{
						Completed: "bg-green-100 text-green-700",
						"Parts Issued": "bg-blue-100 text-blue-700",
						"Parts Allocated": "bg-indigo-100 text-indigo-700",
						"Parts Requested": "bg-amber-100 text-amber-700",
						"In Progress": "bg-amber-100 text-amber-700",
						Pending: "bg-gray-100 text-gray-600",
						"Customer Rejected": "bg-red-100 text-red-700",
					}[k] || "bg-gray-100 text-gray-600"
				);
			}
			const N = _(!1),
				E = _(!1),
				Y = _(""),
				W = yt({
					service_type: "",
					priority: "",
					assigned_service_engineer: "",
					assigned_technician: "",
					depot: "",
					complaint_description: "",
				}),
				_e = _([]),
				fe = _([]),
				he = T(() => {
					var k;
					return !!((k = o.value) != null && k.viewer_is_depot_manager);
				});
			function Je() {
				return q(this, null, function* () {
					try {
						const [k, l] = yield Promise.all([
							U("vehicle_maintenance.api.job_card.list_users_by_role", {
								role: "Service Engineer",
							}),
							U("vehicle_maintenance.api.job_card.list_users_by_role", { role: "Technician" }),
						]);
						(_e.value = (k == null ? void 0 : k.data) || []),
							(fe.value = (l == null ? void 0 : l.data) || []);
					} catch (k) {
						(_e.value = []), (fe.value = []);
					}
				});
			}
			function He() {
				(W.service_type = o.value.service_type || ""),
					(W.priority = o.value.priority || ""),
					(W.assigned_service_engineer = o.value.assigned_service_engineer || ""),
					(W.assigned_technician = o.value.assigned_technician || ""),
					(W.depot = o.value.depot || ""),
					(W.complaint_description = o.value.complaint_description || ""),
					(N.value = !0),
					(Y.value = ""),
					!_e.value.length && !fe.value.length && Je();
			}
			function Ge() {
				(N.value = !1), (Y.value = "");
			}
			function ze() {
				return q(this, null, function* () {
					var k;
					(E.value = !0), (Y.value = "");
					try {
						yield U("vehicle_maintenance.api.job_card.update_job_card", {
							job_card_name: C.name,
							updates: JSON.stringify(W),
						}),
							(N.value = !1),
							j.fetch();
					} catch (l) {
						Y.value =
							((k = l == null ? void 0 : l.messages) == null ? void 0 : k[0]) || "Save failed.";
					} finally {
						E.value = !1;
					}
				});
			}
			const Ne = _([]),
				ne = _(!1),
				ve = _([]),
				ke = _(!1),
				oe = _("");
			function Ye() {
				return q(this, null, function* () {
					try {
						const k = yield U("vehicle_maintenance.api.job_card.list_part_groups");
						Ne.value = (k == null ? void 0 : k.data) || [];
					} catch (k) {
						Ne.value = [];
					}
				});
			}
			function Qe() {
				(ve.value = (o.value.repair_items || []).map((k) => Q({}, k))),
					(ne.value = !0),
					(oe.value = "");
			}
			function Ke() {
				(ne.value = !1), (ve.value = []), (oe.value = "");
			}
			function Xe() {
				return q(this, null, function* () {
					var k;
					(ke.value = !0), (oe.value = "");
					try {
						const l = ve.value.map((M) =>
							te(Q({}, M), {
								pre_repair_photo:
									typeof M.pre_repair_photo == "string" ? M.pre_repair_photo : null,
								post_repair_photo:
									typeof M.post_repair_photo == "string" ? M.post_repair_photo : null,
							})
						);
						yield U("vehicle_maintenance.api.job_card.save_repair_items", {
							job_card_name: C.name,
							rows: JSON.stringify(l),
						}),
							(ne.value = !1),
							j.fetch();
					} catch (l) {
						oe.value =
							((k = l == null ? void 0 : l.messages) == null ? void 0 : k[0]) ||
							"Failed to save repair jobs.";
					} finally {
						ke.value = !1;
					}
				});
			}
			const le = _(!1),
				me = _([]),
				$e = _(!1),
				re = _("");
			function Ze() {
				(me.value = (o.value.maintenance_items || []).map((k) => Q({}, k))),
					(le.value = !0),
					(re.value = "");
			}
			function et() {
				(le.value = !1), (me.value = []), (re.value = "");
			}
			function tt() {
				return q(this, null, function* () {
					var k;
					($e.value = !0), (re.value = "");
					try {
						const l = me.value.map((M) =>
							te(Q({}, M), {
								pre_photo: typeof M.pre_photo == "string" ? M.pre_photo : null,
								post_photo: typeof M.post_photo == "string" ? M.post_photo : null,
							})
						);
						yield U("vehicle_maintenance.api.job_card.save_maintenance_items", {
							job_card_name: C.name,
							rows: JSON.stringify(l),
						}),
							(le.value = !1),
							j.fetch();
					} catch (l) {
						re.value =
							((k = l == null ? void 0 : l.messages) == null ? void 0 : k[0]) ||
							"Failed to save maintenance jobs.";
					} finally {
						$e.value = !1;
					}
				});
			}
			const ie = _(!1),
				be = _([]),
				Ce = _(!1),
				de = _("");
			function st() {
				(be.value = [...(o.value.subsystems || [])]), (ie.value = !0), (de.value = "");
			}
			function at() {
				(ie.value = !1), (be.value = []), (de.value = "");
			}
			function nt() {
				return q(this, null, function* () {
					var k;
					(Ce.value = !0), (de.value = "");
					try {
						yield U("vehicle_maintenance.api.job_card.save_subsystems", {
							job_card_name: C.name,
							subsystems: JSON.stringify(be.value),
						}),
							(ie.value = !1),
							j.fetch();
					} catch (l) {
						de.value =
							((k = l == null ? void 0 : l.messages) == null ? void 0 : k[0]) ||
							"Failed to save subsystems.";
					} finally {
						Ce.value = !1;
					}
				});
			}
			const ee = _(""),
				pe = _(!0);
			function ot(k) {
				return q(this, null, function* () {
					var l;
					ee.value = "";
					try {
						yield U("vehicle_maintenance.api.job_card.update_breakdown_diagnosis", {
							job_card_name: C.name,
							updates: JSON.stringify(k),
						}),
							(pe.value = !0),
							(ee.value = "Saved."),
							j.fetch();
					} catch (M) {
						(pe.value = !1),
							(ee.value =
								((l = M == null ? void 0 : M.messages) == null ? void 0 : l[0]) ||
								"Save failed.");
					}
				});
			}
			function lt(k) {
				return q(this, null, function* () {
					var l;
					ee.value = "";
					try {
						yield U("vehicle_maintenance.api.job_card.save_groups_impacted", {
							job_card_name: C.name,
							part_groups: JSON.stringify(k),
						}),
							(pe.value = !0),
							(ee.value = "Groups updated."),
							j.fetch();
					} catch (M) {
						(pe.value = !1),
							(ee.value =
								((l = M == null ? void 0 : M.messages) == null ? void 0 : l[0]) ||
								"Save failed.");
					}
				});
			}
			const ue = _(!1),
				ge = _([]),
				we = _(!1),
				ce = _("");
			function rt() {
				(ge.value = (o.value.software_components || []).map((k) => Q({}, k))),
					(ue.value = !0),
					(ce.value = "");
			}
			function it() {
				(ue.value = !1), (ge.value = []), (ce.value = "");
			}
			function dt() {
				return q(this, null, function* () {
					var k;
					(we.value = !0), (ce.value = "");
					try {
						const l = ge.value.map((M) =>
							te(Q({}, M), {
								pre_version_photo:
									typeof M.pre_version_photo == "string" ? M.pre_version_photo : null,
								post_version_photo:
									typeof M.post_version_photo == "string" ? M.post_version_photo : null,
								calibration_photo:
									typeof M.calibration_photo == "string" ? M.calibration_photo : null,
							})
						);
						yield U("vehicle_maintenance.api.job_card.save_software_components", {
							job_card_name: C.name,
							rows: JSON.stringify(l),
						}),
							(ue.value = !1),
							j.fetch();
					} catch (l) {
						ce.value =
							((k = l == null ? void 0 : l.messages) == null ? void 0 : k[0]) ||
							"Failed to save software components.";
					} finally {
						we.value = !1;
					}
				});
			}
			function ut(k) {
				return {
					Low: "border-green-500 bg-green-50 text-green-700",
					Medium: "border-amber-500 bg-amber-50 text-amber-700",
					High: "border-orange-500 bg-orange-50 text-orange-700",
					Urgent: "border-red-500 bg-red-50 text-red-700",
				}[k];
			}
			function Se(k) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					minimumFractionDigits: 0,
				}).format(k || 0);
			}
			function Ve(k) {
				return k
					? new Date(k).toLocaleDateString("en-IN", {
							day: "numeric",
							month: "short",
							year: "numeric",
							hour: "2-digit",
							minute: "2-digit",
					  })
					: "";
			}
			return (k, l) => {
				var z, Oe, Te;
				const M = Le("router-link");
				return (
					t(),
					s("div", il, [
						Pe(j).loading
							? (t(), s("div", dl, "Loading..."))
							: o.value.name
							? (t(),
							  s(
									V,
									{ key: 1 },
									[
										e("div", ul, [
											se(
												M,
												{
													to: "/service-portal",
													class: "text-sm text-brand-600 hover:underline",
												},
												{
													default: Fe(() => [
														...(l[12] || (l[12] = [J(" ← Back to list ", -1)])),
													]),
													_: 1,
												}
											),
										]),
										e("div", cl, [
											e("div", vl, [
												e("div", null, [
													e("p", ml, a(o.value.name), 1),
													e("h1", bl, a(o.value.vehicle_number), 1),
													e("p", pl, a(o.value.vehicle_make_model), 1),
													e(
														"p",
														gl,
														a(o.value.job_card_type) +
															" · " +
															a(o.value.service_type),
														1
													),
												]),
												e("div", yl, [
													se(We, { state: o.value.workflow_state }, null, 8, [
														"state",
													]),
													o.value.sla_breached
														? (t(), s("div", xl, " SLA Breached "))
														: d("", !0),
													o.value.force_closed
														? (t(),
														  s(
																"div",
																_l,
																" Force Closed · " +
																	a(o.value.force_close_severity),
																1
														  ))
														: d("", !0),
												]),
											]),
											(n.value.length && A.value) || x.value
												? (t(),
												  s("div", fl, [
														(t(!0),
														s(
															V,
															null,
															I(
																n.value,
																(f) => (
																	t(),
																	s(
																		"button",
																		{
																			key: f,
																			onClick: (ye) => H(f),
																			disabled: B.value,
																			class: D([
																				"px-4 py-2 text-sm font-medium rounded-lg border-2 transition-colors disabled:opacity-50",
																				Ee(f),
																			]),
																		},
																		a(f),
																		11,
																		hl
																	)
																)
															),
															128
														)),
														x.value
															? (t(),
															  X(
																	aa,
																	{
																		key: 0,
																		disabled:
																			B.value || o.value.force_closed,
																		onSubmit: xe,
																	},
																	null,
																	8,
																	["disabled"]
															  ))
															: d("", !0),
														w.value
															? (t(),
															  X(
																	rl,
																	{
																		key: 1,
																		"job-card-name": o.value.name,
																		disabled: B.value,
																		onReopened: O,
																	},
																	null,
																	8,
																	["job-card-name", "disabled"]
															  ))
															: d("", !0),
														R.value && !o.value.force_closed
															? (t(),
															  s(
																	"button",
																	{
																		key: 2,
																		type: "button",
																		onClick:
																			l[0] ||
																			(l[0] = (f) => (S.value = !0)),
																		class: "px-3 py-2 text-sm font-medium text-amber-700 border border-amber-300 hover:bg-amber-50 rounded-lg",
																	},
																	" Share Feedback "
															  ))
															: d("", !0),
												  ]))
												: d("", !0),
											P.value
												? (t(),
												  s(
														"div",
														{
															key: 1,
															class: D([
																"mb-4 p-3 rounded-lg text-sm",
																i.value
																	? "bg-green-50 text-green-700"
																	: "bg-red-50 text-red-700",
															]),
														},
														a(P.value),
														3
												  ))
												: d("", !0),
											h.value
												? (t(),
												  s("div", kl, [
														se(
															xt,
															{
																"pre-score": o.value.pre_pms_score,
																"post-score": o.value.post_pms_score,
																improvement: o.value.score_improvement,
																"category-scores":
																	o.value.category_scores || [],
															},
															null,
															8,
															[
																"pre-score",
																"post-score",
																"improvement",
																"category-scores",
															]
														),
														o.value.health_card
															? (t(),
															  s("div", $l, [
																	se(
																		M,
																		{
																			to: `/service-portal/job-card/${o.value.name}/health-card`,
																			class: "text-sm text-brand-600 hover:underline",
																		},
																		{
																			default: Fe(() => [
																				...(l[13] ||
																					(l[13] = [
																						J(
																							" View Vehicle Health Card → ",
																							-1
																						),
																					])),
																			]),
																			_: 1,
																		},
																		8,
																		["to"]
																	),
															  ]))
															: d("", !0),
												  ]))
												: d("", !0),
											o.value.requires_customer_approval &&
											o.value.workflow_state !== "Closed"
												? (t(),
												  s("div", Cl, [
														...(l[14] ||
															(l[14] = [
																e(
																	"span",
																	{ class: "font-semibold" },
																	"Customer approval required",
																	-1
																),
																J(" — parts total exceeds ₹1,000. ", -1),
															])),
												  ]))
												: d("", !0),
											e("div", wl, [
												e("div", Sl, [
													l[15] ||
														(l[15] = e(
															"h3",
															{
																class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
															},
															"Details",
															-1
														)),
													A.value && !N.value
														? (t(),
														  s(
																"button",
																{
																	key: 0,
																	onClick: He,
																	class: "text-sm text-brand-600 hover:underline",
																},
																" Edit "
														  ))
														: d("", !0),
													N.value
														? (t(),
														  s("div", Rl, [
																e(
																	"button",
																	{
																		onClick: Ge,
																		class: "text-sm text-gray-500 hover:underline",
																	},
																	"Cancel"
																),
																e(
																	"button",
																	{
																		onClick: ze,
																		disabled: E.value,
																		class: "text-sm text-brand-600 font-medium hover:underline disabled:opacity-50",
																	},
																	a(E.value ? "Saving..." : "Save"),
																	9,
																	jl
																),
														  ]))
														: d("", !0),
												]),
												N.value
													? (t(),
													  s("div", Ul, [
															e("div", Wl, [
																e("div", null, [
																	l[26] ||
																		(l[26] = e(
																			"label",
																			{
																				class: "block text-sm font-medium text-gray-700 mb-1",
																			},
																			"Service Type",
																			-1
																		)),
																	G(
																		e(
																			"select",
																			{
																				"onUpdate:modelValue":
																					l[1] ||
																					(l[1] = (f) =>
																						(W.service_type = f)),
																				class: "input-field",
																			},
																			[
																				...(l[25] ||
																					(l[25] = [
																						e(
																							"option",
																							null,
																							"Scheduled Maintenance",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"Breakdown Repair",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"Body & Paint",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"Electrical",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"Tyre & Alignment",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"General Inspection",
																							-1
																						),
																						e(
																							"option",
																							null,
																							"Other",
																							-1
																						),
																					])),
																			],
																			512
																		),
																		[[je, W.service_type]]
																	),
																]),
																e("div", null, [
																	l[27] ||
																		(l[27] = e(
																			"label",
																			{
																				class: "block text-sm font-medium text-gray-700 mb-1",
																			},
																			"Priority",
																			-1
																		)),
																	e("div", Jl, [
																		(t(),
																		s(
																			V,
																			null,
																			I(
																				[
																					"Low",
																					"Medium",
																					"High",
																					"Urgent",
																				],
																				(f) =>
																					e(
																						"button",
																						{
																							key: f,
																							type: "button",
																							onClick: (ye) =>
																								(W.priority =
																									f),
																							class: D([
																								"py-2 text-xs font-medium rounded-lg border-2 transition-colors text-center",
																								W.priority ===
																								f
																									? ut(f)
																									: "border-gray-200 text-gray-500",
																							]),
																						},
																						a(f),
																						11,
																						Hl
																					)
																			),
																			64
																		)),
																	]),
																]),
															]),
															e("div", Gl, [
																e("div", null, [
																	e("label", zl, [
																		l[28] ||
																			(l[28] = J(" Assigned SE ", -1)),
																		he.value
																			? d("", !0)
																			: (t(),
																			  s(
																					"span",
																					Yl,
																					" (Depot Manager only) "
																			  )),
																	]),
																	G(
																		e(
																			"select",
																			{
																				"onUpdate:modelValue":
																					l[2] ||
																					(l[2] = (f) =>
																						(W.assigned_service_engineer =
																							f)),
																				class: "input-field",
																				disabled: !he.value,
																			},
																			[
																				l[29] ||
																					(l[29] = e(
																						"option",
																						{ value: "" },
																						"— Unassigned —",
																						-1
																					)),
																				(t(!0),
																				s(
																					V,
																					null,
																					I(
																						_e.value,
																						(f) => (
																							t(),
																							s(
																								"option",
																								{
																									key: f.user,
																									value: f.user,
																								},
																								[
																									J(
																										a(
																											f.full_name ||
																												f.user
																										) +
																											" ",
																										1
																									),
																									f.full_name
																										? (t(),
																										  s(
																												"span",
																												Xl,
																												"(" +
																													a(
																														f.user
																													) +
																													")",
																												1
																										  ))
																										: d(
																												"",
																												!0
																										  ),
																								],
																								8,
																								Kl
																							)
																						)
																					),
																					128
																				)),
																			],
																			8,
																			Ql
																		),
																		[[je, W.assigned_service_engineer]]
																	),
																]),
																e("div", null, [
																	e("label", Zl, [
																		l[30] ||
																			(l[30] = J(
																				" Assigned Technician ",
																				-1
																			)),
																		he.value
																			? d("", !0)
																			: (t(),
																			  s(
																					"span",
																					er,
																					" (Depot Manager only) "
																			  )),
																	]),
																	G(
																		e(
																			"select",
																			{
																				"onUpdate:modelValue":
																					l[3] ||
																					(l[3] = (f) =>
																						(W.assigned_technician =
																							f)),
																				class: "input-field",
																				disabled: !he.value,
																			},
																			[
																				l[31] ||
																					(l[31] = e(
																						"option",
																						{ value: "" },
																						"— Unassigned —",
																						-1
																					)),
																				(t(!0),
																				s(
																					V,
																					null,
																					I(
																						fe.value,
																						(f) => (
																							t(),
																							s(
																								"option",
																								{
																									key: f.user,
																									value: f.user,
																								},
																								[
																									J(
																										a(
																											f.full_name ||
																												f.user
																										) +
																											" ",
																										1
																									),
																									f.full_name
																										? (t(),
																										  s(
																												"span",
																												ar,
																												"(" +
																													a(
																														f.user
																													) +
																													")",
																												1
																										  ))
																										: d(
																												"",
																												!0
																										  ),
																								],
																								8,
																								sr
																							)
																						)
																					),
																					128
																				)),
																			],
																			8,
																			tr
																		),
																		[[je, W.assigned_technician]]
																	),
																]),
															]),
															e("div", null, [
																l[32] ||
																	(l[32] = e(
																		"label",
																		{
																			class: "block text-sm font-medium text-gray-700 mb-1",
																		},
																		"Depot",
																		-1
																	)),
																G(
																	e(
																		"input",
																		{
																			"onUpdate:modelValue":
																				l[4] ||
																				(l[4] = (f) => (W.depot = f)),
																			class: "input-field",
																			placeholder: "Depot / Workshop",
																		},
																		null,
																		512
																	),
																	[[K, W.depot]]
																),
															]),
															e("div", null, [
																l[33] ||
																	(l[33] = e(
																		"label",
																		{
																			class: "block text-sm font-medium text-gray-700 mb-1",
																		},
																		"Complaint / Description",
																		-1
																	)),
																G(
																	e(
																		"textarea",
																		{
																			"onUpdate:modelValue":
																				l[5] ||
																				(l[5] = (f) =>
																					(W.complaint_description =
																						f)),
																			rows: "3",
																			class: "input-field",
																		},
																		null,
																		512
																	),
																	[[K, W.complaint_description]]
																),
															]),
															Y.value
																? (t(), s("div", nr, a(Y.value), 1))
																: d("", !0),
													  ]))
													: (t(),
													  s("dl", Pl, [
															e("div", null, [
																l[16] ||
																	(l[16] = e(
																		"dt",
																		{ class: "text-gray-500" },
																		"Customer",
																		-1
																	)),
																e("dd", Al, a(o.value.customer_name), 1),
															]),
															e("div", null, [
																l[17] ||
																	(l[17] = e(
																		"dt",
																		{ class: "text-gray-500" },
																		"Priority",
																		-1
																	)),
																e(
																	"dd",
																	{
																		class: D([
																			"font-medium",
																			o.value.priority === "Urgent"
																				? "text-red-600"
																				: "",
																		]),
																	},
																	a(o.value.priority),
																	3
																),
															]),
															e("div", null, [
																l[18] ||
																	(l[18] = e(
																		"dt",
																		{ class: "text-gray-500" },
																		"Estimated Cost",
																		-1
																	)),
																e("dd", Ml, a(Se(o.value.estimated_cost)), 1),
															]),
															e("div", null, [
																l[19] ||
																	(l[19] = e(
																		"dt",
																		{ class: "text-gray-500" },
																		"Actual Cost",
																		-1
																	)),
																e("dd", El, a(Se(o.value.actual_cost)), 1),
															]),
															o.value.assigned_service_engineer
																? (t(),
																  s("div", Nl, [
																		l[20] ||
																			(l[20] = e(
																				"dt",
																				{ class: "text-gray-500" },
																				"Service Engineer",
																				-1
																			)),
																		e(
																			"dd",
																			Fl,
																			a(
																				o.value
																					.assigned_service_engineer
																			),
																			1
																		),
																  ]))
																: d("", !0),
															o.value.assigned_technician
																? (t(),
																  s("div", Vl, [
																		l[21] ||
																			(l[21] = e(
																				"dt",
																				{ class: "text-gray-500" },
																				"Technician",
																				-1
																			)),
																		e(
																			"dd",
																			Ol,
																			a(o.value.assigned_technician),
																			1
																		),
																  ]))
																: d("", !0),
															o.value.depot
																? (t(),
																  s("div", Tl, [
																		l[22] ||
																			(l[22] = e(
																				"dt",
																				{ class: "text-gray-500" },
																				"Depot",
																				-1
																			)),
																		e("dd", Il, a(o.value.depot), 1),
																  ]))
																: d("", !0),
															e("div", Dl, [
																l[23] ||
																	(l[23] = e(
																		"dt",
																		{ class: "text-gray-500" },
																		"Complaint / Description",
																		-1
																	)),
																e(
																	"dd",
																	ql,
																	a(o.value.complaint_description),
																	1
																),
															]),
															o.value.force_close_reason
																? (t(),
																  s("div", Ll, [
																		l[24] ||
																			(l[24] = e(
																				"dt",
																				{ class: "text-gray-500" },
																				"Force Close Reason",
																				-1
																			)),
																		e(
																			"dd",
																			Bl,
																			a(o.value.force_close_reason),
																			1
																		),
																  ]))
																: d("", !0),
													  ])),
											]),
											b.value
												? (t(),
												  s("div", or, [
														e("div", lr, [
															l[34] ||
																(l[34] = e(
																	"h3",
																	{
																		class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
																	},
																	"Repair Jobs",
																	-1
																)),
															e("div", rr, [
																c.value && !ne.value
																	? (t(),
																	  s(
																			"button",
																			{
																				key: 0,
																				onClick: Qe,
																				class: "text-sm text-brand-600 hover:underline",
																			},
																			" Edit "
																	  ))
																	: d("", !0),
																ne.value
																	? (t(),
																	  s("div", ir, [
																			e(
																				"button",
																				{
																					onClick: Ke,
																					class: "text-sm text-gray-500 hover:underline",
																				},
																				"Cancel"
																			),
																			e(
																				"button",
																				{
																					onClick: Xe,
																					disabled: ke.value,
																					class: "text-sm text-brand-600 font-medium hover:underline disabled:opacity-50",
																				},
																				a(
																					ke.value
																						? "Saving…"
																						: "Save"
																				),
																				9,
																				dr
																			),
																	  ]))
																	: d("", !0),
															]),
														]),
														ne.value
															? (t(),
															  X(
																	_s,
																	{
																		key: 0,
																		modelValue: ve.value,
																		"onUpdate:modelValue":
																			l[6] ||
																			(l[6] = (f) => (ve.value = f)),
																		"part-groups": Ne.value,
																	},
																	null,
																	8,
																	["modelValue", "part-groups"]
															  ))
															: (z = o.value.repair_items) != null && z.length
															? (t(),
															  s("div", ur, [
																	(t(!0),
																	s(
																		V,
																		null,
																		I(
																			o.value.repair_items,
																			(f, ye) => (
																				t(),
																				s(
																					"div",
																					{
																						key: ye,
																						class: "bg-white border border-gray-100 rounded-lg p-3 text-sm flex items-start gap-3",
																					},
																					[
																						e("div", cr, [
																							e(
																								"div",
																								vr,
																								a(
																									f.description ||
																										"—"
																								),
																								1
																							),
																							e(
																								"div",
																								mr,
																								a(
																									f.part_group
																								) +
																									" · " +
																									a(
																										f.bus_system
																									) +
																									" · " +
																									a(
																										f.activity_type
																									),
																								1
																							),
																						]),
																						e("div", br, [
																							e(
																								"div",
																								null,
																								"Qty " +
																									a(f.qty),
																								1
																							),
																							e(
																								"div",
																								pr,
																								a(
																									Se(
																										f.estimated_amount
																									)
																								),
																								1
																							),
																						]),
																						e(
																							"span",
																							{
																								class: D([
																									"text-xs font-medium px-2 py-0.5 rounded-full shrink-0",
																									F(
																										f.item_status
																									),
																								]),
																							},
																							a(f.item_status),
																							3
																						),
																					]
																				)
																			)
																		),
																		128
																	)),
															  ]))
															: (t(),
															  s("div", gr, " No repair jobs recorded. ")),
														oe.value
															? (t(), s("div", yr, a(oe.value), 1))
															: d("", !0),
												  ]))
												: d("", !0),
											h.value
												? (t(),
												  s("div", xr, [
														e("div", _r, [
															l[35] ||
																(l[35] = e(
																	"h3",
																	{
																		class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
																	},
																	"Maintenance Jobs",
																	-1
																)),
															e("div", fr, [
																c.value && !le.value
																	? (t(),
																	  s(
																			"button",
																			{
																				key: 0,
																				onClick: Ze,
																				class: "text-sm text-brand-600 hover:underline",
																			},
																			" Edit "
																	  ))
																	: d("", !0),
																le.value
																	? (t(),
																	  s("div", hr, [
																			e(
																				"button",
																				{
																					onClick: et,
																					class: "text-sm text-gray-500 hover:underline",
																				},
																				"Cancel"
																			),
																			e(
																				"button",
																				{
																					onClick: tt,
																					disabled: $e.value,
																					class: "text-sm text-brand-600 font-medium hover:underline disabled:opacity-50",
																				},
																				a(
																					$e.value
																						? "Saving…"
																						: "Save"
																				),
																				9,
																				kr
																			),
																	  ]))
																	: d("", !0),
															]),
														]),
														le.value
															? (t(),
															  X(
																	Ws,
																	{
																		key: 0,
																		modelValue: me.value,
																		"onUpdate:modelValue":
																			l[7] ||
																			(l[7] = (f) => (me.value = f)),
																	},
																	null,
																	8,
																	["modelValue"]
															  ))
															: (Oe = o.value.maintenance_items) != null &&
															  Oe.length
															? (t(),
															  s("div", $r, [
																	(t(!0),
																	s(
																		V,
																		null,
																		I(
																			o.value.maintenance_items,
																			(f, ye) => (
																				t(),
																				s(
																					"div",
																					{
																						key: ye,
																						class: "bg-white border border-gray-100 rounded-lg p-3 text-sm flex items-start gap-3",
																					},
																					[
																						e("div", Cr, [
																							e(
																								"div",
																								wr,
																								a(
																									f.description ||
																										"—"
																								),
																								1
																							),
																							e(
																								"div",
																								Sr,
																								a(
																									f.maintenance_type
																								) +
																									" · " +
																									a(
																										f.action
																									),
																								1
																							),
																						]),
																						e("div", Rr, [
																							e(
																								"div",
																								null,
																								a(f.qty) +
																									" " +
																									a(f.unit),
																								1
																							),
																							e(
																								"div",
																								jr,
																								a(
																									Se(
																										f.estimated_amount
																									)
																								),
																								1
																							),
																						]),
																						e(
																							"span",
																							{
																								class: D([
																									"text-xs font-medium px-2 py-0.5 rounded-full shrink-0",
																									F(
																										f.item_status
																									),
																								]),
																							},
																							a(f.item_status),
																							3
																						),
																					]
																				)
																			)
																		),
																		128
																	)),
															  ]))
															: (t(),
															  s(
																	"div",
																	Pr,
																	" No maintenance jobs recorded. "
															  )),
														re.value
															? (t(), s("div", Ar, a(re.value), 1))
															: d("", !0),
												  ]))
												: d("", !0),
											y.value
												? (t(),
												  s("div", Mr, [
														e("div", Er, [
															l[36] ||
																(l[36] = e(
																	"h3",
																	{
																		class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
																	},
																	"Subsystems Affected",
																	-1
																)),
															c.value && !ie.value
																? (t(),
																  s(
																		"button",
																		{
																			key: 0,
																			onClick: st,
																			class: "text-sm text-brand-600 hover:underline",
																		},
																		" Edit "
																  ))
																: d("", !0),
															ie.value
																? (t(),
																  s("div", Nr, [
																		e(
																			"button",
																			{
																				onClick: at,
																				class: "text-sm text-gray-500 hover:underline",
																			},
																			"Cancel"
																		),
																		e(
																			"button",
																			{
																				onClick: nt,
																				disabled: Ce.value,
																				class: "text-sm text-brand-600 font-medium hover:underline disabled:opacity-50",
																			},
																			a(Ce.value ? "Saving…" : "Save"),
																			9,
																			Fr
																		),
																  ]))
																: d("", !0),
														]),
														ie.value
															? (t(),
															  X(
																	pa,
																	{
																		key: 0,
																		modelValue: be.value,
																		"onUpdate:modelValue":
																			l[8] ||
																			(l[8] = (f) => (be.value = f)),
																	},
																	null,
																	8,
																	["modelValue"]
															  ))
															: (Te = o.value.subsystems) != null && Te.length
															? (t(),
															  s("div", Vr, [
																	(t(!0),
																	s(
																		V,
																		null,
																		I(
																			o.value.subsystems,
																			(f) => (
																				t(),
																				s(
																					"span",
																					{
																						key: f,
																						class: "px-2.5 py-1 text-xs font-medium rounded-full bg-brand-50 text-brand-700",
																					},
																					a(f),
																					1
																				)
																			)
																		),
																		128
																	)),
															  ]))
															: (t(),
															  s("div", Or, "No subsystems selected yet.")),
														de.value
															? (t(), s("div", Tr, a(de.value), 1))
															: d("", !0),
												  ]))
												: d("", !0),
											r.value
												? (t(),
												  s("div", Ir, [
														l[37] ||
															(l[37] = e(
																"h3",
																{
																	class: "text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3",
																},
																" Breakdown Diagnosis ",
																-1
															)),
														se(
															Un,
															{
																"model-value": o.value.breakdown || {},
																disabled: !c.value,
																"can-edit-rca": u.value,
																onSave: ot,
																onSaveGroups: lt,
															},
															null,
															8,
															["model-value", "disabled", "can-edit-rca"]
														),
														ee.value
															? (t(),
															  s(
																	"div",
																	{
																		key: 0,
																		class: D([
																			"mt-2 text-sm",
																			pe.value
																				? "text-green-600"
																				: "text-red-600",
																		]),
																	},
																	a(ee.value),
																	3
															  ))
															: d("", !0),
												  ]))
												: d("", !0),
											g.value
												? (t(),
												  s("div", Dr, [
														e("div", qr, [
															l[38] ||
																(l[38] = e(
																	"h3",
																	{
																		class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
																	},
																	"Software Update",
																	-1
																)),
															e("div", Lr, [
																c.value && !ue.value
																	? (t(),
																	  s(
																			"button",
																			{
																				key: 0,
																				onClick: rt,
																				class: "text-sm text-brand-600 hover:underline",
																			},
																			" Edit "
																	  ))
																	: d("", !0),
																ue.value
																	? (t(),
																	  s("div", Br, [
																			e(
																				"button",
																				{
																					onClick: it,
																					class: "text-sm text-gray-500 hover:underline",
																				},
																				"Cancel"
																			),
																			e(
																				"button",
																				{
																					onClick: dt,
																					disabled: we.value,
																					class: "text-sm text-brand-600 font-medium hover:underline disabled:opacity-50",
																				},
																				a(
																					we.value
																						? "Saving…"
																						: "Save"
																				),
																				9,
																				Ur
																			),
																	  ]))
																	: d("", !0),
															]),
														]),
														ue.value
															? (t(),
															  X(
																	qe,
																	{
																		key: 0,
																		modelValue: ge.value,
																		"onUpdate:modelValue":
																			l[9] ||
																			(l[9] = (f) => (ge.value = f)),
																	},
																	null,
																	8,
																	["modelValue"]
															  ))
															: (t(),
															  X(
																	qe,
																	{
																		key: 1,
																		"model-value":
																			o.value.software_components || [],
																		disabled: !0,
																	},
																	null,
																	8,
																	["model-value"]
															  )),
														ce.value
															? (t(), s("div", Wr, a(ce.value), 1))
															: d("", !0),
												  ]))
												: d("", !0),
											$.value
												? (t(),
												  s("div", Jr, [
														se(
															Lo,
															{ "job-card-name": o.value.name, roles: Pe(p) },
															null,
															8,
															["job-card-name", "roles"]
														),
												  ]))
												: d("", !0),
											e("div", Hr, [
												o.value.opened_at
													? (t(),
													  s("span", Gr, "Opened: " + a(Ve(o.value.opened_at)), 1))
													: d("", !0),
												o.value.closed_at
													? (t(),
													  s("span", zr, "Closed: " + a(Ve(o.value.closed_at)), 1))
													: d("", !0),
											]),
										]),
										o.value.name
											? (t(),
											  X(
													el,
													{
														key: 0,
														"job-card-name": o.value.name,
														open: S.value,
														"onUpdate:open":
															l[10] || (l[10] = (f) => (S.value = f)),
														onSubmitted: l[11] || (l[11] = (f) => Pe(j).fetch()),
													},
													null,
													8,
													["job-card-name", "open"]
											  ))
											: d("", !0),
									],
									64
							  ))
							: d("", !0),
					])
				);
			};
		},
	},
	Qr = Z(Yr, [["__scopeId", "data-v-ece54028"]]),
	Kr = { key: 0, class: "text-center py-16 text-gray-400" },
	Xr = { key: 3, class: "text-center py-16 text-gray-500" },
	oi = {
		__name: "CustomerJobCard",
		props: { name: { type: String, required: !0 } },
		setup(m) {
			const { roles: C, loading: p } = Ue(),
				A = T(() =>
					ae(C, [
						"Depot Manager",
						"Service Engineer",
						"Technician",
						"Central Ops",
						"Administrator",
						"System Manager",
					])
				),
				c = T(() => ae(C, ["Customer"]) && !A.value);
			return (x, w) =>
				Pe(p)
					? (t(), s("div", Kr, " Loading... "))
					: c.value
					? (t(), X(Gt, { key: 1, "job-card-name": m.name }, null, 8, ["job-card-name"]))
					: A.value
					? (t(), X(Qr, { key: 2, name: m.name }, null, 8, ["name"]))
					: (t(), s("div", Xr, " You do not have permission to view this job card. "));
		},
	};
export { oi as default };
//# sourceMappingURL=CustomerJobCard-Y1zY0_mk.js.map
