// Cloudflare Worker: Proxy Permanente para Eaglercraft (HTTP + WebSockets)
// Nombre: server-cumbres.codenautajavier.workers.dev

let MEMORY_TARGET = "";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. Endpoint para sincronizar el túnel del día
    if (url.pathname === "/set-target") {
      const secret = url.searchParams.get("secret");
      const newTarget = url.searchParams.get("target");

      if (secret === "xaviadmin123" && newTarget) {
        const cleanHost = newTarget
          .replace(/^https?:\/\//, "")
          .replace(/^wss?:\/\//, "")
          .replace(/\/.*$/, "")
          .trim();

        MEMORY_TARGET = cleanHost;

        let kvStatus = "sin KV (memoria temporal)";
        if (env && env.TUNNEL_KV) {
          try {
            await env.TUNNEL_KV.put("TARGET_TUNNEL", cleanHost);
            kvStatus = "guardado en KV (permanente global)";
          } catch (e) {
            kvStatus = "error al guardar en KV: " + e.message;
          }
        }

        return new Response("OK: Tunel actualizado a " + cleanHost + " [" + kvStatus + "]", {
          status: 200,
          headers: { "Content-Type": "text/plain; charset=utf-8" }
        });
      }
      return new Response("No autorizado", { status: 403 });
    }

    // 2. Endpoint de diagnóstico /status
    if (url.pathname === "/status") {
      let activeTarget = MEMORY_TARGET;
      let source = "memoria";
      if (env && env.TUNNEL_KV) {
        try {
          const kvTarget = await env.TUNNEL_KV.get("TARGET_TUNNEL");
          if (kvTarget) {
            activeTarget = kvTarget;
            source = "KV global";
          }
        } catch (e) {}
      }

      return new Response(JSON.stringify({
        online: Boolean(activeTarget),
        target: activeTarget || "Ninguno (servidor apagado)",
        storage: source,
        timestamp: new Date().toISOString()
      }, null, 2), {
        status: 200,
        headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    // 3. Obtener el túnel activo
    let target = MEMORY_TARGET;
    if (env && env.TUNNEL_KV) {
      try {
        const kvTarget = await env.TUNNEL_KV.get("TARGET_TUNNEL");
        if (kvTarget) target = kvTarget;
      } catch (e) {}
    }

    if (!target) {
      return new Response("El servidor Eaglercraft de Xavi está actualmente apagado o el túnel no se ha sincronizado todavía.", {
        status: 503,
        headers: { "Content-Type": "text/plain; charset=utf-8" }
      });
    }

    // 4. Reenviar peticiones Web y WebSockets
    url.host = target;
    url.protocol = "https:";

    // Clonar headers y asegurar Host correcto para Cloudflare Argo Tunnel
    const reqHeaders = new Headers(request.headers);
    reqHeaders.set("Host", target);

    const isWs = request.headers.get("Upgrade") === "websocket";

    const fetchInit = {
      method: request.method,
      headers: reqHeaders,
      redirect: "follow"
    };

    if (!isWs && request.method !== "GET" && request.method !== "HEAD") {
      fetchInit.body = request.body;
    }

    return fetch(url.toString(), fetchInit);
  }
};
