// diagnostics.jsx — live rates, replay-window, rejection breakdown, sparklines

const makeWave = (n, base, amp, phase = 0) =>
  Array.from({ length: n }, (_, i) => Math.max(0, base + Math.sin(i * 0.5 + phase) * amp + Math.random() * amp * 0.6));

const REJECTIONS = [
  { key: "size",    label: "Wrong frame size",      count: 0, color: "var(--fg-2)", note: "not 28 bytes" },
  { key: "gcm",     label: "GCM tag failure",       count: 0, color: "var(--crit)", note: "wrong PIN, key or tampered" },
  { key: "replay",  label: "Replay window drop",    count: 1, color: "var(--cool)", note: "counter outside window" },
  { key: "hslen",   label: "Wrong-length handshake", count: 0, color: "var(--fg-2)", note: "malformed handshake frame" },
  { key: "sig",     label: "Bad Ed25519 signature", count: 0, color: "var(--crit)", note: "host identity mismatch" },
  { key: "untrust", label: "Untrusted host",        count: 1, color: "var(--warn)", note: "TOFU pin mismatch" },
  { key: "x25519",  label: "X25519 derivation",     count: 0, color: "var(--crit)", note: "malformed peer pubkey" },
];

const DiagnosticsScreen = ({ id = "diag-screen", state = "filled", onNav = () => {}, label = "Diagnostics" }) => {
  const [dock, setDock] = React.useState("diag");
  const hidWave = React.useMemo(() => makeWave(60, 980, 30), []);
  const fbWave = React.useMemo(() => makeWave(60, 18, 6, 1.2), []);
  const totalRej = REJECTIONS.reduce((s, r) => s + r.count, 0);

  return (
    <Phone id={id} label={label}>
      <AppBar
        sub="Live · last 60s"
        title="Diagnostics"
        right={<Chip kind="live"><Pulse size={5}/>recording</Chip>}
      />
      <ScreenShell dock={<Dock active={dock} onChange={(k) => { setDock(k); onNav(k); }}/>}>
        {state === "empty" ? (
          <div style={{ paddingTop: 60 }}>
            <EmptyState
              glyph={<svg width="22" height="22" viewBox="0 0 22 22" fill="none"><circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.4"/><path d="M11 7v4l2.6 1.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>}
              title="Counters appear once a host connects."
              sub="Bluetrack only meters traffic during an active HID session."
            />
          </div>
        ) : (
          <div className="bt-stagger" style={{ padding: "0 18px 18px" }}>
            {/* Live rate hero — HID + feedback */}
            <div className="bt-glass-strong" style={{ borderRadius: 16, padding: 16, marginBottom: 12 }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
                <div>
                  <div className="bt-cap" style={{ marginBottom: 4 }}>HID send</div>
                  <div className="bt-display" style={{ fontSize: 30, color: "var(--mint-bright)", textShadow: "0 0 12px var(--mint-glow-soft)" }}>
                    1003<span style={{ fontSize: 14, color: "var(--fg-2)", marginLeft: 4, fontWeight: 400 }}>/s</span>
                  </div>
                  <div style={{ marginTop: 8 }}><Sparkline data={hidWave} color="var(--mint-bright)" height={28} fill/></div>
                </div>
                <div>
                  <div className="bt-cap" style={{ marginBottom: 4 }}>Feedback</div>
                  <div className="bt-display" style={{ fontSize: 30, color: "var(--cool)", textShadow: "0 0 12px rgba(109,214,255,0.5)" }}>
                    19<span style={{ fontSize: 14, color: "var(--fg-2)", marginLeft: 4, fontWeight: 400 }}>/s</span>
                  </div>
                  <div style={{ marginTop: 8 }}><Sparkline data={fbWave} color="var(--cool)" height={28} fill/></div>
                </div>
              </div>
              <div style={{
                marginTop: 12, paddingTop: 12, borderTop: "1px solid var(--hairline)",
                display: "flex", justifyContent: "space-between",
                fontFamily: "var(--mono)", fontSize: 11, color: "var(--fg-2)",
              }}>
                <span>HID total · 7,238,401</span>
                <span>fb total · 12,802</span>
              </div>
            </div>

            {/* Replay window — 64 entries [last_counter-63 .. last_counter] */}
            <SectionLabel>Replay window</SectionLabel>
            <div className="bt-glass" style={{ borderRadius: 16, padding: 14, marginBottom: 12 }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 10, marginBottom: 12 }}>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Last</div>
                  <div className="bt-mono" style={{ fontSize: 14, fontWeight: 600, color: "var(--fg-0)" }}>0x9af3</div>
                </div>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Window</div>
                  <div className="bt-mono" style={{ fontSize: 14, fontWeight: 600, color: "var(--fg-0)" }}>64</div>
                </div>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Drops</div>
                  <div className="bt-mono" style={{ fontSize: 14, fontWeight: 600, color: "var(--warn)" }}>1</div>
                </div>
              </div>
              {/* 64-bucket viz: [n-63 .. n] */}
              <div style={{
                position: "relative", height: 18, borderRadius: 8,
                background: "rgba(255,255,255,0.04)", overflow: "hidden",
                border: "1px solid var(--hairline)",
              }}>
                {Array.from({ length: 64 }).map((_, i) => {
                  const isHead = i === 63;
                  const isDrop = i === 47;
                  return (
                    <span key={i} style={{
                      position: "absolute", left: `${(i / 63) * 100}%`, top: 3, bottom: 3,
                      width: 2, borderRadius: 1, transform: "translateX(-50%)",
                      background: isHead ? "var(--mint-bright)"
                                : isDrop ? "var(--warn)"
                                : i > 56 ? "var(--cool)"
                                : "rgba(255,255,255,0.16)",
                      boxShadow: isHead ? "0 0 8px var(--mint-glow)" : "none",
                    }}/>
                  );
                })}
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: 9, fontFamily: "var(--mono)", color: "var(--fg-3)", marginTop: 6, letterSpacing: 0.08 }}>
                <span>n − 63</span>
                <span>head ↑</span>
                <span>n</span>
              </div>
            </div>

            {/* PIN lifecycle — counters only, no historic values */}
            <SectionLabel>PIN lifecycle</SectionLabel>
            <div className="bt-glass" style={{ borderRadius: 16, padding: "14px 16px", marginBottom: 12 }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 12 }}>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Rolls</div>
                  <div className="bt-display" style={{ fontSize: 22, marginTop: 2 }}>3</div>
                </div>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Age</div>
                  <div className="bt-mono" style={{ fontSize: 16, fontWeight: 600, marginTop: 4 }}>18:42</div>
                </div>
                <div>
                  <div className="bt-cap" style={{ fontSize: 9 }}>Current</div>
                  <div className="bt-mono" style={{ fontSize: 18, fontWeight: 600, color: "var(--mint-bright)", marginTop: 2, letterSpacing: 2 }}>••••••</div>
                </div>
              </div>
              <div style={{ fontSize: 10, color: "var(--fg-3)", marginTop: 8, fontFamily: "var(--mono)" }}>
                PIN itself only shown on Hub.
              </div>
            </div>

            {/* Rejection breakdown — 7 categories */}
            <SectionLabel action={<span className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)", letterSpacing: 0.06, textTransform: "uppercase" }}>session · {totalRej} rej</span>}>
              Feedback rejections
            </SectionLabel>
            <div className="bt-glass" style={{ borderRadius: 16, padding: 14 }}>
              {REJECTIONS.map((r, i) => (
                <div key={r.key} style={{
                  display: "flex", alignItems: "center", gap: 10,
                  padding: "8px 0", borderTop: i ? "1px solid var(--hairline)" : "none",
                }}>
                  <span style={{ width: 8, height: 8, borderRadius: 2, background: r.color, opacity: r.count ? 1 : 0.35 }}/>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12, color: r.count ? "var(--fg-0)" : "var(--fg-2)" }}>{r.label}</div>
                    <div className="bt-mono" style={{ fontSize: 9, color: "var(--fg-3)", letterSpacing: 0.04 }}>{r.note}</div>
                  </div>
                  <span className="bt-mono" style={{
                    fontSize: 14, fontWeight: 600,
                    color: r.count ? r.color : "var(--fg-3)",
                  }}>{r.count}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </ScreenShell>
    </Phone>
  );
};

Object.assign(window, { DiagnosticsScreen });
