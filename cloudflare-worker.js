// این کد رو داخل Cloudflare Worker (Edit code) جایگزین کد پیش‌فرض کنید و Deploy بزنید.
// Worker از طریق WebSocket به سرور مقصد (dst) روی پورت 443 وصل میشه و بایت‌ها رو
// در دو جهت پاس میده. برنامه‌ی اندروید دقیقاً با همین مسیر (/apiws?dst=...) صحبت می‌کنه.

import { connect } from "cloudflare:sockets";

function toBytes(data) {
	if (data instanceof ArrayBuffer) {
		return new Uint8Array(data);
	}
	if (typeof data === "string") {
		return new TextEncoder().encode(data);
	}
	return new Uint8Array();
}

// --- padding تصادفی ---
// هر پیام WS یه بایت اول داره که طول padding رو می‌گه، بعد همون مقدار بایت
// تصادفی، بعد داده‌ی واقعی. این کار اندازه‌ی ثابت و قابل‌تشخیص بسته‌های
// MTProto رو روی لینک app<->worker به هم می‌ریزه، بدون اینکه به جریان
// خام MTProto که به سمت تلگرام می‌ره دست بزنه (padding فقط همینجا اضافه/حذف می‌شه).
const MAX_PAD = 63;

function wrapWithPadding(data) {
	const padLen = Math.floor(Math.random() * (MAX_PAD + 1));
	const pad = new Uint8Array(padLen);
	crypto.getRandomValues(pad);
	const out = new Uint8Array(1 + padLen + data.length);
	out[0] = padLen;
	out.set(pad, 1);
	out.set(data, 1 + padLen);
	return out;
}

function unwrapPadding(data) {
	if (!data || data.length === 0) return new Uint8Array();
	const padLen = data[0];
	const start = 1 + padLen;
	if (start > data.length) return new Uint8Array();
	return data.slice(start);
}

export default {
	async fetch(request, env) {
		const url = new URL(request.url);

		// --- مسیر تست سریع کلید (بدون websocket، فقط یه GET ساده) ---
		// اپ قبل از فعال شدن، اینجا رو صدا می‌زنه تا مطمئن بشه کلید درسته.
		if (url.pathname === "/check") {
			const providedKey = request.headers.get("X-Auth-Key");
			if (!env.AUTH_KEY || providedKey !== env.AUTH_KEY) {
				return new Response("Forbidden", { status: 403 });
			}
			return new Response("ok", { status: 200 });
		}

		if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
			return new Response("Expected websocket", { status: 426 });
		}

		// --- احراز هویت با کلید مشترک ---
		// فقط درخواست‌هایی که هدر X-Auth-Key رو با مقدار درست بفرستن قبول می‌شن.
		// مقدار درست از یه Secret Variable به اسم AUTH_KEY خونده می‌شه (توی تنظیمات Worker ست می‌کنید).
		const providedKey = request.headers.get("X-Auth-Key");
		if (!env.AUTH_KEY || providedKey !== env.AUTH_KEY) {
			return new Response("Forbidden", { status: 403 });
		}

		if (url.pathname !== "/apiws") {
			return new Response("Not found", { status: 404 });
		}

		const dst = url.searchParams.get("dst");
		if (!dst) {
			return new Response("Missing dst", { status: 400 });
		}

		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];
		server.accept();

		const socket = connect({ hostname: dst, port: 443 });
		const tcpReader = socket.readable.getReader();
		const tcpWriter = socket.writable.getWriter();

		server.addEventListener("message", async (event) => {
			try {
				const real = unwrapPadding(toBytes(event.data));
				if (real.length > 0) await tcpWriter.write(real);
			} catch {
				try { server.close(1011, "tcp write failed"); } catch {}
			}
		});

		server.addEventListener("close", async () => {
			try { await tcpWriter.close(); } catch {}
			try { socket.close(); } catch {}
		});

		(async () => {
			try {
				while (true) {
					const { value, done } = await tcpReader.read();
					if (done) break;
					if (value) server.send(wrapWithPadding(value));
				}
			} catch {
			} finally {
				try { server.close(); } catch {}
				try { tcpReader.releaseLock(); } catch {}
				try { socket.close(); } catch {}
			}
		})();

		return new Response(null, { status: 101, webSocket: client });
	},
};
