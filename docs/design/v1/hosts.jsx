// hosts.jsx — bonded devices list with device class, ignore reasons, compat caveats

const HOST_CLASSES = {
  computer: { label: "Computer", icon: <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><rect x="2" y="3" width="12" height="8" rx="1.2" stroke="currentColor" strokeWidth="1.4"/><path d="M5 13h6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>, color: "var(--cool)" },
  audio: { label: "Audio", icon: <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 7v3a4 4 0 004 4M13 7v3a4 4 0 01-4 4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/><circle cx="8" cy="6" r="3.5" stroke="currentColor" strokeWidth="1.4"/></svg>, color: "var(--fg-2)" },
  pointing: { label: "Pointer", icon: <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 2l9 6-4 1.5L7 14 4 2z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/></svg>, color: "var(--fg-2)" },
  keyboard: { label: "Keyboard", icon: <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><rect x="1.5" y="4" width="13" height="8" rx="1.2" stroke="currentColor" strokeWidth="1.4"/><path d="M4 10h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>, color: "var(--fg-2)" },
  unknown: { label: "Unknown", icon: <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.4"/><path d="M6.5 6a1.5 1.5 0 113 .5c0 1-1.5 1.2-1.5 2.5M8 11.5v.1" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>, color: "var(--fg-3)" },
};

const HOSTS = [
  { id: "1", name: "studio-mbp", os: "macOS 14.5", cls: "computer", state: "active", fp: "f3:a7:91:c2:de:08", caveats: [], hidCapable: true },
  { id: "2", name: "tower-pc", os: "Windows 11", cls: "computer", state: "available", fp: "11:84:33:ee:5c:bf", caveats: ["multi-adv"], hidCapable: true },
  { id: "3", name: "iphone-13", os: "iOS 17", cls: "computer", state: "incompatible", fp: "9a:c8:11:42:fa:01",
    caveats: ["ios-hid"], hidCapable: false },
  { id: "4", name: "AirPods Pro", os: "—", cls: "audio", state: "ignored", fp: "—", caveats: [], reason: "Audio profile · not a HID host" },
  { id: "5", name: "Magic Mouse 2", os: "—", cls: "pointing", state: "ignored", fp: "—", caveats: [], reason: "Pointer device · cannot be a HID host" },
  { id: "6", name: "K380 Keyboard", os: "—", cls: "keyboard", state: "ignored", fp: "—", caveats: [], reason: "Keyboard device · cannot be a HID host" },
];

const CAVEATS = {
  "multi-adv": {
    title: "Multiple advertising not supported",
    body: "This phone's BLE chip can only run one advertisement at a time. Bluetrack will pause the discoverability beacon while the HID link is active. Pairing a second host while connected requires disconnecting first.",
  },
  "ios-hid": {
    title: "iOS doesn't accept HID over BLE",
    body: "iPhones and iPads can pair with the phone but won't open a HID Device session — Apple restricts this profile to MFi accessories. Use a Mac, PC or Android tablet as the host.",
  },
  "hid-unavail": {
    title: "HID profile not available",
    body: "Some Android builds (especially low-end and AOSP-derived ROMs) ship without the HID Device service. Bluetrack falls back to a notice; the touchpad and gamepad won't work on this phone.",
  },
  "adv-unavail": {
    title: "BLE advertiser missing",
    body: "The chipset can scan and connect but cannot advertise. Pair from the host side instead — Bluetrack will be reachable by name once you initiate the bond.",
  },
};

const HostRow = ({ host, onTap, onConnect, onForget, onShowCaveat }) => {
  const cls = HOST_CLASSES[host.cls];
  const ignored = host.state === "ignored";
  return (
    <div className="bt-glass" style={{
      borderRadius: 16, padding: 12, marginBottom: 8,
      opacity: ignored ? 0.66 : 1,
      borderLeft: host.state === "active" ? "3px solid var(--mint)" : "none",
      paddingLeft: host.state === "active" ? 9 : 12,
    }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <div style={{
          width: 38, height: 38, borderRadius: 12, flexShrink: 0,
          background: ignored ? "rgba(255,255,255,0.03)" : "rgba(255,255,255,0.05)",
          display: "grid", placeItems: "center", color: cls.color,
        }}>{cls.icon}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 2 }}>
            <span style={{ fontSize: 13, fontWeight: 500, color: ignored ? "var(--fg-2)" : "var(--fg-0)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{host.name}</span>
            {host.caveats?.length > 0 && (
              <button onClick={() => onShowCaveat(host.caveats[0])} style={{
                background: "transparent", border: "none", padding: 0, cursor: "pointer", color: "var(--warn)",
                width: 16, height: 16, display: "grid", placeItems: "center",
              }} aria-label="Compatibility info">
                <svg width="14" height="14" viewBox="0 0 14 14"><circle cx="7" cy="7" r="5.6" stroke="currentColor" strokeWidth="1.2" fill="none"/><path d="M7 5.5v3M7 4v.1" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
              </button>
            )}
          </div>
          <div style={{ display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
            <Chip kind="calm">{cls.label}</Chip>
            {host.state === "active" && <Chip kind="live"><Pulse size={5}/>HID active</Chip>}
            {host.state === "available" && <Chip kind="cool">Bonded</Chip>}
            {host.state === "incompatible" && <Chip kind="calm">Not supported</Chip>}
            {ignored && <Chip kind="calm">Ignored</Chip>}
          </div>
          {ignored && host.reason && (
            <div style={{ fontSize: 11, color: "var(--fg-3)", marginTop: 6, lineHeight: 1.4 }}>{host.reason}</div>
          )}
          {host.fp !== "—" && (
            <div className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)", marginTop: 4 }}>{host.fp}</div>
          )}
        </div>
        {host.state === "available" && (
          <button onClick={onConnect} style={{
            height: 32, padding: "0 12px", borderRadius: 999,
            background: "var(--mint)", color: "#fff", border: "none", cursor: "pointer",
            fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.06, textTransform: "uppercase",
            boxShadow: "0 6px 16px var(--mint-glow-soft)",
          }}>Connect</button>
        )}
        {host.state === "active" && (
          <button onClick={onForget} style={{
            width: 32, height: 32, borderRadius: 999, border: "1px solid var(--hairline)",
            background: "transparent", color: "var(--fg-2)", cursor: "pointer", display: "grid", placeItems: "center",
          }} aria-label="Disconnect">
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>
          </button>
        )}
      </div>
    </div>
  );
};

const HostsScreen = ({ id = "hosts-screen", state = "filled", onNav = () => {}, label = "Hosts" }) => {
  const [caveat, setCaveat] = React.useState(null);
  const [dock, setDock] = React.useState("hosts");

  const computers = HOSTS.filter(h => ["computer"].includes(h.cls));
  const accessories = HOSTS.filter(h => ["audio", "pointing", "keyboard"].includes(h.cls));

  return (
    <Phone id={id} label={label}>
      <AppBar
        sub="Bonded devices · 6"
        title="Hosts"
        right={<IconBtn aria-label="Pair new"><svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 2v10M2 7h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/></svg></IconBtn>}
      />
      <ScreenShell dock={<Dock active={dock} onChange={(k) => { setDock(k); onNav(k); }}/>}>
        {state === "empty" ? (
          <div style={{ paddingTop: 40 }}>
            <EmptyState
              glyph={<svg width="22" height="22" viewBox="0 0 22 22" fill="none"><rect x="2" y="4" width="18" height="13" rx="1.6" stroke="currentColor" strokeWidth="1.4"/><path d="M8 20h6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>}
              title="No paired computers yet"
              sub="Pair a Mac, PC or Android tablet to start. Audio devices and accessories are ignored automatically."
              action={<button style={{
                marginTop: 8, height: 42, padding: "0 22px", borderRadius: 12,
                background: "var(--mint)", color: "#fff", border: "none", cursor: "pointer",
                fontWeight: 600, boxShadow: "0 8px 22px var(--mint-glow)",
              }}>Pair a computer</button>}
            />
          </div>
        ) : (
          <div className="bt-stagger">
            <SectionLabel>Computers · 3</SectionLabel>
            <div style={{ padding: "0 18px 14px" }}>
              {computers.map(h => (
                <HostRow key={h.id} host={h}
                  onConnect={() => {}} onForget={() => {}}
                  onShowCaveat={(c) => setCaveat(c)}/>
              ))}
            </div>

            <SectionLabel action={<span style={{ fontSize: 10, fontFamily: "var(--mono)", color: "var(--fg-3)", letterSpacing: 0.06, textTransform: "uppercase" }}>auto-ignored</span>}>
              Accessories · 3
            </SectionLabel>
            <div style={{ padding: "0 18px 16px" }}>
              {accessories.map(h => (
                <HostRow key={h.id} host={h}
                  onShowCaveat={(c) => setCaveat(c)}/>
              ))}
            </div>

            {/* Compat note */}
            <div style={{ padding: "0 18px 16px" }}>
              <div className="bt-glass" style={{
                borderRadius: 16, padding: 12,
                display: "flex", alignItems: "flex-start", gap: 10,
              }}>
                <span style={{ color: "var(--fg-2)", marginTop: 2 }}>
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeWidth="1.2"/><path d="M8 6v3.5M8 4.5v.1" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
                </span>
                <div style={{ fontSize: 11, color: "var(--fg-2)", lineHeight: 1.5 }}>
                  Bluetrack only acts as a <span style={{ color: "var(--fg-1)" }}>HID Device</span>, never a host. Headphones, mice and keyboards stay in this list for transparency but are skipped during auto-connect.
                </div>
              </div>
            </div>
          </div>
        )}
      </ScreenShell>
      <InfoSheet
        open={!!caveat} onClose={() => setCaveat(null)}
        title={caveat ? CAVEATS[caveat]?.title : ""}
        body={caveat ? CAVEATS[caveat]?.body : ""}
      />
    </Phone>
  );
};

Object.assign(window, { HostsScreen });
