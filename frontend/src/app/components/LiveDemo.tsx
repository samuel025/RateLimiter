"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import styles from "../page.module.css";

type DemoLog = {
  id: number;
  title: string;
  payload: unknown;
};

type UsageLogView = {
  id: string;
  apiKeyId: string;
  requestPath: string;
  method: string;
  statusCode: number;
  latencyMs: number;
  upstreamStatusCode: number | null;
  clientIp: string | null;
  userAgent: string | null;
  requestedAt: string;
};

type UsageLogPage = {
  content: UsageLogView[];
  number: number;
  size: number;
  totalElements: number;
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
  const [liveLogs, setLiveLogs] = useState<UsageLogView[]>([]);
  const [liveLogsBusy, setLiveLogsBusy] = useState(false);
  const [liveLogsError, setLiveLogsError] = useState<string | null>(null);
  const [liveLogPathFilter, setLiveLogPathFilter] = useState("/gateway");
  const [liveLogMethodFilter, setLiveLogMethodFilter] = useState("ALL");
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastRefreshedAt, setLastRefreshedAt] = useState<string | null>(null);
  const [totalLogs, setTotalLogs] = useState(0);

  const latest = useMemo(() => logs[0], [logs]);

  const fetchUsageLogs = useCallback(
    async (showLoading = true) => {
      if (showLoading) {
        setLiveLogsBusy(true);
      }
      setLiveLogsError(null);

      try {
        const params = new URLSearchParams();
        params.set("size", "10");

        if (apiKeyId) {
          params.set("apiKeyId", apiKeyId);
        }
        if (liveLogMethodFilter !== "ALL") {
          params.set("method", liveLogMethodFilter);
        }
        if (liveLogPathFilter.trim()) {
          params.set("requestPathContains", liveLogPathFilter.trim());
        }

        const response = await fetch(
          `/api/demo/usage-logs?${params.toString()}`,
          {
            cache: "no-store",
          },
        );

        const data = (await response.json()) as UsageLogPage;

        if (!response.ok || !Array.isArray(data?.content)) {
          throw new Error("Unable to load usage logs");
        }

        setLiveLogs(data.content);
        setTotalLogs(
          Number.isFinite(data.totalElements) ? data.totalElements : 0,
        );
        setLastRefreshedAt(new Date().toISOString());
      } catch {
        setLiveLogsError("Could not fetch live usage logs.");
      } finally {
        if (showLoading) {
          setLiveLogsBusy(false);
        }
      }
    },
    [apiKeyId, liveLogMethodFilter, liveLogPathFilter],
  );

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void fetchUsageLogs(true);
    }, 0);

    return () => window.clearTimeout(timer);
  }, [fetchUsageLogs]);

  useEffect(() => {
    if (!autoRefresh) {
      return;
    }

    const timer = window.setInterval(() => {
      void fetchUsageLogs(false);
    }, 3000);

    return () => window.clearInterval(timer);
  }, [autoRefresh, fetchUsageLogs]);

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
      await fetchUsageLogs(false);
    } finally {
      setBusy(false);
    }
  }

  function formatTimestamp(value: string) {
    return new Date(value).toLocaleTimeString();
  }

  function statusClass(statusCode: number) {
    if (statusCode >= 500) {
      return styles.statusError;
    }
    if (statusCode >= 400) {
      return styles.statusWarn;
    }
    return styles.statusOk;
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

      <div className={styles.liveSection}>
        <div className={styles.liveHeader}>
          <h3>Live Usage Logs</h3>
          <p>
            {totalLogs > 0
              ? `Showing latest ${liveLogs.length} of ${totalLogs}`
              : "No usage logs yet"}
          </p>
        </div>

        <div className={styles.liveToolbar}>
          <label>
            Method
            <select
              value={liveLogMethodFilter}
              onChange={(e) => setLiveLogMethodFilter(e.target.value)}
            >
              <option value="ALL">All</option>
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="PATCH">PATCH</option>
              <option value="PUT">PUT</option>
              <option value="DELETE">DELETE</option>
            </select>
          </label>
          <label className={styles.fullRow}>
            Path contains
            <input
              value={liveLogPathFilter}
              onChange={(e) => setLiveLogPathFilter(e.target.value)}
              placeholder="/gateway"
            />
          </label>
          <button
            type="button"
            onClick={() => void fetchUsageLogs(true)}
            disabled={liveLogsBusy}
          >
            Refresh now
          </button>
          <label className={styles.toggle}>
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
            Auto refresh (3s)
          </label>
        </div>

        {lastRefreshedAt ? (
          <p className={styles.liveMeta}>
            Last update: {new Date(lastRefreshedAt).toLocaleTimeString()}
          </p>
        ) : null}

        {liveLogsError ? (
          <p className={styles.liveError}>{liveLogsError}</p>
        ) : null}

        <div className={styles.liveTableWrap}>
          <table className={styles.liveTable}>
            <thead>
              <tr>
                <th>Time</th>
                <th>Method</th>
                <th>Path</th>
                <th>Status</th>
                <th>Latency</th>
              </tr>
            </thead>
            <tbody>
              {liveLogs.map((entry) => (
                <tr key={entry.id}>
                  <td>{formatTimestamp(entry.requestedAt)}</td>
                  <td>{entry.method}</td>
                  <td title={entry.requestPath}>{entry.requestPath}</td>
                  <td>
                    <span
                      className={`${styles.logStatus} ${statusClass(entry.statusCode)}`}
                    >
                      {entry.statusCode}
                    </span>
                  </td>
                  <td>{entry.latencyMs} ms</td>
                </tr>
              ))}
              {!liveLogsBusy && liveLogs.length === 0 ? (
                <tr>
                  <td colSpan={5} className={styles.emptyRow}>
                    No matching logs.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}
