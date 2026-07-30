// این کد رو داخل Cloudflare Worker (Edit code) جایگزین کد پیش‌فرض کنید و Deploy بزنید.
// نکته‌ی مهم: این نسخه از یه Durable Object (کلاس Relay) برای نگه‌داشتن اتصال
// طولانی‌مدت استفاده می‌کنه، چون یه Worker معمولی بعد از حدود ۳۰ ثانیه اتصال رو
// قطع می‌کنه (محدودیت پلتفرم Cloudflare، نه باگ کد). بعد از پیست کردن این کد،
// باید یه Durable Object Binding هم توی تنظیمات Worker اضافه کنید (توضیحش پایین‌تره).

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

		// --- مسیر تست سریع کلید ---
		if (url.pathname === "/check") {
			const providedKey = request.headers.get("X-Auth-Key");
			if (!env.AUTH_KEY || providedKey !== env.AUTH_KEY) {
				return new Response("Forbidden", { status: 403 });
			}
			return new Response("ok", { status: 200 });
		}

		if (url.pathname !== "/apiws") {
			return new Response("Not found", { status: 404 });
		}

		if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
			return new Response("Expected websocket", { status: 426 });
		}

		const providedKey = request.headers.get("X-Auth-Key");
		if (!env.AUTH_KEY || providedKey !== env.AUTH_KEY) {
			return new Response("Forbidden", { status: 403 });
		}

		const dst = url.searchParams.get("dst");
		if (!dst) {
			return new Response("Missing dst", { status: 400 });
		}

		// هر اتصال یه Durable Object جدا و مخصوص خودش می‌گیره تا بتونه
		// مستقل از محدودیت زمانی fetch معمولی، زنده بمونه.
		const id = env.RELAY.newUniqueId();
		const stub = env.RELAY.get(id);
		return stub.fetch(request);
	},
};

export class Relay {
	constructor(state, env) {
		this.state = state;
		this.sockets = new Map(); // WebSocket -> { tcpSocket, writer }
	}

	async fetch(request) {
		const url = new URL(request.url);
		const dst = url.searchParams.get("dst");

		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];

		this.state.acceptWebSocket(server);

		try {
			const tcpSocket = connect({ hostname: dst, port: 443 });
			const timeout = new Promise((_, reject) =>
				setTimeout(() => reject(new Error("connect timeout")), 10000)
			);
			await Promise.race([tcpSocket.opened, timeout]);

			const writer = tcpSocket.writable.getWriter();
			this.sockets.set(server, { tcpSocket, writer });

			const reader = tcpSocket.readable.getReader();
			(async () => {
				try {
					while (true) {
						const { value, done } = await reader.read();
						if (done) break;
						if (value) server.send(wrapWithPadding(value));
					}
				} catch (err) {
					console.error(`tcp read failed — ${err && err.message}`);
				} finally {
					try { server.close(); } catch {}
					try { reader.releaseLock(); } catch {}
					try { tcpSocket.close(); } catch {}
					this.sockets.delete(server);
				}
			})();
		} catch (err) {
			console.error(`tcp connect failed to ${dst}:443 — ${err && err.message}`);
			try { server.close(1011, "upstream connect failed"); } catch {}
		}

		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws, message) {
		const entry = this.sockets.get(ws);
		if (!entry) return;
		try {
			const real = unwrapPadding(toBytes(message));
			if (real.length > 0) {
				await entry.writer.write(real);
			}
		} catch (err) {
			console.error(`tcp write failed — ${err && err.message}`);
			try { ws.close(1011, "tcp write failed"); } catch {}
		}
	}

	async webSocketClose(ws, code, reason) {
		const entry = this.sockets.get(ws);
		if (entry) {
			try { await entry.writer.close(); } catch {}
			try { entry.tcpSocket.close(); } catch {}
			this.sockets.delete(ws);
		}
	}

	async webSocketError(ws, error) {
		await this.webSocketClose(ws, 1011, "error");
	}
}
