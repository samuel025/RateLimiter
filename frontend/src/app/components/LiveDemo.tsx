"use client";

import { useMemo, useState } from "react";
import styles from "../page.module.css";

type DemoLog = {
  id: number;
  title: string;
  payload: unknown;
};

export default function LiveDemo() {
  const [clientName, setClientName] = useState("Demo Client");
  const [clientEmail, setClientEmail] = useState("demo@example.com");
  const [clientId, setClientId] = useState("");
  const [apiKeyId, setApiKeyId] = useState("");
  const [rateLimit, setRateLimit] = useState(2);
  const [apiKey, setApiKey] = useState("");
  const [gatewayPath, setGatewayPath] = useState("/get");
  const [busy, setBusy] = useState(false);
  const [logs, setLogs] = useState<DemoLog[]>([]);

  const latest = useMemo(() => logs[0], [logs]);

  function pushLog(title: string, payload: unknown) {
    setLogs((prev) =>
      [{ id: Date.now(), title, payload }, ...prev].slice(0, 8),
    );
  }

  async function createClient() {
    setBusy(true);
    try {
      const response = await fetch("/api/demo/create-client", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: clientName, contactEmail: clientEmail }),
      });

      const data = await response.json();
      if (response.ok && data?.clientId) {
        setClientId(String(data.clientId));
      }
      pushLog("Create Client", { status: response.status, data });
    } finally {
      setBusy(false);
    }
  }

  async function createKey() {
    if (!clientId) {
      pushLog("Create Key", { error: "Create a client first" });
      return;
    }

    setBusy(true);
    try {
      const response = await fetch("/api/demo/create-key", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ clientId, rateLimitPerMinute: rateLimit }),
      });

      const data = await response.json();
      if (response.ok && data?.plaintextKey) {
        setApiKey(String(data.plaintextKey));
      }
      if (response.ok && data?.apiKeyId) {
        setApiKeyId(String(data.apiKeyId));
      }
      pushLog("Create Key", { status: response.status, data });
    } finally {
      setBusy(false);
    }
  }

  async function updateLimit() {
    if (!apiKeyId) {
      pushLog("Update Limit", { error: "Create a key first" });
      return;
    }

    setBusy(true);
    try {
      const response = await fetch("/api/demo/update-limit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKeyId, rateLimit }),
      });

      const data = await response.json();
      pushLog("Update Limit", { status: response.status, data });
    } finally {
      setBusy(false);
    }
  }

  async function revokeKey() {
    if (!apiKeyId) {
      pushLog("Revoke Key", { error: "Create a key first" });
      return;
    }

    setBusy(true);
    try {
      const response = await fetch("/api/demo/revoke-key", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKeyId }),
      });

      const data = await response.json();
      pushLog("Revoke Key", { status: response.status, data });
    } finally {
      setBusy(false);
    }
  }

  async function hitGateway(times: number) {
    if (!apiKey) {
      pushLog("Gateway Request", { error: "Create a key first" });
      return;
    }

    setBusy(true);
    try {
      for (let i = 1; i <= times; i += 1) {
        const response = await fetch("/api/demo/hit-gateway", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ path: gatewayPath, apiKey, method: "GET" }),
        });
        const data = await response.json();
        pushLog(`Gateway Request #${i}`, data);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.panel}>
      <h2>Live Demo Mode</h2>

      <div className={styles.demoGrid}>
        <label>
          Client Name
          <input
            value={clientName}
            onChange={(e) => setClientName(e.target.value)}
          />
        </label>
        <label>
          Client Email
          <input
            value={clientEmail}
            onChange={(e) => setClientEmail(e.target.value)}
          />
        </label>
        <label>
          Client ID
          <input
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
            placeholder="auto-filled"
          />
        </label>
        <label>
          API Key ID
          <input
            value={apiKeyId}
            onChange={(e) => setApiKeyId(e.target.value)}
            placeholder="auto-filled"
          />
        </label>
        <label>
          Rate Limit / min
          <input
            type="number"
            min={1}
            value={rateLimit}
            onChange={(e) => setRateLimit(Number(e.target.value))}
          />
        </label>
        <label className={styles.fullRow}>
          API Key
          <input
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="auto-filled after key creation"
          />
        </label>
        <label className={styles.fullRow}>
          Gateway Path
          <input
            value={gatewayPath}
            onChange={(e) => setGatewayPath(e.target.value)}
            placeholder="/get"
          />
        </label>
      </div>

      <div className={styles.demoActions}>
        <button type="button" onClick={createClient} disabled={busy}>
          1) Create Client
        </button>
        <button type="button" onClick={createKey} disabled={busy}>
          2) Create Key
        </button>
        <button type="button" onClick={updateLimit} disabled={busy}>
          3) Update Limit
        </button>
        <button type="button" onClick={() => hitGateway(1)} disabled={busy}>
          4) Hit Gateway Once
        </button>
        <button type="button" onClick={() => hitGateway(3)} disabled={busy}>
          5) Burst x3
        </button>
        <button
          type="button"
          className={styles.dangerButton}
          onClick={revokeKey}
          disabled={busy}
        >
          6) Revoke Key
        </button>
      </div>

      <div className={styles.demoOutput}>
        <h3>Latest Response</h3>
        <pre>
          {JSON.stringify(
            latest?.payload ?? { message: "No requests yet" },
            null,
            2,
          )}
        </pre>
      </div>
    </section>
  );
}
