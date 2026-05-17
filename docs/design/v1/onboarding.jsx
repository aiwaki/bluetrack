// onboarding.jsx — Welcome (first-run only) + granular Permissions wizard
// 3 grants: BT nearby + Notifications + FG service. Pre-prompt cards
// before each system dialog with a timeline.

const PERMS_DEFAULTS = [
  {
    key: "bt", title: "Bluetooth nearby devices", status: "needed",
    why: "Required to scan, advertise, and connect to your computer over BLE + classic.",
    pre: "Android will show a system sheet listing 3 sub-permissions: Scan, Connect, Advertise.",
    icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M7 5l6 5-6 5V5l6 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  },
  {
    key: "notif", title: "Notifications", status: "needed",
    why: "Required so the foreground HID service can show its permanent ‘running’ notification.",
    pre: "Android 13+ will ask once. If denied, the service still runs but the notification is silent.",
    icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M5 14V9a5 5 0 0110 0v5l1.5 1.5h-13L5 14z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/><path d="M8 17a2 2 0 004 0" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>,
  },
  {
    key: "fgs", title: "Foreground service", status: "system",
    why: "Spawned by the OS when the HID link starts. Shows the permanent ‘Bluetrack running’ notification.",
    pre: "Not bypassable. Long-press the notification to mute its sound only.",
    icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="6" stroke="currentColor" strokeWidth="1.4"/><path d="M10 6v4l2.5 1.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>,
  },
];

const StatusDot = ({ status }) => {
  const map = {
    granted: { c: "var(--mint)", g: "var(--mint-glow)" },
    denied:  { c: "var(--crit)", g: "rgba(255,64,96,0.4)" },
    needed:  { c: "var(--warn)", g: "rgba(255,184,107,0.4)" },
    system:  { c: "var(--fg-2)", g: "transparent" },
  };
  const s = map[status];
  return <span style={{ width: 8, height: 8, borderRadius: "50%", background: s.c, boxShadow: `0 0 8px ${s.g}` }}/>;
};

const StatusLabel = ({ status }) => {
  const map = {
    granted: "Granted",
    denied: "Denied · re-request",
    needed: "Not asked",
    system: "Spawned by OS",
  };
  return <span style={{
    fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.06, textTransform: "uppercase",
    color: status === "granted" ? "var(--mint-bright)" : status === "denied" ? "var(--crit)" : status === "needed" ? "var(--warn)" : "var(--fg-2)",
  }}>{map[status]}</span>;
};

// One permission row with expand-to-see-pre-prompt
const PermRow = ({ perm, expanded, onToggle, onAct }) => {
  return (
    <div className="bt-glass" style={{ borderRadius: 16, overflow: "hidden", marginBottom: 10 }}>
      <button onClick={onToggle} style={{
        width: "100%", display: "flex", alignItems: "center", gap: 12, padding: 14,
        background: "transparent", border: "none", color: "var(--fg-0)", cursor: "pointer", textAlign: "left",
      }}>
        <div style={{
          width: 40, height: 40, borderRadius: 12, flexShrink: 0,
          background: "rgba(255,255,255,0.05)",
          display: "grid", placeItems: "center", color: "var(--fg-1)",
        }}>{perm.icon}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 2 }}>
            <StatusDot status={perm.status}/>
            <span style={{ fontSize: 14, fontWeight: 500 }}>{perm.title}</span>
          </div>
          <StatusLabel status={perm.status}/>
        </div>
        <span style={{
          width: 22, height: 22, color: "var(--fg-3)",
          transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          transition: "transform 200ms",
        }}>
          <svg viewBox="0 0 22 22"><path d="M9 6l5 5-5 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none"/></svg>
        </span>
      </button>
      {expanded && (
        <div style={{ padding: "0 14px 14px", animation: "btFadeIn 180ms ease-out" }}>
          <div style={{ fontSize: 12, color: "var(--fg-1)", lineHeight: 1.5, marginBottom: 10 }}>{perm.why}</div>
          <div style={{
            fontSize: 11, color: "var(--fg-2)", padding: 10,
            background: "rgba(255,255,255,0.03)", border: "1px dashed var(--hairline)", borderRadius: 12,
            display: "flex", alignItems: "flex-start", gap: 8, marginBottom: 12,
          }}>
            <svg width="14" height="14" viewBox="0 0 14 14" style={{ flexShrink: 0, marginTop: 1 }}>
              <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeWidth="1.2" fill="none"/>
              <path d="M7 5v3M7 9.5v.1" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/>
            </svg>
            <span><strong style={{ color: "var(--fg-1)" }}>System dialog: </strong>{perm.pre}</span>
          </div>
          {perm.status !== "system" && perm.status !== "granted" && (
            <button onClick={onAct} style={{
              width: "100%", height: 42, borderRadius: 12,
              background: perm.status === "denied" ? "transparent" : "var(--mint)",
              border: perm.status === "denied" ? "1px solid var(--hairline-2)" : "none",
              color: perm.status === "denied" ? "var(--fg-0)" : "#fff",
              fontWeight: 600, cursor: "pointer", fontSize: 13,
              boxShadow: perm.status === "denied" ? "none" : "0 6px 20px var(--mint-glow)",
            }}>{perm.status === "denied" ? "Re-request" : "Request"}</button>
          )}
          {perm.status === "system" && (
            <div style={{ fontSize: 11, color: "var(--fg-3)", textAlign: "center" }}>
              Bluetrack will trigger this automatically once the HID link starts.
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// Timeline of "we asked / system asked / you tapped"
const PromptTimeline = () => {
  const events = [
    { side: "app", text: "Bluetrack: ‘Allow nearby devices’", t: "0s" },
    { side: "sys", text: "Android shows BT permission sheet", t: "0.4s" },
    { side: "you", text: "You tap Allow", t: "1.2s" },
    { side: "app", text: "Bluetrack: ‘Permission granted’", t: "1.3s" },
  ];
  return (
    <div className="bt-glass" style={{ margin: "0 18px 12px", borderRadius: 16, padding: 14 }}>
      <div className="bt-cap" style={{ marginBottom: 10 }}>What happens next</div>
      <div style={{ position: "relative", paddingLeft: 14 }}>
        <div style={{ position: "absolute", left: 4, top: 4, bottom: 4, width: 1, background: "var(--hairline-2)" }}/>
        {events.map((e, i) => (
          <div key={i} style={{ display: "flex", alignItems: "flex-start", gap: 10, padding: "6px 0" }}>
            <div style={{
              position: "absolute", left: 0, marginTop: 5,
              width: 9, height: 9, borderRadius: "50%",
              background: e.side === "you" ? "var(--mint)" : e.side === "sys" ? "var(--cool)" : "var(--fg-2)",
              boxShadow: e.side === "you" ? "0 0 8px var(--mint-glow)" : "none",
            }}/>
            <div style={{ flex: 1, paddingLeft: 14 }}>
              <div style={{ fontSize: 12, color: "var(--fg-1)" }}>{e.text}</div>
              <div className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)" }}>
                {e.side === "you" ? "you" : e.side === "sys" ? "android" : "app"} · {e.t}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

// Permissions screen — hybrid wizard: critical BT can be granted alone,
// rest is inline with re-request actions.
const PermissionsScreen = ({ id = "perms-screen", label = "02 Permissions" }) => {
  const [perms, setPerms] = React.useState(PERMS_DEFAULTS.map((p, i) => ({
    ...p, status: i === 0 ? "needed" : i === 1 ? "denied" : "system",
  })));
  const [exp, setExp] = React.useState("bt");
  const grant = (k) => setPerms(prev => prev.map(p => p.key === k ? { ...p, status: "granted" } : p));
  const allDone = perms.every(p => p.status === "granted" || p.status === "system");

  return (
    <Phone id={id} label={label}>
      <AppBar sub="Setup · Step 2 of 2" title="Permissions"/>
      <ScreenShell dock={false}>
        <div className="bt-stagger" style={{ padding: "0 18px 24px" }}>
          <div style={{ fontSize: 13, color: "var(--fg-1)", marginBottom: 16, lineHeight: 1.5 }}>
            Bluetrack uses three Android grants. Tap any row for what the system will ask and why we need it.
          </div>
          {perms.map(p => (
            <PermRow key={p.key} perm={p}
              expanded={exp === p.key}
              onToggle={() => setExp(exp === p.key ? null : p.key)}
              onAct={() => grant(p.key)}/>
          ))}
        </div>

        <PromptTimeline/>

        <div style={{ padding: "8px 18px 24px" }}>
          <button disabled={!allDone} style={{
            width: "100%", height: 50, borderRadius: 16, fontWeight: 600, fontSize: 15,
            background: allDone ? "var(--mint)" : "rgba(255,255,255,0.05)",
            color: allDone ? "#fff" : "var(--fg-3)",
            border: allDone ? "none" : "1px solid var(--hairline)",
            cursor: allDone ? "pointer" : "not-allowed",
            boxShadow: allDone ? "0 12px 32px var(--mint-glow)" : "none",
          }}>{allDone ? "Continue to Hub →" : "Grant Bluetooth to continue"}</button>
        </div>
      </ScreenShell>
    </Phone>
  );
};

// Welcome — first-run only
const WelcomeScreen = ({ id = "welcome-screen", label = "01 Welcome" }) => {
  return (
    <Phone id={id} label={label}>
      <ScreenShell dock={false}>
        <div style={{ padding: "20px 22px 0" }}>
          <Wordmark size={13}/>
        </div>
        <div className="bt-stagger" style={{
          flex: 1, display: "flex", flexDirection: "column", justifyContent: "space-between", padding: "16px 22px 28px",
        }}>
          <div>
            <div className="bt-cap" style={{ marginBottom: 14 }}>First run · won't see this again</div>
            <div className="bt-display" style={{ fontSize: 56, lineHeight: 0.9, marginBottom: 14 }}>
              Turn this <span className="bt-neon">phone</span><br/>into a HID.
            </div>
            <div style={{ fontSize: 14, color: "var(--fg-1)", lineHeight: 1.55, maxWidth: 280 }}>
              Bluetrack makes your computer see this phone as a mouse, keyboard and gamepad — over BLE, encrypted, identity-pinned.
            </div>
          </div>

          <div className="bt-glass" style={{ borderRadius: 16, padding: 14, marginTop: 16 }}>
            <div className="bt-cap" style={{ marginBottom: 10 }}>You'll be asked for</div>
            {[
              ["Bluetooth nearby devices", "BLE scan + connect + advertise"],
              ["Notifications", "for the running-service banner"],
              ["A foreground service", "Android spawns it; not bypassable"],
            ].map(([t, s], i) => (
              <div key={i} style={{ display: "flex", gap: 12, padding: "8px 0", borderTop: i ? "1px solid var(--hairline)" : "none" }}>
                <span className="bt-mono" style={{ width: 18, color: "var(--mint-bright)", fontSize: 11 }}>0{i + 1}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 13 }}>{t}</div>
                  <div style={{ fontSize: 11, color: "var(--fg-2)" }}>{s}</div>
                </div>
              </div>
            ))}
          </div>

          <button style={{
            marginTop: 16, width: "100%", height: 52, borderRadius: 16,
            background: "var(--mint)", color: "#fff", border: "none", cursor: "pointer",
            fontWeight: 600, fontSize: 15,
            boxShadow: "0 14px 36px var(--mint-glow), 0 0 0 1px var(--mint-glow-soft) inset",
          }}>Set up · 2 steps</button>
          <div style={{ textAlign: "center", marginTop: 10, fontSize: 11, color: "var(--fg-3)", fontFamily: "var(--mono)" }}>
            BLE only · no cloud · open-source
          </div>
        </div>
      </ScreenShell>
    </Phone>
  );
};

Object.assign(window, { WelcomeScreen, PermissionsScreen });
