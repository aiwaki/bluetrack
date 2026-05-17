// shared.jsx — atoms shared across Bluetrack screens

const Pulse = ({ color = "var(--mint)", size = 10 }) => (
  <span style={{
    display: "inline-block", width: size, height: size, borderRadius: "50%",
    background: color,
    boxShadow: `0 0 0 ${size * 0.5}px ${color === "var(--mint)" ? "var(--mint-glow)" : "rgba(255,255,255,0.08)"}`,
    animation: "btPulse 2s ease-in-out infinite",
  }}/>
);

const PhoneStatus = ({ time = "9:41", dark }) => {
  const c = dark ? "rgba(255,255,255,0.92)" : "rgba(15,18,16,0.92)";
  return (
    <div style={{
      height: 36, display: "flex", alignItems: "center", justifyContent: "space-between",
      padding: "0 22px", fontFamily: "var(--mono)", fontSize: 13, color: c, letterSpacing: 0.4, position: "relative", zIndex: 2,
    }}>
      <span>{time}</span>
      <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <svg width="15" height="11" viewBox="0 0 15 11" fill="none"><path d="M1 8h2v2H1zM5 5h2v5H5zM9 2h2v8H9zM13 0h1v10h-1z" fill={c}/></svg>
        <svg width="15" height="11" viewBox="0 0 15 11" fill="none"><path d="M7.5 2.5c2.2 0 4.2.8 5.7 2.2l-1 1A6.5 6.5 0 0 0 2.8 5.7l-1-1A8.5 8.5 0 0 1 7.5 2.5zm0 3c1.4 0 2.7.5 3.7 1.5l-1 1a4 4 0 0 0-5.4 0l-1-1A5.5 5.5 0 0 1 7.5 5.5zm0 3a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z" fill={c}/></svg>
        <svg width="22" height="11" viewBox="0 0 22 11" fill="none">
          <rect x="0.5" y="0.5" width="18" height="10" rx="2.5" stroke={c} opacity="0.6"/>
          <rect x="2" y="2" width="13" height="7" rx="1" fill={c}/>
          <rect x="20" y="3.5" width="1.5" height="4" rx="0.75" fill={c} opacity="0.6"/>
        </svg>
      </div>
    </div>
  );
};

const NavPill = ({ dark }) => (
  <div style={{ height: 26, display: "flex", alignItems: "center", justifyContent: "center", position: "relative", zIndex: 2 }}>
    <div style={{ width: 110, height: 4, borderRadius: 2, background: dark ? "rgba(255,255,255,0.55)" : "rgba(15,18,16,0.55)" }}/>
  </div>
);

const Phone = ({ children, dark = true, theme = "dark", glass = "on", motion = "full", id, label }) => (
  <div data-screen-label={label} data-theme={theme} data-glass={glass} data-motion={motion} className="bt" id={id}
    style={{
      width: 360, height: 760, borderRadius: 44, overflow: "hidden", position: "relative",
      background: "var(--bg-0)",
      boxShadow: dark
        ? "0 0 0 1px rgba(255,255,255,0.05) inset, 0 30px 80px rgba(0,0,0,0.55), 0 0 0 6px #1a1c1d, 0 0 0 7px #2a2c2d"
        : "0 0 0 1px rgba(0,0,0,0.06) inset, 0 30px 80px rgba(0,0,0,0.18), 0 0 0 6px #d8d8d4, 0 0 0 7px #c0c0bc",
    }}>
    <div className="bt-aurora"/>
    <div style={{ position: "relative", zIndex: 1, height: "100%", display: "flex", flexDirection: "column" }}>
      <PhoneStatus dark={dark}/>
      <div style={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden", position: "relative" }}>
        {children}
      </div>
      <NavPill dark={dark}/>
    </div>
  </div>
);

// Wordmark — used in every header
const Wordmark = ({ size = 12 }) => (
  <span className="bt-wordmark" style={{ fontSize: size }}>
    <span className="wm-bracket">[</span>
    <span className="wm-name">Blue<span className="wm-cut">·</span>track</span>
    <span className="wm-bracket">]</span>
  </span>
);

// AppBar — unified across every utility screen.
// Layout: wordmark micro-line top, big title below, optional right slot.
const AppBar = ({ right, title, back, onBack }) => (
  <div style={{
    display: "flex", alignItems: "flex-end", gap: 12,
    padding: "8px 18px 14px",
  }}>
    {back && (
      <button onClick={onBack} aria-label="Back" style={{
        width: 36, height: 36, borderRadius: 12,
        border: "1px solid var(--hairline)", background: "transparent",
        color: "var(--fg-0)", cursor: "pointer", display: "grid", placeItems: "center",
        flexShrink: 0, marginBottom: 4,
      }}>
        <svg width="14" height="14" viewBox="0 0 14 14"><path d="M9 3l-4 4 4 4" stroke="currentColor" strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round"/></svg>
      </button>
    )}
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ marginBottom: 4, opacity: 0.85 }}><Wordmark size={11}/></div>
      <div style={{
        fontFamily: "var(--display)", fontWeight: 700, fontSize: 26,
        letterSpacing: "-0.025em", lineHeight: 1,
        whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
      }}>{title}</div>
    </div>
    {right && <div style={{ flexShrink: 0, marginBottom: 4 }}>{right}</div>}
  </div>
);

const IconBtn = ({ children, onClick, accent, "aria-label": al }) => (
  <button onClick={onClick} aria-label={al} style={{
    width: 36, height: 36, borderRadius: 12,
    border: "1px solid var(--hairline)",
    background: accent ? "var(--mint)" : "transparent",
    color: accent ? "#fff" : "var(--fg-0)",
    cursor: "pointer", display: "grid", placeItems: "center",
  }}>{children}</button>
);

const Metric = ({ label, value, sub, accent }) => (
  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
    <div className="bt-cap">{label}</div>
    <div className="bt-display" style={{ fontSize: 34, color: accent ? "var(--mint)" : "var(--fg-0)" }}>{value}</div>
    {sub && <div className="bt-mono" style={{ fontSize: 11, color: "var(--fg-2)" }}>{sub}</div>}
  </div>
);

const Chip = ({ children, kind = "default" }) => {
  const map = {
    default: { bg: "rgba(255,255,255,0.06)", fg: "var(--fg-1)", bd: "var(--hairline)" },
    live:    { bg: "var(--mint-glow-soft)", fg: "var(--mint-bright)", bd: "var(--mint-glow-soft)" },
    cool:    { bg: "rgba(109,214,255,0.14)", fg: "var(--cool)", bd: "transparent" },
    warn:    { bg: "rgba(255,184,107,0.14)", fg: "var(--warn)", bd: "transparent" },
    calm:    { bg: "rgba(255,255,255,0.05)", fg: "var(--calm)", bd: "var(--hairline)" },
  };
  const s = map[kind];
  return (
    <span style={{
      display: "inline-flex", alignItems: "center", gap: 6,
      height: 24, padding: "0 10px", borderRadius: 999,
      background: s.bg, color: s.fg, border: `1px solid ${s.bd}`,
      fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.08, textTransform: "uppercase", whiteSpace: "nowrap",
    }}>{children}</span>
  );
};

const SectionLabel = ({ children, action }) => (
  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 18px", marginBottom: 8 }}>
    <div className="bt-cap">{children}</div>
    {action}
  </div>
);

// Sparkline — micro chart for live rates
const Sparkline = ({ data, color = "var(--mint)", height = 30, fill }) => {
  const max = Math.max(1, ...data);
  const w = 100, h = 100;
  const pts = data.map((v, i) => `${(i / Math.max(1, data.length - 1)) * w},${h - (v / max) * h * 0.9 - h * 0.05}`).join(" ");
  return (
    <svg width="100%" height={height} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" style={{ display: "block" }}>
      {fill && <polygon points={`0,${h} ${pts} ${w},${h}`} fill={color} opacity="0.15"/>}
      <polyline points={pts} fill="none" stroke={color} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" style={{ filter: `drop-shadow(0 0 3px ${color})` }}/>
    </svg>
  );
};

// Empty state
const EmptyState = ({ glyph, title, sub, action }) => (
  <div className="bt-glass" style={{
    margin: "0 18px", borderRadius: 16, padding: "28px 20px",
    display: "flex", flexDirection: "column", alignItems: "center", gap: 10, textAlign: "center",
  }}>
    <div style={{
      width: 48, height: 48, borderRadius: 16,
      background: "rgba(255,255,255,0.05)",
      display: "grid", placeItems: "center",
      color: "var(--fg-2)",
    }}>{glyph}</div>
    <div style={{ fontFamily: "var(--display)", fontWeight: 700, fontSize: 18 }}>{title}</div>
    {sub && <div style={{ fontSize: 12, color: "var(--fg-2)", maxWidth: 240 }}>{sub}</div>}
    {action}
  </div>
);

// ScreenShell — scrollable body + fixed glass dock
const ScreenShell = ({ children, dock }) => (
  <div style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
    <div className="bt-noscrollbar" style={{
      flex: 1, minHeight: 0, overflowY: "auto", overflowX: "hidden",
      paddingBottom: dock === false ? 16 : 86,
    }}>{children}</div>
    {dock !== false && (
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: "10px 18px", pointerEvents: "none" }}>
        <div style={{ pointerEvents: "auto" }}>{dock}</div>
      </div>
    )}
  </div>
);

// 5-destination Dock
const Dock = ({ active, onChange = () => {} }) => {
  const items = [
    { key: "hub", icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="3" fill="currentColor"/><circle cx="10" cy="10" r="7.2" stroke="currentColor" strokeWidth="1.5" strokeDasharray="2 3"/></svg> },
    { key: "hosts", icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><rect x="2.5" y="3.5" width="15" height="11" rx="1.6" stroke="currentColor" strokeWidth="1.5"/><path d="M7 17.5h6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg> },
    { key: "activity", icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M2 10h3l2-5 3 10 2-7 2 4 3-2h1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/></svg> },
    { key: "diag", icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="6" stroke="currentColor" strokeWidth="1.5"/><path d="M10 6v4l2.5 1.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg> },
    { key: "settings", icon: <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.4" stroke="currentColor" strokeWidth="1.5"/><path d="M10 1.8v1.9M10 16.3v1.9M18.2 10h-1.9M3.7 10H1.8M15.7 4.3l-1.4 1.4M5.7 14.3 4.3 15.7M15.7 15.7l-1.4-1.4M5.7 5.7 4.3 4.3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg> },
  ];
  return (
    <div className="bt-glass-strong" style={{
      padding: 5, borderRadius: 999, display: "flex", gap: 2,
      boxShadow: "0 12px 30px rgba(0,0,0,0.4), 0 1px 0 rgba(255,255,255,0.08) inset",
    }}>
      {items.map(it => {
        const a = active === it.key;
        return (
          <button key={it.key} onClick={() => onChange(it.key)} style={{
            flex: 1, height: 44, borderRadius: 999,
            display: "flex", alignItems: "center", justifyContent: "center",
            background: a ? "var(--mint)" : "transparent",
            color: a ? "#fff" : "var(--fg-1)",
            border: "none", cursor: "pointer",
            boxShadow: a ? "0 0 22px var(--mint-glow), 0 0 44px var(--mint-glow-soft)" : "none",
            transition: "all 220ms cubic-bezier(0.34, 1.4, 0.64, 1)",
            transform: a ? "scale(1)" : "scale(0.96)",
          }}>{it.icon}</button>
        );
      })}
    </div>
  );
};

// Info popover/sheet — for compat caveats (calm tone)
const InfoSheet = ({ open, onClose, title, body }) => {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: "absolute", inset: 0, background: "rgba(0,0,0,0.42)",
      display: "flex", alignItems: "flex-end", zIndex: 50, animation: "btFadeIn 200ms ease-out",
    }}>
      <div onClick={(e) => e.stopPropagation()} className="bt-glass-strong" style={{
        margin: 12, padding: "18px 18px 22px", borderRadius: 22, width: "calc(100% - 24px)",
        animation: "btFadeUp 280ms cubic-bezier(0.22,1,0.36,1)",
      }}>
        <div style={{ width: 36, height: 4, borderRadius: 2, background: "var(--fg-3)", margin: "0 auto 14px" }}/>
        <div style={{ fontFamily: "var(--display)", fontSize: 20, fontWeight: 700, marginBottom: 8 }}>{title}</div>
        <div style={{ fontSize: 13, color: "var(--fg-1)", lineHeight: 1.5 }}>{body}</div>
      </div>
    </div>
  );
};

Object.assign(window, {
  Pulse, Phone, AppBar, IconBtn, Metric, Chip, SectionLabel, PhoneStatus, NavPill,
  Sparkline, EmptyState, ScreenShell, Dock, InfoSheet, Wordmark,
});
