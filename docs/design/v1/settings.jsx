// settings.jsx — Settings screen with stacked-value rows (no truncation)
// Groups: Identity (incl. Visible-as) · Connectivity · Permissions link · Diagnostics · Appearance · About

const SettingsRow = ({ label, value, mono, kind = "text", onClick, accent, hint }) => {
  const showStacked = kind === "text" && value && (mono || (value.length > 14));
  const interactive = !!onClick;
  return (
    <button onClick={onClick} disabled={!interactive} style={{
      width: "100%", display: "block", textAlign: "left",
      background: "transparent", border: "none", padding: "13px 14px",
      color: "var(--fg-0)", cursor: interactive ? "pointer" : "default",
      opacity: 1,
    }}>
      {showStacked ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
            <span style={{ fontSize: 13, fontWeight: 500 }}>{label}</span>
            {kind === "chev" && (
              <svg width="12" height="12" viewBox="0 0 14 14" style={{ color: "var(--fg-3)" }}>
                <path d="M5 3l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
              </svg>
            )}
          </div>
          <span className={mono ? "bt-mono" : ""} style={{
            fontSize: 12, color: accent ? "var(--mint-bright)" : "var(--fg-2)",
            overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
          }}>{value}</span>
          {hint && <span style={{ fontSize: 11, color: "var(--fg-3)" }}>{hint}</span>}
        </div>
      ) : (
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 500 }}>{label}</div>
            {hint && <div style={{ fontSize: 11, color: "var(--fg-2)", marginTop: 2 }}>{hint}</div>}
          </div>
          {value && kind === "text" && (
            <span className={mono ? "bt-mono" : ""} style={{
              fontSize: 11, color: accent ? "var(--mint-bright)" : "var(--fg-2)",
              flexShrink: 0, maxWidth: 130, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
            }}>{value}</span>
          )}
          {kind === "chev" && (
            <svg width="12" height="12" viewBox="0 0 14 14" style={{ color: "var(--fg-3)", flexShrink: 0 }}>
              <path d="M5 3l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
            </svg>
          )}
          {kind === "ext" && (
            <svg width="12" height="12" viewBox="0 0 14 14" style={{ color: "var(--fg-3)", flexShrink: 0 }}>
              <path d="M5 2H2v10h10V9M9 2h3v3M12 2 7 7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
            </svg>
          )}
        </div>
      )}
    </button>
  );
};

const SettingsGroup = ({ title, children }) => (
  <div style={{ marginBottom: 18 }}>
    <SectionLabel>{title}</SectionLabel>
    <div className="bt-glass" style={{ borderRadius: 16, margin: "0 18px", overflow: "hidden" }}>
      {React.Children.map(children, (c, i) => (
        <div style={{ borderTop: i ? "1px solid var(--hairline)" : "none" }}>{c}</div>
      ))}
    </div>
  </div>
);

const SettingsScreen = ({ id = "settings-screen", onNav = () => {}, onOpenTweaks = () => {}, label = "Settings" }) => {
  const [dock, setDock] = React.useState("settings");
  return (
    <Phone id={id} label={label}>
      <AppBar
        title="Settings"
        right={<IconBtn onClick={onOpenTweaks} aria-label="Tweaks">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <circle cx="7" cy="7" r="2" stroke="currentColor" strokeWidth="1.3"/>
            <path d="M7 1.5v1.6M7 10.9v1.6M11.5 7h1.6M0.9 7h1.6M10.2 3.8l1.1-1.1M2.7 11.3l1.1-1.1M10.2 10.2l1.1 1.1M2.7 2.7l1.1 1.1" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round"/>
          </svg>
        </IconBtn>}
      />
      <ScreenShell dock={<Dock active={dock} onChange={(k) => { setDock(k); onNav(k); }}/>}>
        <div className="bt-stagger">
          <SettingsGroup title="Identity">
            <SettingsRow label="Visible as" value="Bluetrack Pro Engine" mono
              hint="Search for this name in macOS · Windows BT settings"/>
            <SettingsRow label="Trusted host" value="studio-mbp" accent kind="chev"/>
            <SettingsRow label="Fingerprint" value="f3:a7:91:c2:de:08:1b:24" mono/>
            <SettingsRow label="Show identity QR" kind="chev"/>
            <SettingsRow label="Forget host…" kind="chev"/>
          </SettingsGroup>

          <SettingsGroup title="Connectivity">
            <SettingsRow label="Foreground service" value="Running" accent/>
            <SettingsRow label="Auto-connect to bonded" value="On"/>
            <SettingsRow label="HID profile" value="Available"/>
            <SettingsRow label="BLE advertiser" value="Available"/>
            <SettingsRow label="Multi advertisement" value="Not supported"/>
          </SettingsGroup>

          <SettingsGroup title="Permissions">
            <SettingsRow label="Bluetooth nearby" value="Granted" accent/>
            <SettingsRow label="Notifications" value="Denied" kind="chev"/>
            <SettingsRow label="Manage all permissions" kind="chev" onClick={() => onNav("permissions")}/>
          </SettingsGroup>

          <SettingsGroup title="Diagnostics & Activity">
            <SettingsRow label="Open Diagnostics" kind="chev" onClick={() => onNav("diag")}/>
            <SettingsRow label="Open Activity log" kind="chev" onClick={() => onNav("activity")}/>
            <SettingsRow label="Export session log" kind="chev"/>
          </SettingsGroup>

          <SettingsGroup title="Appearance">
            <SettingsRow label="Tweaks panel" kind="chev" onClick={onOpenTweaks}/>
            <SettingsRow label="Reduce motion" value="Auto"/>
            <SettingsRow label="Aurora on low battery" value="Off"/>
          </SettingsGroup>

          <SettingsGroup title="About">
            <SettingsRow label="Version" value="0.6.2 (build 142)" mono/>
            <SettingsRow label="Commit" value="c1ff4ae4" mono/>
            <SettingsRow label="Identity storage" value="SharedPreferences · host_identity_v1"
              hint="App-private, never leaves the device"/>
            <SettingsRow label="Source code" kind="ext"/>
            <SettingsRow label="What is Bluetrack?" kind="chev"/>
          </SettingsGroup>
        </div>
      </ScreenShell>
    </Phone>
  );
};

Object.assign(window, { SettingsScreen });
