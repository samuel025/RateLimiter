import styles from "./page.module.css";
import LiveDemo from "./components/LiveDemo";

export default function Home() {
  return (
    <div className={styles.shell}>
      <main className={styles.container}>
        <section className={styles.hero}>
          <p className={styles.eyebrow}>RateLimiter Gateway</p>
          <h1>Protect your APIs.</h1>
          <p className={styles.subhead}>
            A Spring Boot gateway that validates API keys, applies per-key
            limits, proxies requests to upstreams, and logs usage. Built for
            practical testing and extension.
          </p>

          <div className={styles.heroActions}>
            <a
              href="http://localhost:8091/swagger-ui.html"
              target="_blank"
              rel="noopener noreferrer"
            >
              Open Swagger
            </a>
            <a
              href="http://localhost:8091/v3/api-docs"
              target="_blank"
              rel="noopener noreferrer"
            >
              View OpenAPI JSON
            </a>
          </div>
        </section>

        {/* <section className={styles.metrics}>
          <article>
            <p>Rate Window</p>
            <h3>1 Minute</h3>
          </article>
          <article>
            <p>Limiter Store</p>
            <h3>Redis + Lua</h3>
          </article>
          <article>
            <p>Runtime</p>
            <h3>Java 21 VT</h3>
          </article>
          <article>
            <p>Data Store</p>
            <h3>PostgreSQL</h3>
          </article>
        </section> */}

        <section className={styles.panel}>
          <h2>Request Flow</h2>
          <ol>
            <li>
              <span>1</span>
              <div>
                <h4>AuthInterceptor</h4>
                <p>
                  Reads X-API-Key, validates hash, and checks client status.
                </p>
              </div>
            </li>
            <li>
              <span>2</span>
              <div>
                <h4>RateLimitInterceptor</h4>
                <p>Runs atomic Redis script and emits X-RateLimit headers.</p>
              </div>
            </li>
            <li>
              <span>3</span>
              <div>
                <h4>GatewayController</h4>
                <p>
                  Proxies method, path, query, and headers to upstream service.
                </p>
              </div>
            </li>
            <li>
              <span>4</span>
              <div>
                <h4>UsageLogService</h4>
                <p>
                  Stores status, latency, and request metadata for reporting.
                </p>
              </div>
            </li>
          </ol>
        </section>

        <section className={styles.split}>
          <article className={styles.panel}>
            <h2>Admin Endpoints</h2>
            <p className={styles.sectionIntro}>
              Key lifecycle controls exposed by the gateway.
            </p>
            <ul className={styles.endpointList}>
              <li>
                <span className={styles.methodPost}>POST</span>
                <code>/admin/clients</code>
                <small>Create client</small>
              </li>
              <li>
                <span className={styles.methodPost}>POST</span>
                <code>/admin/keys</code>
                <small>Issue key</small>
              </li>
              <li>
                <span className={styles.methodPatch}>PATCH</span>
                <code>
                  /admin/keys/{"{"}apiKeyId{"}"}/rate-limit
                </code>
                <small>Change quota</small>
              </li>
              <li>
                <span className={styles.methodPost}>POST</span>
                <code>
                  /admin/keys/{"{"}apiKeyId{"}"}/revoke
                </code>
                <small>Revoke key</small>
              </li>
              <li>
                <span className={styles.methodGet}>GET</span>
                <code>
                  /admin/keys/{"{"}apiKeyId{"}"}
                </code>
                <small>Inspect metadata</small>
              </li>
              <li>
                <span className={styles.methodGet}>GET</span>
                <code>/admin/usage-logs</code>
                <small>Search request logs</small>
              </li>
            </ul>
          </article>

          <article className={styles.panel}>
            <h2>Quick Test Example</h2>
            <p className={styles.sectionIntro}>
              Minimal sequence to validate auth + throttling behavior.
            </p>
            <div className={styles.quickSteps}>
              <div>
                <p>Step 1</p>
                <code>POST /admin/clients</code>
              </div>
              <pre>
                <code>{`{ "name": "Acme", "contactEmail": "qa@acme.dev" }`}</code>
              </pre>

              <div>
                <p>Step 2</p>
                <code>POST /admin/keys</code>
              </div>
              <pre>
                <code>{`{ "clientId": "...", "rateLimitPerMinute": 2 }`}</code>
              </pre>

              <div>
                <p>Step 3</p>
                <code>GET /gateway/get (x3)</code>
              </div>
              <pre>
                <code>{`Expected:\n- request #1 => 200\n- request #2 => 200\n- request #3 => 429`}</code>
              </pre>
            </div>
          </article>
        </section>

        <LiveDemo />

        <section className={styles.footerCard}>
          <h3>Built to demonstrate real gateway concerns.</h3>
          <p>
            Auth boundaries, quota enforcement, upstream reliability, and
            observable usage trails in one project.
          </p>
        </section>
      </main>
    </div>
  );
}
