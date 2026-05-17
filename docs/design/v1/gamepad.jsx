// gamepad.jsx — full landscape gamepad surface
// Renders NATIVELY in 760×360 (landscape). The HTML canvas wraps it in
// a landscape-oriented device shell. No internal rotation.

// Landscape phone frame (rotated chrome). Use INSTEAD of <Phone> for gamepad.
const LandscapePhone = ({ children, label = "Gamepad" }) => (
  <div data-screen-label={label} data-theme="dark" data-glass="on" data-motion="full" className="bt"
    style={{
      width: 760, height: 360, borderRadius: 44, overflow: "hidden", position: "relative",
      background: "var(--bg-0)",
      boxShadow: "0 0 0 1px rgba(255,255,255,0.05) inset, 0 30px 80px rgba(0,0,0,0.55), 0 0 0 6px #1a1c1d, 0 0 0 7px #2a2c2d",
    }}>
    <div className="bt-aurora"/>
    <div className="bt-lattice"/>
    {/* Right-edge status (notch + system icons rotated 90°). Subtle, low priority. */}
    <div style={{
      position: "absolute", right: 0, top: 0, bottom: 0, width: 36,
      display: "flex", alignItems: "center", justifyContent: "center",
      writingMode: "vertical-rl", transform: "rotate(180deg)",
      fontFamily: "var(--mono)", fontSize: 11, color: "rgba(255,255,255,0.6)",
      letterSpacing: 0.4, zIndex: 2, pointerEvents: "none",
    }}>9:41</div>
    {/* Left edge gesture pill */}
    <div style={{
      position: "absolute", left: 8, top: "50%", transform: "translateY(-50%)",
      width: 4, height: 110, borderRadius: 2, background: "rgba(255,255,255,0.55)",
      zIndex: 2,
    }}/>
    <div style={{ position: "relative", zIndex: 1, width: "100%", height: "100%" }}>
      {children}
    </div>
  </div>
);

const GamepadScreen = ({ onExit }) => {
  const [seq, setSeq] = React.useState(48217);
  const [pulse, setPulse] = React.useState(false);
  const [pressed, setPressed] = React.useState({});
  const [lStick, setLStick] = React.useState({ x: 0.18, y: -0.32 });
  const [rStick, setRStick] = React.useState({ x: 0, y: 0 });
  const [hat, setHat] = React.useState(8);
  const [diagOpen, setDiagOpen] = React.useState(false);

  React.useEffect(() => {
    const id = setInterval(() => {
      setSeq(s => s + 1);
      setPulse(true);
      setTimeout(() => setPulse(false), 80);
    }, 130);
    return () => clearInterval(id);
  }, []);

  const press = (k) => setPressed(p => ({ ...p, [k]: true }));
  const release = (k) => setPressed(p => ({ ...p, [k]: false }));

  return (
    <GamepadLandscape
      seq={seq} pulse={pulse} pressed={pressed} press={press} release={release}
      lStick={lStick} setLStick={setLStick} rStick={rStick} setRStick={setRStick}
      hat={hat} setHat={setHat}
      diagOpen={diagOpen} setDiagOpen={setDiagOpen}
      onExit={onExit}
    />
  );
};

const GamepadLandscape = ({ seq, pulse, pressed, press, release, lStick, setLStick, rStick, setRStick, hat, setHat, diagOpen, setDiagOpen, onExit }) => (
  <div style={{
    position: "relative", width: "100%", height: "100%",
    padding: "14px 28px", color: "var(--fg-0)",
    display: "flex", flexDirection: "column",
  }}>
    {/* TOP RAIL */}
    <div style={{
      display: "flex", alignItems: "center", justifyContent: "space-between",
      gap: 10, marginBottom: 8, position: "relative", zIndex: 2,
    }}>
      <button onClick={onExit} className="bt-glass" style={{
        height: 28, padding: "0 12px", borderRadius: 999, border: "1px solid var(--glass-border)",
        color: "var(--fg-1)", fontFamily: "var(--mono)", fontSize: 10,
        letterSpacing: 0.16, textTransform: "uppercase", cursor: "pointer",
        display: "inline-flex", alignItems: "center", gap: 6,
      }}>
        <svg width="10" height="10" viewBox="0 0 10 10"><path d="M7 1L3 5l4 4" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round"/></svg>
        Exit
      </button>

      {/* Connection lozenge */}
      <div className="bt-glass" style={{
        height: 28, padding: "0 12px", borderRadius: 999,
        display: "inline-flex", alignItems: "center", gap: 8,
        fontFamily: "var(--mono)", fontSize: 11, letterSpacing: 0.06,
      }}>
        <span style={{
          width: 6, height: 6, borderRadius: "50%",
          background: pulse ? "var(--mint-bright)" : "var(--mint)",
          boxShadow: `0 0 ${pulse ? 12 : 6}px var(--mint-glow)`,
          transition: "all 80ms",
        }}/>
        <span style={{ color: "var(--fg-0)" }}>MacBook Pro</span>
        <span style={{ color: "var(--fg-2)" }}>·</span>
        <span style={{ color: "var(--mint)" }}>14 ms</span>
      </div>

      {/* Wake-train chip with reason */}
      <WakeTrainChip/>

      {/* Diag side handle */}
      <button onClick={() => setDiagOpen(o => !o)} className="bt-glass" style={{
        width: 28, height: 28, borderRadius: 12,
        border: "1px solid var(--glass-border)", color: "var(--fg-1)",
        cursor: "pointer", display: "grid", placeItems: "center",
      }}>
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <path d="M2 4h10M2 7h10M2 10h7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
        </svg>
      </button>
    </div>

    {/* MAIN ROW — sticks + dpad/face */}
    <div style={{
      flex: 1, display: "grid", gridTemplateColumns: "1fr 1fr 1fr 1fr", gap: 18, alignItems: "center",
      position: "relative", zIndex: 1,
    }}>
      {/* LEFT TRIGGERS */}
      <div style={{ display: "flex", flexDirection: "column", gap: 12, alignItems: "stretch" }}>
        <Trigger label="LB" pressed={!!pressed.LB} onPress={() => press("LB")} onRelease={() => release("LB")}/>
        <Trigger label="LT" pressed={!!pressed.LT} onPress={() => press("LT")} onRelease={() => release("LT")} digital/>
        <Stick label="L" value={lStick} onChange={setLStick}/>
      </div>

      {/* DPAD column */}
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 16 }}>
        <DPad value={hat} onChange={setHat}/>
        <CenterRail/>
      </div>

      {/* FACE BUTTONS column */}
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 16 }}>
        <FaceButtons pressed={pressed} press={press} release={release}/>
        <FrameCounter seq={seq} pulse={pulse}/>
      </div>

      {/* RIGHT TRIGGERS */}
      <div style={{ display: "flex", flexDirection: "column", gap: 12, alignItems: "stretch" }}>
        <Trigger label="RB" pressed={!!pressed.RB} onPress={() => press("RB")} onRelease={() => release("RB")}/>
        <Trigger label="RT" pressed={!!pressed.RT} onPress={() => press("RT")} onRelease={() => release("RT")} digital/>
        <Stick label="R" value={rStick} onChange={setRStick}/>
      </div>
    </div>

    {/* Fold-out diag panel */}
    {diagOpen && <DiagFold lStick={lStick} rStick={rStick} hat={hat} pressed={pressed} seq={seq}/>}
  </div>
);

// ────────────────────────────────────────────────────────
const Stick = ({ label, value, onChange }) => {
  const ref = React.useRef(null);
  const [drag, setDrag] = React.useState(false);

  const handle = (e) => {
    if (!drag || !ref.current) return;
    const r = ref.current.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    let dx = (e.clientX - cx) / (r.width / 2);
    let dy = (e.clientY - cy) / (r.height / 2);
    const m = Math.hypot(dx, dy);
    if (m > 1) { dx /= m; dy /= m; }
    onChange({ x: dx, y: dy });
  };

  React.useEffect(() => {
    if (!drag) return;
    const up = () => { setDrag(false); onChange({ x: 0, y: 0 }); };
    window.addEventListener("mousemove", handle);
    window.addEventListener("mouseup", up);
    return () => { window.removeEventListener("mousemove", handle); window.removeEventListener("mouseup", up); };
  });

  const inDead = Math.hypot(value.x, value.y) < 0.12;

  return (
    <div ref={ref}
      onMouseDown={(e) => { setDrag(true); handle(e); }}
      style={{
        position: "relative", width: 88, height: 88, borderRadius: "50%",
        background: "radial-gradient(circle at 30% 25%, rgba(255,255,255,0.06), rgba(0,0,0,0.45) 70%)",
        border: "1px solid var(--glass-border)",
        boxShadow: "0 14px 30px rgba(0,0,0,0.55), 0 0 0 1px rgba(255,255,255,0.04) inset, 0 -3px 8px rgba(0,0,0,0.3) inset",
        display: "grid", placeItems: "center", margin: "0 auto",
        cursor: drag ? "grabbing" : "grab",
        userSelect: "none",
      }}>
      {/* deadzone */}
      <div style={{
        position: "absolute", left: "50%", top: "50%", transform: "translate(-50%,-50%)",
        width: "24%", height: "24%", borderRadius: "50%",
        border: "1px dashed rgba(255,255,255,0.14)",
      }}/>
      {/* thumb */}
      <div style={{
        position: "absolute",
        left: `calc(50% + ${value.x * 22}px)`, top: `calc(50% + ${value.y * 22}px)`,
        transform: "translate(-50%,-50%)",
        width: 36, height: 36, borderRadius: "50%",
        background: inDead
          ? "radial-gradient(circle at 35% 30%, #fff 0%, #444 60%, #1a1a1a 100%)"
          : "radial-gradient(circle at 35% 30%, #fff 0%, var(--mint-bright) 45%, var(--mint-deep) 100%)",
        boxShadow: inDead
          ? "0 4px 10px rgba(0,0,0,0.5), 0 0 0 2px rgba(255,255,255,0.06) inset"
          : "0 4px 10px rgba(0,0,0,0.5), 0 0 16px var(--mint-glow), 0 0 0 2px rgba(255,255,255,0.18) inset",
        transition: drag ? "none" : "left 220ms var(--bt-curve), top 220ms var(--bt-curve)",
      }}/>
      {/* label */}
      <span className="bt-mono" style={{
        position: "absolute", bottom: -16, left: 0, right: 0, textAlign: "center",
        fontSize: 9, color: "var(--fg-2)", letterSpacing: 0.18,
      }}>{label} · {label === "L" ? "L3" : "R3"}</span>
    </div>
  );
};

const DPad = () => {
  const [active, setActive] = React.useState(null);
  const dirs = [
    { k: "U", x: 0, y: -1, d: "M-7 4L0 -4L7 4Z" },
    { k: "R", x: 1, y: 0, d: "M-4 -7L4 0L-4 7Z" },
    { k: "D", x: 0, y: 1, d: "M-7 -4L0 4L7 -4Z" },
    { k: "L", x: -1, y: 0, d: "M4 -7L-4 0L4 7Z" },
  ];
  return (
    <div style={{ position: "relative", width: 88, height: 88 }}>
      {dirs.map(({ k, x, y, d }) => {
        const a = active === k;
        return (
          <button key={k}
            onMouseDown={() => setActive(k)} onMouseUp={() => setActive(null)} onMouseLeave={() => setActive(null)}
            style={{
              position: "absolute",
              left: `calc(50% + ${x * 22}px - 16px)`,
              top: `calc(50% + ${y * 22}px - 16px)`,
              width: 32, height: 32, borderRadius: 8,
              background: a ? "linear-gradient(180deg, var(--mint-bright), var(--mint-deep))" : "linear-gradient(180deg, #2a2c2e, #15171a)",
              border: "1px solid " + (a ? "transparent" : "var(--glass-border)"),
              boxShadow: a
                ? "0 0 18px var(--mint-glow), 0 0 1px rgba(255,255,255,0.4) inset"
                : "0 4px 10px rgba(0,0,0,0.4), 0 0 1px rgba(255,255,255,0.06) inset",
              display: "grid", placeItems: "center", cursor: "pointer",
              transition: "all 120ms var(--bt-curve-fast)",
            }}>
            <svg width="14" height="14" viewBox="-8 -8 16 16">
              <path d={d} fill={a ? "#fff" : "var(--fg-1)"}/>
            </svg>
          </button>
        );
      })}
    </div>
  );
};

const FaceButtons = ({ pressed, press, release }) => {
  const f = (k, color, x, y, label) => (
    <button key={k}
      onMouseDown={() => press(k)} onMouseUp={() => release(k)} onMouseLeave={() => release(k)}
      style={{
        position: "absolute",
        left: `calc(50% + ${x}px - 18px)`, top: `calc(50% + ${y}px - 18px)`,
        width: 36, height: 36, borderRadius: "50%",
        background: pressed[k]
          ? `radial-gradient(circle at 35% 30%, #fff, ${color} 60%, #000 130%)`
          : `radial-gradient(circle at 35% 30%, rgba(255,255,255,0.18), rgba(0,0,0,0.35) 70%)`,
        border: `1.5px solid ${pressed[k] ? color : "var(--glass-border)"}`,
        boxShadow: pressed[k]
          ? `0 0 20px ${color}, 0 0 0 1px rgba(255,255,255,0.18) inset`
          : "0 4px 10px rgba(0,0,0,0.5)",
        color: pressed[k] ? "#fff" : color,
        fontFamily: "var(--display)", fontWeight: 800, fontSize: 14,
        cursor: "pointer", transition: "all 120ms var(--bt-curve-fast)",
        textShadow: pressed[k] ? "0 1px 0 rgba(0,0,0,0.5)" : `0 0 8px ${color}40`,
      }}>{label}</button>
  );
  return (
    <div style={{ position: "relative", width: 96, height: 96 }}>
      {f("Y", "#ffd23f", 0, -22, "Y")}
      {f("X", "#3fb6ff", -22, 0, "X")}
      {f("B", "#ff4060", 22, 0, "B")}
      {f("A", "#3fff80", 0, 22, "A")}
    </div>
  );
};

const Trigger = ({ label, pressed, onPress, onRelease, digital }) => (
  <button onMouseDown={onPress} onMouseUp={onRelease} onMouseLeave={onRelease}
    style={{
      height: 38, borderRadius: 12,
      background: pressed
        ? "linear-gradient(180deg, var(--mint-bright), var(--mint-deep))"
        : "linear-gradient(180deg, rgba(255,255,255,0.04), rgba(0,0,0,0.3))",
      border: "1px solid " + (pressed ? "transparent" : "var(--glass-border)"),
      color: pressed ? "#fff" : "var(--fg-1)",
      fontFamily: "var(--mono)", fontSize: 11, letterSpacing: 0.18,
      cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "space-between",
      padding: "0 12px",
      boxShadow: pressed
        ? "0 0 18px var(--mint-glow), 0 0 1px rgba(255,255,255,0.18) inset"
        : "0 2px 6px rgba(0,0,0,0.35), 0 0 1px rgba(255,255,255,0.04) inset",
      transition: "all 120ms var(--bt-curve-fast)",
      textTransform: "uppercase", textAlign: "left",
    }}>
    <span>{label}</span>
    {digital && <span style={{ fontSize: 8, opacity: 0.6, letterSpacing: 0.16 }}>DIGITAL</span>}
  </button>
);

const CenterRail = () => (
  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
    <CenterBtn label="BACK"/>
    <CenterBtn glyph="◉" guide/>
    <CenterBtn label="START"/>
  </div>
);
const CenterBtn = ({ label, glyph, guide }) => {
  const [a, setA] = React.useState(false);
  return (
    <button
      onMouseDown={() => setA(true)} onMouseUp={() => setA(false)} onMouseLeave={() => setA(false)}
      style={{
        height: 26, padding: glyph ? "0 8px" : "0 12px", borderRadius: 999,
        background: a ? "var(--mint)" : (guide ? "rgba(255,255,255,0.08)" : "transparent"),
        border: "1px solid " + (a ? "transparent" : "var(--glass-border)"),
        color: a ? "#fff" : (guide ? "var(--mint-bright)" : "var(--fg-1)"),
        fontFamily: "var(--mono)", fontSize: 9, letterSpacing: 0.2,
        cursor: "pointer", display: "inline-flex", alignItems: "center",
        textTransform: "uppercase",
        boxShadow: guide ? "0 0 10px var(--mint-glow-soft)" : "none",
        transition: "all 120ms var(--bt-curve-fast)",
      }}>
      {glyph || label}
    </button>
  );
};

const FrameCounter = ({ seq, pulse }) => (
  <div className="bt-mono" style={{
    fontSize: 10, color: "var(--fg-2)", letterSpacing: 0.12, textAlign: "center",
    display: "flex", flexDirection: "column", gap: 2, alignItems: "center",
  }}>
    <span style={{ color: "var(--fg-3)", fontSize: 8, letterSpacing: 0.2 }}>FRAME</span>
    <span className={pulse ? "bt-tick" : ""} style={{ color: "var(--fg-1)" }}>#{seq.toLocaleString()}</span>
  </div>
);

const WakeTrainChip = () => (
  <div className="bt-glass" style={{
    height: 28, padding: "0 10px", borderRadius: 999,
    display: "inline-flex", alignItems: "center", gap: 7,
    fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.1,
    border: "1px solid var(--glass-border)",
  }} title="Browsers need a button event to recognise new gamepads">
    <div style={{ display: "flex", gap: 2 }}>
      {[0, 1, 2].map(i => (
        <span key={i} style={{
          width: 4, height: 4, borderRadius: "50%",
          background: "var(--mint)",
          animation: `btPulse 900ms ${i * 150}ms ease-in-out infinite`,
        }}/>
      ))}
    </div>
    <span style={{ color: "var(--fg-0)", textTransform: "uppercase", letterSpacing: 0.12 }}>Wake train</span>
  </div>
);

const DiagFold = ({ lStick, rStick, hat, pressed, seq }) => (
  <div className="bt-glass-strong" style={{
    position: "absolute", right: 28, top: 56, bottom: 14,
    width: 220, padding: 14, borderRadius: 16,
    border: "1px solid var(--glass-border)",
    fontFamily: "var(--mono)", fontSize: 10, color: "var(--fg-1)",
    overflow: "auto",
  }}>
    <div className="bt-cap" style={{ marginBottom: 8 }}>Live state</div>
    <DiagLine k="L stick X" v={lStick.x.toFixed(3)}/>
    <DiagLine k="L stick Y" v={lStick.y.toFixed(3)}/>
    <DiagLine k="R stick X" v={rStick.x.toFixed(3)}/>
    <DiagLine k="R stick Y" v={rStick.y.toFixed(3)}/>
    <DiagLine k="Hat" v={hat === 8 ? "neutral" : `dir ${hat}`}/>
    <DiagLine k="Trigger pressure" v="digital only"/>
    <DiagLine k="Pressed" v={Object.entries(pressed).filter(([_, v]) => v).map(([k]) => k).join(", ") || "—"}/>
    <DiagLine k="Seq" v={`#${seq}`}/>
    <div className="bt-cap" style={{ marginTop: 12, marginBottom: 6 }}>Last 6 reports</div>
    {Array.from({ length: 6 }).map((_, i) => (
      <div key={i} style={{
        fontSize: 9, color: "var(--fg-2)",
        padding: "2px 0", borderTop: i === 0 ? "none" : "1px solid var(--hairline)",
      }}>
        #{seq - i} · {(i * 8 + 12).toString().padStart(3, "0")}ms · 7B
      </div>
    ))}
  </div>
);

const DiagLine = ({ k, v }) => (
  <div style={{
    display: "flex", justifyContent: "space-between", padding: "4px 0",
    borderTop: "1px solid var(--hairline)",
  }}>
    <span style={{ color: "var(--fg-2)" }}>{k}</span>
    <span style={{ color: "var(--fg-0)" }}>{v}</span>
  </div>
);

Object.assign(window, { GamepadScreen, LandscapePhone });
