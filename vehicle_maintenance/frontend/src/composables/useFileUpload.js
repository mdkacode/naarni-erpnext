/**
 * Frappe File upload helper.
 *
 * Wraps `/api/method/upload_file` so callers can pass a File and get back a
 * persisted file_url. Handles the multipart form, CSRF token from window.csrf_token,
 * and surfaces server errors with a useful message.
 *
 *   const { url } = await uploadFile(file, { doctype: "Job Card", docname, fieldname });
 *
 * The returned `url` is what you store on the doc field (Attach Image fieldtype).
 */

export async function uploadFile(file, opts = {}) {
	if (!(file instanceof File) && !(file instanceof Blob)) {
		throw new Error("uploadFile: argument must be a File or Blob");
	}

	const fd = new FormData();
	fd.append("file", file, file.name || "upload.bin");
	fd.append("is_private", opts.is_private ? "1" : "0");
	if (opts.doctype) fd.append("doctype", opts.doctype);
	if (opts.docname) fd.append("docname", opts.docname);
	if (opts.fieldname) fd.append("fieldname", opts.fieldname);
	if (opts.folder) fd.append("folder", opts.folder);
	if (opts.optimize) fd.append("optimize", "1");

	const csrf = (typeof window !== "undefined" && window.csrf_token) || "";
	const res = await fetch("/api/method/upload_file", {
		method: "POST",
		headers: {
			"X-Frappe-CSRF-Token": csrf,
			"X-Requested-With": "XMLHttpRequest",
		},
		body: fd,
		credentials: "same-origin",
	});

	let body = null;
	try {
		body = await res.json();
	} catch {
		body = null;
	}

	if (!res.ok) {
		const msg =
			body?._server_messages ||
			body?.exception ||
			body?.message ||
			`Upload failed (HTTP ${res.status})`;
		throw new Error(typeof msg === "string" ? msg : JSON.stringify(msg));
	}

	const data = body?.message || body || {};
	return {
		url: data.file_url || data.message?.file_url || "",
		name: data.name || data.file_name || "",
		raw: data,
	};
}
