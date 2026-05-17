// activity.jsx — Activity timeline screen (full per-session log)

const ACT_KIND = {
  connect:   { dot: "var(--mint)", glow: "var(--mint-glow)", label: "Connected" },
  disconnect: { dot: "var(--fg-2)", glow: "transparent", label: "Disconnected" },
  pin:       { dot: "var(--cool)", glow: "rgba(109,214,255,0.5)", label: "PIN rotated" },
  warn:      { dot: "var(--warn)", glow: "rgba(255,184,107,0.5)", label: "Warning" },
  fg:        { dot: "var(--mint-bright)", glow: "var(--mint-glow-soft)", label: "Service" },
  reject:    { dot: "var(--crit)", glow: "rgba(255,64,96,0.5)", label: "Rejected" },
};

const ACTIVITY_LOG = [
  { kind: "connect", t: "now", title: "studio-mbp · HID active", host: "studio-mbp", detail: "Reports 1000 Hz · 0 dropped" },
  { kind: "pin", t: "−2m", title: "GATT session #3 opened", host: "studio-mbp", detail: "PIN regenerated · 304817" },
  { kind: "warn", t: "−6m", title: "Replay window dropped 1 packet", host: "studio-mbp", detail: "Counter went backwards · expected, harmless" },
  { kind: "fg", t: "−18m", title: "Foreground service started", host: "—", detail: "Permanent notification posted" },
  { kind: "reject", t: "−1h 2m", title: "Untrusted host attempted connection", host: "91:b4:c0:de:7e:11", detail: "Rejected — pinned host is f3:a7:…" },
  { kind: "disconnect", t: "−1h 8m", title: "studio-mbp · HID closed", host: "studio-mbp", detail: "Host disconnected gracefully" },
  { kind: "connect", t: "−1h 12m", title: "studio-mbp · HID active", host: "studio-mbp", detail: "First connection of session" },
  { kind: "pin", t: "−1h 12m", title: "GATT session #2 opened", host: "studio-mbp", detail: "PIN regenerated · 559203" },
  { kind: "fg", t: "−2h", title: "Bluetrack started", host: "—", detail: "App resumed from background" },
];

const SESSION_STATS = {
  duration: "2h 04m",
  hosts: 1,
  events: 24,
  warnings: 2,
};

const ActivityScreen = ({ id = "activity-screen", onNav = () => {}, state = "filled", label = "Activity" }) => {
  const [filter, setFilter] = React.useState("all");
  const [dock, setDock] = React.useState("activity");
  const filtered = filter === "all" ? ACTIVITY_LOG :
    filter === "pairing" ? ACTIVITY_LOG.filter(e => ["pin", "connect", "disconnect"].includes(e.kind)) :
    filter === "feedback" ? ACTIVITY_LOG.filter(e => ["pin", "fg"].includes(e.kind)) :
    filter === "trust" ? ACTIVITY_LOG.filter(e => ["reject"].includes(e.kind)) :
    filter === "errors" ? ACTIVITY_LOG.filter(e => ["warn", "reject"].includes(e.kind)) :
    ACTIVITY_LOG;

  return (
    <Phone id={id} label={label}>
      <AppBar
        sub={`Session · ${SESSION_STATS.duration}`}
        title="Activity"
        right={<IconBtn aria-label="Export"><svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 1v8M3.5 5.5L7 9l3.5-3.5M2 12h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none"/></svg></IconBtn>}
      />

      <ScreenShell dock={<Dock active={dock} onChange={(k) => { setDock(k); onNav(k); }}/>}>
        {state === "empty" ? (
          <div style={{ paddingTop: 60 }}>
            <EmptyState
              glyph={<svg width="22" height="22" viewBox="0 0 22 22" fill="none"><path d="M2 11h3l2-5 3 10 2-7 2 4 3-2h1" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none"/></svg>}
              title="Quiet."
              sub="Connect a host to start logging events. The last 24 will appear here."
            />
          </div>
        ) : (
          <div className="bt-stagger">
            {/* Session summary strip */}
            <div style={{ padding: "0 18px 12px" }}>
              <div className="bt-glass" style={{
                borderRadius: 16, padding: "12px 14px",
                display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 8,
              }}>
                {[
                  ["Hosts", SESSION_STATS.hosts],
                  ["Events", SESSION_STATS.events],
                  ["Warnings", SESSION_STATS.warnings],
                  ["Length", SESSION_STATS.duration],
                ].map(([l, v]) => (
                  <div key={l} style={{ textAlign: "center" }}>
                    <div className="bt-mono" style={{ fontSize: 16, fontWeight: 600, color: "var(--fg-0)" }}>{v}</div>
                    <div className="bt-cap" style={{ fontSize: 9 }}>{l}</div>
                  </div>
                ))}
              </div>
            </div>

            {/* Filter pills — All / Pairing / Feedback / Trust / Errors */}
            <div style={{ padding: "0 18px 14px", display: "flex", gap: 6, flexWrap: "wrap" }}>
              {[["all", "All"], ["pairing", "Pairing"], ["feedback", "Feedback"], ["trust", "Trust"], ["errors", "Errors"]].map(([k, l]) => (
                <button key={k} onClick={() => setFilter(k)} style={{
                  height: 28, padding: "0 12px", borderRadius: 999,
                  background: filter === k ? "var(--mint)" : "transparent",
                  color: filter === k ? "#fff" : "var(--fg-1)",
                  border: filter === k ? "none" : "1px solid var(--hairline)",
                  fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.08, textTransform: "uppercase", cursor: "pointer",
                  boxShadow: filter === k ? "0 6px 16px var(--mint-glow-soft)" : "none",
                }}>{l}</button>
              ))}
            </div>

            {/* Timeline */}
            <div style={{ padding: "0 18px 14px", position: "relative" }}>
              <div style={{ position: "absolute", left: 28, top: 12, bottom: 12, width: 1, background: "var(--hairline)" }}/>
              {filtered.map((e, i) => {
                const k = ACT_KIND[e.kind];
                return (
                  <div key={i} style={{
                    display: "flex", gap: 12, padding: "10px 0", position: "relative",
                  }}>
                    <div style={{
                      width: 22, height: 22, marginTop: 2, borderRadius: "50%", flexShrink: 0,
                      background: "var(--bg-0)",
                      display: "grid", placeItems: "center",
                      border: "1px solid var(--hairline-2)",
                      position: "relative", zIndex: 1,
                    }}>
                      <span style={{
                        width: 8, height: 8, borderRadius: "50%",
                        background: k.dot, boxShadow: `0 0 8px ${k.glow}`,
                      }}/>
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
                        <span style={{ fontSize: 13, color: "var(--fg-0)", fontWeight: 500 }}>{e.title}</span>
                        <span className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)", flexShrink: 0 }}>{e.t}</span>
                      </div>
                      <div style={{ fontSize: 11, color: "var(--fg-2)", marginTop: 3, lineHeight: 1.45 }}>{e.detail}</div>
                      {e.host !== "—" && (
                        <div className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)", marginTop: 3 }}>{e.host}</div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <div style={{ padding: "0 18px 18px", textAlign: "center" }}>
              <span className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)", letterSpacing: 0.08, textTransform: "uppercase" }}>
                showing {filtered.length} of {ACTIVITY_LOG.length} · max 24 retained
              </span>
            </div>
          </div>
        )}
      </ScreenShell>
    </Phone>
  );
};

Object.assign(window, { ActivityScreen });
