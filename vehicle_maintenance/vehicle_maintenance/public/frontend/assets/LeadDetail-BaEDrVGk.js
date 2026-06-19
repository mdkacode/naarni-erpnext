var ce = Object.defineProperty;
var q = Object.getOwnPropertySymbols;
var me = Object.prototype.hasOwnProperty,
	pe = Object.prototype.propertyIsEnumerable;
var Q = (y, _, c) =>
		_ in y ? ce(y, _, { enumerable: !0, configurable: !0, writable: !0, value: c }) : (y[_] = c),
	W = (y, _) => {
		for (var c in _ || (_ = {})) me.call(_, c) && Q(y, c, _[c]);
		if (q) for (var c of q(_)) pe.call(_, c) && Q(y, c, _[c]);
		return y;
	};
var S = (y, _, c) =>
	new Promise((V, $) => {
		var a = (m) => {
				try {
					n(c.next(m));
				} catch (x) {
					$(x);
				}
			},
			C = (m) => {
				try {
					n(c.throw(m));
				} catch (x) {
					$(x);
				}
			},
			n = (m) => (m.done ? V(m.value) : Promise.resolve(m.value).then(a, C));
		n((c = c.apply(y, _)).next());
	});
import {
	o as s,
	a as i,
	b as e,
	t as d,
	w as X,
	i as H,
	q as h,
	v as R,
	z as M,
	F as I,
	r as F,
	u as J,
	j as u,
	y as ve,
	B as K,
	k as L,
	h as b,
	g as k,
	f as ye,
	e as _e,
	d as ge,
	x as xe,
	m as A,
	n as G,
} from "./main-CCKo9z3F.js";
import { u as Y, a as Z } from "./useCrmDropdowns-HGNnfTWS.js";
import { _ as ee } from "./_plugin-vue_export-helper-DlAUqK2U.js";
import "./api-BQ4HYjks.js";
const fe = { class: "bg-white border border-gray-200 rounded-xl p-4" },
	be = { class: "text-xs text-gray-500" },
	he = { class: "text-sm font-medium text-gray-900 mt-1" },
	j = {
		__name: "InfoCard",
		props: { label: { type: String, required: !0 }, value: { type: [String, Number], default: "" } },
		setup(y) {
			return (_, c) => (
				s(), i("div", fe, [e("div", be, d(y.label), 1), e("div", he, d(y.value || "—"), 1)])
			);
		},
	},
	we = { class: "bg-white rounded-2xl shadow-xl w-full max-w-md p-6" },
	ke = { class: "space-y-3" },
	Ce = ["value"],
	Se = { key: 0, class: "text-xs text-gray-500 mt-1" },
	je = { key: 0 },
	Ve = ["placeholder"],
	$e = { key: 0, class: "mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700" },
	Ne = { class: "mt-6 flex justify-end gap-2" },
	De = ["disabled"],
	Ie = {
		__name: "LeadReminderModal",
		props: {
			modelValue: { type: Boolean, default: !1 },
			lead: { type: String, required: !0 },
			defaultRecipient: { type: String, default: "" },
		},
		emits: ["update:modelValue", "scheduled"],
		setup(y, { emit: _ }) {
			const c = y,
				V = _,
				$ = Z(),
				{ dropdowns: a, load: C } = Y(),
				n = K({
					reminder_datetime: "",
					message_template: "",
					subject: "Follow-up",
					message_body: "",
					recipient_email: "",
				}),
				m = b(!1),
				x = b(""),
				g = L(() => a.value.email_templates.find((v) => v.name === n.message_template));
			X(g, (v) => {
				v != null && v.subject && (n.subject = v.subject);
			}),
				X(
					() => c.modelValue,
					(v) => {
						v &&
							((x.value = ""),
							(n.reminder_datetime = ""),
							(n.message_template = ""),
							(n.subject = "Follow-up"),
							(n.message_body = ""),
							(n.recipient_email = ""));
					}
				);
			function w() {
				V("update:modelValue", !1);
			}
			function U() {
				return S(this, null, function* () {
					if (((x.value = ""), !n.reminder_datetime)) {
						x.value = "Pick a date & time.";
						return;
					}
					if (!n.message_template && !n.message_body.trim()) {
						x.value = "Provide a message or pick a template.";
						return;
					}
					m.value = !0;
					try {
						yield $.scheduleReminder({
							lead: c.lead,
							reminder_datetime: new Date(n.reminder_datetime).toISOString(),
							subject: n.subject,
							message_template: n.message_template || null,
							message_body: n.message_template ? null : n.message_body,
							recipient_email: n.recipient_email || null,
							channel: "Email",
						}),
							V("scheduled"),
							w();
					} catch (v) {
						x.value = (v == null ? void 0 : v.message) || "Failed to schedule reminder.";
					} finally {
						m.value = !1;
					}
				});
			}
			return (
				H(() => C()),
				(v, r) =>
					y.modelValue
						? (s(),
						  i(
								"div",
								{
									key: 0,
									class: "fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4",
									onClick: ve(w, ["self"]),
								},
								[
									e("div", we, [
										e("div", { class: "flex items-center justify-between mb-4" }, [
											r[5] ||
												(r[5] = e(
													"h2",
													{ class: "text-lg font-semibold text-gray-900" },
													"Schedule reminder",
													-1
												)),
											e(
												"button",
												{ class: "text-gray-400 hover:text-gray-600", onClick: w },
												"✕"
											),
										]),
										e("div", ke, [
											e("div", null, [
												r[6] || (r[6] = e("label", { class: "label" }, "When", -1)),
												h(
													e(
														"input",
														{
															"onUpdate:modelValue":
																r[0] ||
																(r[0] = (p) => (n.reminder_datetime = p)),
															type: "datetime-local",
															class: "input-field",
														},
														null,
														512
													),
													[[R, n.reminder_datetime]]
												),
											]),
											e("div", null, [
												r[8] ||
													(r[8] = e(
														"label",
														{ class: "label" },
														"Email Template",
														-1
													)),
												h(
													e(
														"select",
														{
															"onUpdate:modelValue":
																r[1] ||
																(r[1] = (p) => (n.message_template = p)),
															class: "select-field",
														},
														[
															r[7] ||
																(r[7] = e(
																	"option",
																	{ value: "" },
																	"— None (write a custom message) —",
																	-1
																)),
															(s(!0),
															i(
																I,
																null,
																F(
																	J(a).email_templates,
																	(p) => (
																		s(),
																		i(
																			"option",
																			{ key: p.name, value: p.name },
																			d(p.name),
																			9,
																			Ce
																		)
																	)
																),
																128
															)),
														],
														512
													),
													[[M, n.message_template]]
												),
												g.value
													? (s(), i("p", Se, " Subject: " + d(g.value.subject), 1))
													: u("", !0),
											]),
											e("div", null, [
												r[9] ||
													(r[9] = e("label", { class: "label" }, "Subject", -1)),
												h(
													e(
														"input",
														{
															"onUpdate:modelValue":
																r[2] || (r[2] = (p) => (n.subject = p)),
															type: "text",
															class: "input-field",
														},
														null,
														512
													),
													[[R, n.subject]]
												),
											]),
											n.message_template
												? u("", !0)
												: (s(),
												  i("div", je, [
														r[10] ||
															(r[10] = e(
																"label",
																{ class: "label" },
																"Message",
																-1
															)),
														h(
															e(
																"textarea",
																{
																	"onUpdate:modelValue":
																		r[3] ||
																		(r[3] = (p) => (n.message_body = p)),
																	rows: "4",
																	class: "input-field",
																},
																null,
																512
															),
															[[R, n.message_body]]
														),
												  ])),
											e("div", null, [
												r[11] ||
													(r[11] = e(
														"label",
														{ class: "label" },
														"Recipient email",
														-1
													)),
												h(
													e(
														"input",
														{
															"onUpdate:modelValue":
																r[4] ||
																(r[4] = (p) => (n.recipient_email = p)),
															type: "email",
															class: "input-field",
															placeholder:
																y.defaultRecipient || "lead@example.com",
														},
														null,
														8,
														Ve
													),
													[[R, n.recipient_email]]
												),
												r[12] ||
													(r[12] = e(
														"p",
														{ class: "text-xs text-gray-400 mt-1" },
														" Defaults to the lead's email on dispatch if left blank. ",
														-1
													)),
											]),
										]),
										x.value ? (s(), i("div", $e, d(x.value), 1)) : u("", !0),
										e("div", Ne, [
											e(
												"button",
												{
													class: "px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50",
													onClick: w,
												},
												" Cancel "
											),
											e(
												"button",
												{
													class: "px-4 py-2 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50",
													disabled: m.value,
													onClick: U,
												},
												d(m.value ? "Scheduling…" : "Schedule"),
												9,
												De
											),
										]),
									]),
								]
						  ))
						: u("", !0)
			);
		},
	},
	Fe = ee(Ie, [["__scopeId", "data-v-cb4fee9e"]]),
	Re = { class: "max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-6" },
	Ue = { class: "mb-4" },
	Ae = { key: 0, class: "text-center py-12 text-gray-400" },
	Me = { key: 1, class: "space-y-6" },
	Be = { class: "bg-white border border-gray-200 rounded-xl p-5" },
	Le = { class: "flex items-start justify-between gap-4" },
	Te = { class: "text-xl font-bold text-gray-900" },
	Ee = { class: "text-sm text-gray-500 mt-0.5" },
	ze = { class: "flex flex-wrap gap-x-6 gap-y-1 mt-3 text-sm text-gray-700" },
	Oe = { key: 0 },
	Pe = { key: 1 },
	qe = { key: 2 },
	Qe = { class: "flex flex-col items-end gap-2" },
	We = { class: "flex items-center gap-2" },
	Xe = ["value", "disabled"],
	Ge = ["value"],
	He = { key: 0, class: "text-xs text-green-700 bg-green-50 px-2 py-1 rounded" },
	Je = { class: "border-b border-gray-200" },
	Ke = { class: "flex gap-6" },
	Ye = ["onClick"],
	Ze = { key: 0, class: "ml-1 text-xs text-gray-400" },
	et = { key: 0, class: "grid grid-cols-1 md:grid-cols-2 gap-4" },
	tt = { class: "md:col-span-2 bg-white border border-gray-200 rounded-xl p-4" },
	lt = { class: "text-sm text-gray-800 whitespace-pre-wrap" },
	at = { key: 1, class: "space-y-4" },
	st = { class: "bg-white border border-gray-200 rounded-xl p-4" },
	it = { class: "grid grid-cols-1 md:grid-cols-4 gap-2" },
	nt = ["disabled"],
	ot = { class: "bg-white border border-gray-200 rounded-xl divide-y divide-gray-100" },
	dt = { key: 0, class: "p-6 text-center text-sm text-gray-400" },
	rt = { class: "text-lg" },
	ut = { class: "flex-1" },
	ct = { class: "text-sm font-medium text-gray-900" },
	mt = { key: 0, class: "ml-2 text-xs font-normal text-gray-500" },
	pt = { key: 0, class: "text-sm text-gray-700 mt-0.5 whitespace-pre-wrap" },
	vt = { class: "text-xs text-gray-400 mt-1" },
	yt = { key: 0 },
	_t = { key: 1 },
	gt = { key: 2, class: "space-y-4" },
	xt = { class: "flex justify-end" },
	ft = { class: "bg-white border border-gray-200 rounded-xl divide-y divide-gray-100" },
	bt = { key: 0, class: "p-6 text-center text-sm text-gray-400" },
	ht = { class: "text-sm font-medium text-gray-900" },
	wt = { class: "text-xs text-gray-500 mt-0.5" },
	kt = { key: 0 },
	Ct = { key: 0, class: "text-xs text-red-600 mt-1" },
	St = { class: "flex items-center gap-2" },
	jt = ["onClick"],
	Vt = { key: 3, class: "space-y-4" },
	$t = { class: "bg-white border border-gray-200 rounded-xl p-4" },
	Nt = ["disabled"],
	Dt = { key: 0, class: "text-sm text-red-600 mt-2" },
	It = { class: "grid grid-cols-2 md:grid-cols-3 gap-3" },
	Ft = ["href"],
	Rt = ["src", "alt"],
	Ut = { key: 1, class: "w-full h-40 flex items-center justify-center bg-gray-50 text-gray-400" },
	At = { class: "p-2 text-xs text-gray-600 truncate" },
	Mt = { key: 0, class: "col-span-full text-center text-sm text-gray-400 py-6" },
	Bt = {
		__name: "LeadDetail",
		setup(y) {
			const _ = xe(),
				c = Z(),
				{ dropdowns: V, load: $ } = Y(),
				a = b(null),
				C = b([]),
				n = b(!0),
				m = b("overview"),
				x = b(!1),
				g = K({ activity_type: "", outcome: "", next_action: "", summary: "" }),
				w = b(!1),
				U = b(!1),
				v = b(""),
				r = b(!1),
				p = b(""),
				te = L(() => {
					var o, l;
					return [
						{ id: "overview", label: "Overview" },
						{
							id: "activities",
							label: "Activities",
							count: (((o = a.value) == null ? void 0 : o.activities) || []).length,
						},
						{ id: "reminders", label: "Reminders", count: C.value.length },
						{
							id: "attachments",
							label: "Attachments",
							count: (((l = a.value) == null ? void 0 : l.attachments) || []).length,
						},
					];
				}),
				le = L(() => {
					var o;
					return [...(((o = a.value) == null ? void 0 : o.activities) || [])].sort(
						(l, D) => new Date(D.activity_date) - new Date(l.activity_date)
					);
				});
			function N() {
				return S(this, null, function* () {
					n.value = !0;
					try {
						const o = yield c.get(_.params.name);
						(a.value = o.lead), (C.value = o.reminders || []);
					} finally {
						n.value = !1;
					}
				});
			}
			function ae(o) {
				return S(this, null, function* () {
					if (!(!o || o === a.value.status)) {
						x.value = !0;
						try {
							yield c.updateStatus(a.value.name, o), yield N();
						} finally {
							x.value = !1;
						}
					}
				});
			}
			function se() {
				return S(this, null, function* () {
					if (g.activity_type) {
						w.value = !0;
						try {
							yield c.addActivity(a.value.name, W({}, g)),
								Object.assign(g, {
									activity_type: "",
									outcome: "",
									next_action: "",
									summary: "",
								}),
								yield N();
						} finally {
							w.value = !1;
						}
					}
				});
			}
			function ie(o) {
				return S(this, null, function* () {
					yield c.cancelReminder(o), yield N();
				});
			}
			function ne(o) {
				return S(this, null, function* () {
					var D, t;
					const l = (D = o.target.files) == null ? void 0 : D[0];
					if (l) {
						if (((p.value = ""), l.size > 5 * 1024 * 1024)) {
							(p.value = "File exceeds 5 MB."), (o.target.value = "");
							return;
						}
						r.value = !0;
						try {
							const f = new FormData();
							f.append("file", l),
								f.append("is_private", "1"),
								f.append("doctype", "Lead"),
								f.append("docname", a.value.name);
							const z = {};
							window.csrf_token && (z["X-Frappe-CSRF-Token"] = window.csrf_token);
							const B = yield fetch("/api/method/upload_file", {
								method: "POST",
								credentials: "include",
								headers: z,
								body: f,
							});
							if (!B.ok) throw new Error(`Upload failed (${B.status})`);
							const O = yield B.json(),
								P = ((t = O.message) == null ? void 0 : t.file_url) || O.file_url;
							if (!P) throw new Error("No file_url in upload response.");
							yield c.uploadAttachment(a.value.name, P, v.value),
								(v.value = ""),
								(o.target.value = ""),
								yield N();
						} catch (f) {
							p.value = (f == null ? void 0 : f.message) || "Upload failed.";
						} finally {
							r.value = !1;
						}
					}
				});
			}
			function oe(o) {
				const l = "inline-flex px-2 py-0.5 rounded-full text-xs font-medium";
				return o === "Scheduled"
					? `${l} bg-blue-50 text-blue-700`
					: o === "Sent"
					? `${l} bg-green-50 text-green-700`
					: o === "Failed"
					? `${l} bg-red-50 text-red-700`
					: `${l} bg-gray-100 text-gray-600`;
			}
			function de(o) {
				return (
					{ Call: "📞", Visit: "🚗", Demo: "💻", "Quote Sent": "📄", Email: "✉️", WhatsApp: "💬" }[
						o
					] || "📝"
				);
			}
			function re(o) {
				return /\.(jpe?g|png|webp|gif)$/i.test(o || "");
			}
			function T(o) {
				return (o || "").split("/").pop();
			}
			function ue(o) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					maximumFractionDigits: 0,
				}).format(o);
			}
			function E(o) {
				return o
					? new Date(o).toLocaleString("en-IN", {
							day: "2-digit",
							month: "short",
							year: "2-digit",
							hour: "2-digit",
							minute: "2-digit",
					  })
					: "";
			}
			return (
				H(() =>
					S(this, null, function* () {
						yield $(), yield N();
					})
				),
				(o, l) => {
					const D = ge("router-link");
					return (
						s(),
						i("div", Re, [
							e("div", Ue, [
								k(
									D,
									{
										to: "/service-portal/crm/leads",
										class: "text-sm text-brand-600 hover:underline",
									},
									{
										default: ye(() => [...(l[8] || (l[8] = [A("← All leads", -1)]))]),
										_: 1,
									}
								),
							]),
							n.value
								? (s(), i("div", Ae, "Loading…"))
								: a.value
								? (s(),
								  i("div", Me, [
										e("div", Be, [
											e("div", Le, [
												e("div", null, [
													e("h1", Te, d(a.value.lead_name), 1),
													e(
														"p",
														Ee,
														d(a.value.company_name || "Individual lead"),
														1
													),
													e("div", ze, [
														e("span", null, "📞 " + d(a.value.phone), 1),
														a.value.email
															? (s(),
															  i("span", Oe, "✉️ " + d(a.value.email), 1))
															: u("", !0),
														a.value.city || a.value.state
															? (s(),
															  i(
																	"span",
																	Pe,
																	" 📍 " +
																		d(
																			[a.value.city, a.value.state]
																				.filter(Boolean)
																				.join(", ")
																		),
																	1
															  ))
															: u("", !0),
														a.value.estimated_value
															? (s(),
															  i(
																	"span",
																	qe,
																	" 💰 " + d(ue(a.value.estimated_value)),
																	1
															  ))
															: u("", !0),
													]),
												]),
												e("div", Qe, [
													e("div", We, [
														l[9] ||
															(l[9] = e(
																"label",
																{ class: "text-xs text-gray-500" },
																"Status",
																-1
															)),
														e(
															"select",
															{
																value: a.value.status,
																onChange:
																	l[0] ||
																	(l[0] = (t) => ae(t.target.value)),
																class: "select-field w-44",
																disabled: x.value,
															},
															[
																(s(!0),
																i(
																	I,
																	null,
																	F(
																		J(V).statuses,
																		(t) => (
																			s(),
																			i(
																				"option",
																				{
																					key: t.name,
																					value: t.name,
																				},
																				d(t.status_name),
																				9,
																				Ge
																			)
																		)
																	),
																	128
																)),
															],
															40,
															Xe
														),
													]),
													a.value.converted_to_customer
														? (s(),
														  i(
																"span",
																He,
																" Converted → " +
																	d(a.value.converted_to_customer),
																1
														  ))
														: u("", !0),
												]),
											]),
										]),
										e("div", Je, [
											e("nav", Ke, [
												(s(!0),
												i(
													I,
													null,
													F(
														te.value,
														(t) => (
															s(),
															i(
																"button",
																{
																	key: t.id,
																	class: G([
																		"pb-2 text-sm font-medium border-b-2 transition",
																		m.value === t.id
																			? "border-brand-600 text-brand-700"
																			: "border-transparent text-gray-500 hover:text-gray-700",
																	]),
																	onClick: (f) => (m.value = t.id),
																},
																[
																	A(d(t.label) + " ", 1),
																	t.count != null
																		? (s(), i("span", Ze, d(t.count), 1))
																		: u("", !0),
																],
																10,
																Ye
															)
														)
													),
													128
												)),
											]),
										]),
										m.value === "overview"
											? (s(),
											  i("div", et, [
													k(
														j,
														{ label: "Source", value: a.value.lead_source },
														null,
														8,
														["value"]
													),
													k(
														j,
														{ label: "Industry", value: a.value.industry },
														null,
														8,
														["value"]
													),
													k(
														j,
														{
															label: "Interested In",
															value: a.value.interested_in,
														},
														null,
														8,
														["value"]
													),
													k(
														j,
														{
															label: "Fleet Size",
															value: a.value.fleet_size_bucket,
														},
														null,
														8,
														["value"]
													),
													k(
														j,
														{ label: "Priority", value: a.value.priority },
														null,
														8,
														["value"]
													),
													k(
														j,
														{ label: "Assigned To", value: a.value.assigned_to },
														null,
														8,
														["value"]
													),
													k(j, { label: "Depot", value: a.value.depot }, null, 8, [
														"value",
													]),
													k(
														j,
														{
															label: "Expected Close",
															value: a.value.expected_close_date,
														},
														null,
														8,
														["value"]
													),
													e("div", tt, [
														l[10] ||
															(l[10] = e(
																"div",
																{ class: "text-xs text-gray-500 mb-1" },
																"Notes",
																-1
															)),
														e("div", lt, d(a.value.notes || "—"), 1),
													]),
											  ]))
											: u("", !0),
										m.value === "activities"
											? (s(),
											  i("div", at, [
													e("div", st, [
														l[14] ||
															(l[14] = e(
																"h3",
																{
																	class: "text-sm font-semibold text-gray-800 mb-3",
																},
																"Log an activity",
																-1
															)),
														e("div", it, [
															h(
																e(
																	"select",
																	{
																		"onUpdate:modelValue":
																			l[1] ||
																			(l[1] = (t) =>
																				(g.activity_type = t)),
																		class: "select-field",
																	},
																	[
																		...(l[11] ||
																			(l[11] = [
																				e(
																					"option",
																					{ value: "" },
																					"Type",
																					-1
																				),
																				e("option", null, "Call", -1),
																				e(
																					"option",
																					null,
																					"Visit",
																					-1
																				),
																				e("option", null, "Demo", -1),
																				e(
																					"option",
																					null,
																					"Quote Sent",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Email",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"WhatsApp",
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
																[[M, g.activity_type]]
															),
															h(
																e(
																	"select",
																	{
																		"onUpdate:modelValue":
																			l[2] ||
																			(l[2] = (t) => (g.outcome = t)),
																		class: "select-field",
																	},
																	[
																		...(l[12] ||
																			(l[12] = [
																				e(
																					"option",
																					{ value: "" },
																					"Outcome",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Interested",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Not Interested",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Callback Requested",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Meeting Scheduled",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"No Response",
																					-1
																				),
																			])),
																	],
																	512
																),
																[[M, g.outcome]]
															),
															h(
																e(
																	"select",
																	{
																		"onUpdate:modelValue":
																			l[3] ||
																			(l[3] = (t) =>
																				(g.next_action = t)),
																		class: "select-field",
																	},
																	[
																		...(l[13] ||
																			(l[13] = [
																				e(
																					"option",
																					{ value: "" },
																					"Next action",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Call Back",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Send Quote",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Schedule Demo",
																					-1
																				),
																				e(
																					"option",
																					null,
																					"Close as Lost",
																					-1
																				),
																				e("option", null, "None", -1),
																			])),
																	],
																	512
																),
																[[M, g.next_action]]
															),
															e(
																"button",
																{
																	class: "px-3 py-2 text-sm text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50",
																	disabled: !g.activity_type || w.value,
																	onClick: se,
																},
																d(w.value ? "…" : "Add"),
																9,
																nt
															),
														]),
														h(
															e(
																"textarea",
																{
																	"onUpdate:modelValue":
																		l[4] ||
																		(l[4] = (t) => (g.summary = t)),
																	rows: "2",
																	class: "input-field mt-2",
																	placeholder: "Short note (optional)",
																},
																null,
																512
															),
															[[R, g.summary]]
														),
													]),
													e("div", ot, [
														(a.value.activities || []).length
															? u("", !0)
															: (s(), i("div", dt, " No activities yet. ")),
														(s(!0),
														i(
															I,
															null,
															F(
																le.value,
																(t) => (
																	s(),
																	i(
																		"div",
																		{
																			key: t.name,
																			class: "p-4 flex items-start gap-3",
																		},
																		[
																			e(
																				"span",
																				rt,
																				d(de(t.activity_type)),
																				1
																			),
																			e("div", ut, [
																				e("div", ct, [
																					A(
																						d(t.activity_type) +
																							" ",
																						1
																					),
																					t.outcome
																						? (s(),
																						  i(
																								"span",
																								mt,
																								"· " +
																									d(
																										t.outcome
																									),
																								1
																						  ))
																						: u("", !0),
																				]),
																				t.summary
																					? (s(),
																					  i(
																							"div",
																							pt,
																							d(t.summary),
																							1
																					  ))
																					: u("", !0),
																				e("div", vt, [
																					A(
																						d(
																							E(t.activity_date)
																						) + " ",
																						1
																					),
																					t.performed_by
																						? (s(),
																						  i(
																								"span",
																								yt,
																								" · " +
																									d(
																										t.performed_by
																									),
																								1
																						  ))
																						: u("", !0),
																					t.next_action &&
																					t.next_action !== "None"
																						? (s(),
																						  i(
																								"span",
																								_t,
																								" · next: " +
																									d(
																										t.next_action
																									),
																								1
																						  ))
																						: u("", !0),
																				]),
																			]),
																		]
																	)
																)
															),
															128
														)),
													]),
											  ]))
											: u("", !0),
										m.value === "reminders"
											? (s(),
											  i("div", gt, [
													e("div", xt, [
														e(
															"button",
															{
																class: "px-3 py-2 text-sm text-white bg-brand-600 rounded-lg hover:bg-brand-700",
																onClick:
																	l[5] || (l[5] = (t) => (U.value = !0)),
															},
															" + Schedule reminder "
														),
													]),
													e("div", ft, [
														C.value.length
															? u("", !0)
															: (s(),
															  i("div", bt, " No reminders scheduled. ")),
														(s(!0),
														i(
															I,
															null,
															F(
																C.value,
																(t) => (
																	s(),
																	i(
																		"div",
																		{
																			key: t.name,
																			class: "p-4 flex items-start justify-between gap-3",
																		},
																		[
																			e("div", null, [
																				e(
																					"div",
																					ht,
																					d(
																						t.subject ||
																							"(no subject)"
																					),
																					1
																				),
																				e("div", wt, [
																					A(
																						d(
																							E(
																								t.reminder_datetime
																							)
																						) +
																							" · " +
																							d(t.channel) +
																							" ",
																						1
																					),
																					t.recipient_email
																						? (s(),
																						  i(
																								"span",
																								kt,
																								" · " +
																									d(
																										t.recipient_email
																									),
																								1
																						  ))
																						: u("", !0),
																				]),
																				t.failure_reason
																					? (s(),
																					  i(
																							"div",
																							Ct,
																							d(
																								t.failure_reason
																							),
																							1
																					  ))
																					: u("", !0),
																			]),
																			e("div", St, [
																				e(
																					"span",
																					{
																						class: G(
																							oe(t.status)
																						),
																					},
																					d(t.status),
																					3
																				),
																				t.status === "Scheduled"
																					? (s(),
																					  i(
																							"button",
																							{
																								key: 0,
																								class: "text-xs text-red-600 hover:underline",
																								onClick: (
																									f
																								) =>
																									ie(
																										t.name
																									),
																							},
																							" Cancel ",
																							8,
																							jt
																					  ))
																					: u("", !0),
																			]),
																		]
																	)
																)
															),
															128
														)),
													]),
											  ]))
											: u("", !0),
										m.value === "attachments"
											? (s(),
											  i("div", Vt, [
													e("div", $t, [
														l[15] ||
															(l[15] = e(
																"label",
																{ class: "label" },
																"Upload file (image or PDF, max 5 MB)",
																-1
															)),
														e(
															"input",
															{
																type: "file",
																accept: "image/jpeg,image/png,image/webp,application/pdf",
																onChange: ne,
																disabled: r.value,
																class: "block text-sm",
															},
															null,
															40,
															Nt
														),
														h(
															e(
																"input",
																{
																	"onUpdate:modelValue":
																		l[6] || (l[6] = (t) => (v.value = t)),
																	type: "text",
																	class: "input-field mt-2",
																	placeholder: "Caption (optional)",
																},
																null,
																512
															),
															[[R, v.value]]
														),
														p.value
															? (s(), i("p", Dt, d(p.value), 1))
															: u("", !0),
													]),
													e("div", It, [
														(s(!0),
														i(
															I,
															null,
															F(
																a.value.attachments || [],
																(t, f) => (
																	s(),
																	i(
																		"div",
																		{
																			key: f,
																			class: "bg-white border border-gray-200 rounded-xl overflow-hidden",
																		},
																		[
																			e(
																				"a",
																				{
																					href: t.file_url,
																					target: "_blank",
																					class: "block",
																				},
																				[
																					re(t.file_url)
																						? (s(),
																						  i(
																								"img",
																								{
																									key: 0,
																									src: t.file_url,
																									alt:
																										t.caption ||
																										"attachment",
																									class: "w-full h-40 object-cover",
																								},
																								null,
																								8,
																								Rt
																						  ))
																						: (s(),
																						  i(
																								"div",
																								Ut,
																								" 📄 " +
																									d(
																										T(
																											t.file_url
																										)
																									),
																								1
																						  )),
																				],
																				8,
																				Ft
																			),
																			e(
																				"div",
																				At,
																				d(t.caption || T(t.file_url)),
																				1
																			),
																		]
																	)
																)
															),
															128
														)),
														(a.value.attachments || []).length
															? u("", !0)
															: (s(), i("div", Mt, " No attachments yet. ")),
													]),
											  ]))
											: u("", !0),
								  ]))
								: u("", !0),
							a.value
								? (s(),
								  _e(
										Fe,
										{
											key: 2,
											modelValue: U.value,
											"onUpdate:modelValue": l[7] || (l[7] = (t) => (U.value = t)),
											lead: a.value.name,
											"default-recipient": a.value.email,
											onScheduled: N,
										},
										null,
										8,
										["modelValue", "lead", "default-recipient"]
								  ))
								: u("", !0),
						])
					);
				}
			);
		},
	},
	Pt = ee(Bt, [["__scopeId", "data-v-59383c55"]]);
export { Pt as default };
//# sourceMappingURL=LeadDetail-BaEDrVGk.js.map
