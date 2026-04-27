var v = (b, c, n) =>
	new Promise((s, r) => {
		var l = (o) => {
				try {
					d(n.next(o));
				} catch (i) {
					r(i);
				}
			},
			m = (o) => {
				try {
					d(n.throw(o));
				} catch (i) {
					r(i);
				}
			},
			d = (o) => (o.done ? s(o.value) : Promise.resolve(o.value).then(l, m));
		d((n = n.apply(b, c)).next());
	});
import {
	o as x,
	a as f,
	b as t,
	y as w,
	n as _,
	t as g,
	q as k,
	v as P,
	j as I,
	h as u,
	k as h,
	x as N,
} from "./main-CCKo9z3F.js";
const S = { class: "min-h-screen flex items-center justify-center bg-gray-50 px-4" },
	T = { class: "w-full max-w-sm" },
	C = { class: "flex items-stretch" },
	E = ["value"],
	L = { key: 0, class: "text-sm text-red-600" },
	q = ["disabled"],
	V = {
		__name: "Login",
		setup(b) {
			const c = N(),
				n = u(""),
				s = u(""),
				r = u(""),
				l = u(!1),
				m = /^[6-9]\d{9}$/;
			function d(a) {
				const e = (a.target.value || "").replace(/\D+/g, "").slice(0, 10);
				(n.value = e), a.target.value !== e && (a.target.value = e);
			}
			const o = h(() => m.test(n.value)),
				i = h(() =>
					n.value
						? n.value.length < 10
							? { tone: "text-gray-400", text: `${n.value.length}/10 digits entered.` }
							: o.value
							? { tone: "text-green-600", text: "Looks good." }
							: { tone: "text-red-600", text: "Indian mobile numbers start with 6, 7, 8 or 9." }
						: { tone: "text-gray-400", text: "We'll send the OTP to this number." }
				);
			function y() {
				return v(this, null, function* () {
					if (((r.value = ""), !o.value)) {
						r.value = "Enter a valid 10-digit mobile number (starting with 6/7/8/9).";
						return;
					}
					l.value = !0;
					try {
						const a = yield fetch("/api/method/vehicle_maintenance.api.auth.login_with_phone", {
								method: "POST",
								headers: { "Content-Type": "application/x-www-form-urlencoded" },
								body: `phone=${encodeURIComponent(n.value)}&password=${encodeURIComponent(
									s.value
								)}`,
							}),
							e = yield a.json();
						if (!a.ok || e.exc_type) throw new Error(e.message || "Login failed");
						const p = c.query.redirect || "/service-portal";
						window.location.href = p;
					} catch (a) {
						r.value = "Invalid phone number or password.";
					} finally {
						l.value = !1;
					}
				});
			}
			return (a, e) => (
				x(),
				f("div", S, [
					t("div", T, [
						e[4] ||
							(e[4] = t(
								"h1",
								{ class: "text-2xl font-bold text-center text-gray-900 mb-8" },
								"NaArNi Service Portal Login",
								-1
							)),
						t(
							"form",
							{
								onSubmit: w(y, ["prevent"]),
								class: "bg-white shadow rounded-xl p-6 space-y-4",
							},
							[
								t("div", null, [
									e[2] ||
										(e[2] = t(
											"label",
											{ class: "block text-sm font-medium text-gray-700 mb-1" },
											"Phone Number",
											-1
										)),
									t("div", C, [
										e[1] ||
											(e[1] = t(
												"span",
												{
													class: "inline-flex items-center px-3 text-sm text-gray-500 bg-gray-50 border border-r-0 border-gray-300 rounded-l-lg",
												},
												" +91 ",
												-1
											)),
										t(
											"input",
											{
												value: n.value,
												onInput: d,
												type: "tel",
												inputmode: "numeric",
												maxlength: "10",
												pattern: "[6-9][0-9]{9}",
												required: "",
												class: "flex-1 px-3 py-2 border border-gray-300 rounded-r-lg focus:ring-2 focus:ring-brand-500 focus:border-brand-500 tracking-wider",
												placeholder: "10-digit mobile number",
												autocomplete: "tel",
												"aria-describedby": "phone-hint",
											},
											null,
											40,
											E
										),
									]),
									t(
										"p",
										{ id: "phone-hint", class: _(["mt-1 text-xs", i.value.tone]) },
										g(i.value.text),
										3
									),
								]),
								t("div", null, [
									e[3] ||
										(e[3] = t(
											"label",
											{ class: "block text-sm font-medium text-gray-700 mb-1" },
											"Password",
											-1
										)),
									k(
										t(
											"input",
											{
												"onUpdate:modelValue": e[0] || (e[0] = (p) => (s.value = p)),
												type: "password",
												required: "",
												class: "w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-brand-500 focus:border-brand-500",
											},
											null,
											512
										),
										[[P, s.value]]
									),
								]),
								r.value ? (x(), f("div", L, g(r.value), 1)) : I("", !0),
								t(
									"button",
									{
										type: "submit",
										disabled: l.value || !o.value || !s.value,
										class: "w-full py-2.5 px-4 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors",
									},
									g(l.value ? "Signing in..." : "Sign In"),
									9,
									q
								),
							],
							32
						),
					]),
				])
			);
		},
	};
export { V as default };
//# sourceMappingURL=Login-dfpV-52z.js.map
