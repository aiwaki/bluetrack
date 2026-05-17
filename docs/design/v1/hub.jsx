// hub.jsx — main control center
// Includes: Pair-as-action, Pin block, Trust flow, Visible-as, FG-service chip,
// touchpad with tap/scroll/right-click, gamepad surface entry, activity strip.

const HUB_HOSTS = {
  none: null,
  mac: { name: "studio-mbp", os: "macOS", fingerprint: "f3:a7:91:c2:de:08", pinnedAt: "Apr 24, 14:02" },
};

// ---------- Identity / Visible-as line ----------
// ---------- Identity / Visible-as line — REMOVED from Hub (moved to Settings) ----------
const VisibleAs = () => null;

// ---------- FG service chip in appbar (compact "● Live") ----------
const ServiceChip = ({ running }) => (
  <span style={{
    display: "inline-flex", alignItems: "center", gap: 6,
    height: 26, padding: "0 9px", borderRadius: 999,
    background: running ? "var(--mint-glow-soft)" : "rgba(255,255,255,0.05)",
    color: running ? "var(--mint-bright)" : "var(--fg-2)",
    fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.1, textTransform: "uppercase",
    border: `1px solid ${running ? "transparent" : "var(--hairline)"}`,
  }}>
    {running && <Pulse size={6}/>}
    {running ? "Live" : "Off"}
  </span>
);

// ---------- BIG PIN BLOCK — with auto-clear clipboard ----------
const PinBlock = ({ pin, session, gattOpen }) => {
  const [copied, setCopied] = React.useState(false);
  const [secs, setSecs] = React.useState(0);
  React.useEffect(() => {
    if (!copied) return;
    let t = 30; setSecs(t);
    const id = setInterval(() => {
      t -= 1; setSecs(t);
      if (t <= 0) {
        navigator.clipboard?.writeText("").catch(() => {});
        setCopied(false);
        clearInterval(id);
      }
    }, 1000);
    return () => clearInterval(id);
  }, [copied]);
  const digits = (pin || "------").split("");
  return (
    <div style={{ padding: "0 18px", marginBottom: 12 }}>
      <div className="bt-glass-strong" style={{
        borderRadius: 22, padding: "16px 16px 14px", position: "relative", overflow: "hidden",
        boxShadow: gattOpen ? "0 0 0 1px var(--mint-glow-soft) inset, 0 12px 40px var(--mint-glow-soft)" : "none",
      }}>
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", marginBottom: 10, gap: 12 }}>
          <div style={{ minWidth: 0 }}>
            <div className="bt-cap" style={{ marginBottom: 2 }}>Pairing PIN</div>
            <div className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)" }}>session #{session}</div>
          </div>
          {gattOpen ? <Chip kind="live"><Pulse size={5}/>GATT open</Chip> : <Chip kind="calm">GATT closed</Chip>}
        </div>
        {gattOpen ? (
          <>
            <button onClick={() => { navigator.clipboard?.writeText(pin); setCopied(true); }}
              style={{
                width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center",
                padding: "8px 4px", background: "transparent", border: "none", cursor: "pointer",
              }}>
              {digits.map((d, i) => (
                <span key={i} className="bt-mono bt-neon-title" style={{
                  flex: 1, textAlign: "center", fontSize: 38, fontWeight: 600,
                  letterSpacing: 0,
                }}>{d}</span>
              ))}
            </button>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 8, gap: 12, flexWrap: "wrap" }}>
              <code style={{
                fontFamily: "var(--mono)", fontSize: 11, color: "var(--fg-2)",
                background: "rgba(255,255,255,0.04)", padding: "3px 8px", borderRadius: 8,
                whiteSpace: "nowrap",
              }}>--pin {pin}</code>
              <span style={{ fontSize: 10, color: copied ? "var(--mint-bright)" : "var(--fg-3)", fontFamily: "var(--mono)", whiteSpace: "nowrap" }}>
                {copied ? `copied · clears in ${secs}s` : "tap to copy"}
              </span>
            </div>
            <div style={{ fontSize: 11, color: "var(--fg-3)", marginTop: 6, lineHeight: 1.4 }}>
              New PIN every time the feedback channel opens. Auto-clears clipboard after 30s.
            </div>
          </>
        ) : (
          <div style={{ padding: "18px 4px 6px", display: "flex", flexDirection: "column", gap: 6 }}>
            <div style={{ fontFamily: "var(--mono)", fontSize: 26, color: "var(--fg-3)", letterSpacing: 6 }}>— — — — — —</div>
            <div style={{ fontSize: 12, color: "var(--fg-2)" }}>PIN appears once a host opens the feedback channel.</div>
          </div>
        )}
      </div>
    </div>
  );
};

// ---------- Wordmark (re-uses shared) ----------

// ---------- Heartbeat trace ----------
const Heartbeat = ({ active }) => {
  const [t, setT] = React.useState(0);
  React.useEffect(() => {
    if (!active) return;
    let raf;
    const tick = () => { setT(v => v + 1); raf = requestAnimationFrame(tick); };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [active]);
  // Build a path: small noise sine + spike every ~36 frames
  const W = 280, H = 18;
  const pts = [];
  for (let x = 0; x <= W; x += 4) {
    const phase = (x + t * 2) / 12;
    let y = H / 2 + Math.sin(phase) * 0.6;
    const spikeAt = ((x + t * 2) % 72);
    if (spikeAt < 8) {
      const s = Math.sin((spikeAt / 8) * Math.PI);
      y -= s * 7;
    } else if (spikeAt < 14) {
      const s = Math.sin(((spikeAt - 8) / 6) * Math.PI);
      y += s * 3;
    }
    pts.push(`${x},${y.toFixed(2)}`);
  }
  return (
    <div className="bt-heartbeat" style={{ padding: "4px 18px 6px" }}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
        <polyline
          className="hb-pulse"
          points={pts.join(" ")}
          fill="none"
          stroke="var(--mint-bright)"
          strokeWidth="1.2"
          strokeLinecap="round"
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
          style={{ filter: "drop-shadow(0 0 5px var(--mint-glow))" }}/>
        <line x1="0" y1={H - 0.5} x2={W} y2={H - 0.5} stroke="rgba(255,255,255,0.06)" strokeWidth="0.5"/>
      </svg>
    </div>
  );
};

// ---------- Neon ribbon (single-flash on fresh handshake) ----------
const NeonRibbon = ({ trigger }) => {
  const [n, setN] = React.useState(0);
  React.useEffect(() => { if (trigger) setN(v => v + 1); }, [trigger]);
  if (!n) return null;
  return <div key={n} className="bt-ribbon"/>;
};

// ---------- TRUST CARD ----------
const TrustCard = ({ state, host, onForget, onShowQR }) => {
  // state: 'empty' | 'pinned' | 'rejection'
  return (
    <div style={{ padding: "0 18px", marginBottom: 12 }}>
      <div className="bt-glass" style={{ borderRadius: 22, padding: 16 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12 }}>
          <div className="bt-cap">Trusted host</div>
          <button onClick={onShowQR} style={{
            background: "transparent", border: "1px solid var(--hairline)", color: "var(--fg-1)",
            borderRadius: 999, padding: "3px 10px", fontSize: 10, fontFamily: "var(--mono)", letterSpacing: 0.06,
            textTransform: "uppercase", cursor: "pointer",
          }}>Show QR</button>
        </div>

        {state === "empty" && (
          <div style={{ display: "flex", alignItems: "flex-start", gap: 12 }}>
            <div style={{
              width: 44, height: 44, borderRadius: 12, flexShrink: 0,
              border: "1px dashed var(--hairline-2)",
              display: "grid", placeItems: "center", color: "var(--fg-3)",
            }}>
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M10 3v14M3 10h14" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 14, fontWeight: 500, marginBottom: 2 }}>No host trusted yet</div>
              <div style={{ fontSize: 11, color: "var(--fg-2)", lineHeight: 1.45 }}>
                The first host that opens the feedback channel will be pinned. After that, others are rejected.
              </div>
            </div>
          </div>
        )}

        {state === "pinned" && (
          <>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <div style={{
                width: 44, height: 44, borderRadius: 12, flexShrink: 0,
                background: "var(--mint-glow-soft)",
                display: "grid", placeItems: "center", color: "var(--mint-bright)",
              }}>
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M5 10.5l3.2 3 6.3-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 500 }}>{host.name} <span style={{ color: "var(--fg-3)", fontWeight: 400 }}>· {host.os}</span></div>
                <div className="bt-mono" style={{ fontSize: 11, color: "var(--fg-2)", marginTop: 2 }}>{host.fingerprint}</div>
              </div>
            </div>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 12, paddingTop: 10, borderTop: "1px solid var(--hairline)" }}>
              <span style={{ fontSize: 11, color: "var(--fg-3)" }}>pinned {host.pinnedAt}</span>
              <button onClick={onForget} style={{
                background: "transparent", border: "1px solid var(--hairline)",
                color: "var(--fg-1)", borderRadius: 999, padding: "5px 12px",
                fontSize: 11, fontFamily: "var(--mono)", letterSpacing: 0.04, cursor: "pointer",
              }}>Forget…</button>
            </div>
          </>
        )}

        {state === "rejection" && (
          <div style={{
            background: "rgba(255,184,107,0.08)", border: "1px solid rgba(255,184,107,0.2)",
            borderRadius: 12, padding: 12,
          }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
              <Chip kind="warn">Rejected</Chip>
              <span style={{ fontSize: 11, color: "var(--fg-2)" }}>2 min ago</span>
            </div>
            <div style={{ fontSize: 13, marginBottom: 4 }}>A different host tried to connect.</div>
            <div className="bt-mono" style={{ fontSize: 11, color: "var(--fg-2)" }}>91:b4:c0:de:7e:11</div>
            <div style={{ fontSize: 11, color: "var(--fg-2)", marginTop: 6, lineHeight: 1.4 }}>
              Tap Forget to re-pair, or ignore to keep your trusted host.
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// ---------- Forget confirm dialog ----------
const ForgetConfirm = ({ open, onClose, onConfirm, host }) => {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: "absolute", inset: 0, zIndex: 60,
      background: "rgba(0,0,0,0.55)",
      display: "flex", alignItems: "center", justifyContent: "center",
      animation: "btFadeIn 180ms ease-out",
    }}>
      <div onClick={(e) => e.stopPropagation()} className="bt-glass-strong" style={{
        margin: 18, padding: 20, borderRadius: 22, width: "calc(100% - 36px)",
        animation: "btScaleIn 220ms cubic-bezier(0.22,1,0.36,1)",
      }}>
        <div style={{ fontFamily: "var(--display)", fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Forget {host?.name}?</div>
        <div style={{ fontSize: 13, color: "var(--fg-1)", marginBottom: 14, lineHeight: 1.5 }}>
          Bluetrack will pin a new host on the next handshake.
        </div>
        <div style={{
          fontSize: 11, color: "var(--fg-2)", lineHeight: 1.5, marginBottom: 18,
          padding: "10px 12px", background: "rgba(255,255,255,0.04)", borderRadius: 12,
          border: "1px dashed var(--hairline-2)",
        }}>
          <strong style={{ color: "var(--fg-1)" }}>Heads-up.</strong> If you also want to roll the host's identity, run <code style={{ fontFamily: "var(--mono)", color: "var(--mint-bright)" }}>--reset-host-identity</code> on the CLI. Otherwise the same machine will pin itself back.
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <button onClick={onClose} style={{
            flex: 1, height: 44, borderRadius: 16,
            background: "transparent", border: "1px solid var(--hairline-2)",
            color: "var(--fg-0)", fontWeight: 500, cursor: "pointer",
          }}>Cancel</button>
          <button onClick={onConfirm} style={{
            flex: 1, height: 44, borderRadius: 16,
            background: "var(--mint)", border: "none", color: "#fff",
            fontWeight: 600, cursor: "pointer", boxShadow: "0 8px 22px var(--mint-glow)",
          }}>Forget</button>
        </div>
      </div>
    </div>
  );
};

// ---------- QR sheet placeholder ----------
const QRSheet = ({ open, onClose, fingerprint }) => {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: "absolute", inset: 0, zIndex: 60,
      background: "rgba(0,0,0,0.55)",
      display: "flex", alignItems: "flex-end",
      animation: "btFadeIn 180ms ease-out",
    }}>
      <div onClick={(e) => e.stopPropagation()} className="bt-glass-strong" style={{
        margin: 12, padding: 20, borderRadius: 22, width: "calc(100% - 24px)",
        animation: "btFadeUp 280ms cubic-bezier(0.22,1,0.36,1)",
      }}>
        <div style={{ width: 36, height: 4, borderRadius: 2, background: "var(--fg-3)", margin: "0 auto 14px" }}/>
        <div style={{ fontFamily: "var(--display)", fontSize: 20, fontWeight: 700, marginBottom: 4 }}>Identity QR</div>
        <div style={{ fontSize: 12, color: "var(--fg-2)", marginBottom: 16 }}>
          Scan from the host CLI to pair without typing a fingerprint.
        </div>
        <div style={{
          aspectRatio: "1 / 1", width: "70%", margin: "0 auto",
          background: "#fff", borderRadius: 12, padding: 12,
          backgroundImage: `repeating-linear-gradient(0deg, #000 0 6%, transparent 6% 12%, #000 12% 14%, transparent 14% 22%, #000 22% 30%, transparent 30% 36%, #000 36% 42%, transparent 42% 50%, #000 50% 58%, transparent 58% 66%, #000 66% 72%, transparent 72% 80%, #000 80% 88%, transparent 88% 94%, #000 94% 100%), repeating-linear-gradient(90deg, transparent 0 6%, #000 6% 14%, transparent 14% 26%, #000 26% 32%, transparent 32% 44%, #000 44% 52%, transparent 52% 64%, #000 64% 70%, transparent 70% 82%, #000 82% 88%, transparent 88% 100%)`,
          backgroundBlendMode: "multiply",
          boxShadow: "0 0 0 8px #fff, 0 12px 40px rgba(0,0,0,0.6)",
        }}/>
        <div className="bt-mono" style={{ fontSize: 11, color: "var(--fg-2)", textAlign: "center", marginTop: 14 }}>
          {fingerprint}
        </div>
      </div>
    </div>
  );
};

// ---------- Pair primary action (when no host) ----------
const PairCTA = ({ onPair }) => (
  <div style={{ padding: "0 18px", marginBottom: 14 }}>
    <div className="bt-glass-strong" style={{ borderRadius: 22, padding: 18, textAlign: "center" }}>
      <div className="bt-display" style={{ fontSize: 24, marginBottom: 6 }}>Ready to pair</div>
      <div style={{ fontSize: 12, color: "var(--fg-2)", marginBottom: 14, lineHeight: 1.5 }}>
        Make this phone discoverable for 120 seconds. Your host will see <span className="bt-mono" style={{ color: "var(--fg-1)" }}>Bluetrack Pro Engine</span>.
      </div>
      <button onClick={onPair} style={{
        width: "100%", height: 50, borderRadius: 16,
        background: "var(--mint)", color: "#fff", border: "none", cursor: "pointer",
        fontWeight: 600, fontSize: 15,
        boxShadow: "0 12px 32px var(--mint-glow), 0 0 0 1px var(--mint-glow-soft) inset",
      }}>Pair a computer</button>
      <div style={{ fontSize: 10, color: "var(--fg-3)", marginTop: 8, fontFamily: "var(--mono)", letterSpacing: 0.04 }}>
        Android will prompt for discoverability
      </div>
    </div>
  </div>
);

// ---------- Touchpad with tap/scroll/right-click semantics ----------
const Touchpad = ({ tweaks }) => {
  const ref = React.useRef(null);
  const [trail, setTrail] = React.useState([]);
  const [hint, setHint] = React.useState(null); // "click" | "rclick" | "scroll"
  const [twoFinger, setTwoFinger] = React.useState(false);
  const startRef = React.useRef(null);

  const onPointerDown = (e) => {
    const r = ref.current.getBoundingClientRect();
    const x = e.clientX - r.left, y = e.clientY - r.top;
    startRef.current = { x, y, t: Date.now(), moved: 0 };
    setTrail([{ x, y, life: 1 }]);
  };
  const onPointerMove = (e) => {
    if (!startRef.current) return;
    const r = ref.current.getBoundingClientRect();
    const x = e.clientX - r.left, y = e.clientY - r.top;
    const dx = x - startRef.current.x, dy = y - startRef.current.y;
    startRef.current.moved += Math.abs(dx) + Math.abs(dy);
    setTrail(prev => [...prev.slice(-20), { x, y, life: 1 }]);
  };
  const onPointerUp = (e) => {
    const s = startRef.current;
    if (s && s.moved < 8 && Date.now() - s.t < 220) {
      const isRight = e.button === 2 || e.shiftKey;
      setHint(isRight ? "rclick" : "click");
      setTimeout(() => setHint(null), 700);
    }
    startRef.current = null;
    setTimeout(() => setTrail([]), 220);
  };
  const onContext = (e) => { e.preventDefault(); setHint("rclick"); setTimeout(() => setHint(null), 700); };

  return (
    <div style={{ padding: "0 18px", marginBottom: 12 }}>
      <div ref={ref}
        className="bt-glass"
        onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}
        onContextMenu={onContext}
        style={{
          height: 220, borderRadius: 22, position: "relative", overflow: "hidden",
          touchAction: "none", cursor: "crosshair",
          background: "linear-gradient(180deg, rgba(255,255,255,0.04), rgba(0,0,0,0.18))",
        }}>
        {/* grid */}
        <svg style={{ position: "absolute", inset: 0, opacity: 0.18, pointerEvents: "none" }} width="100%" height="100%">
          <defs>
            <pattern id="hub-grid" x="0" y="0" width="22" height="22" patternUnits="userSpaceOnUse">
              <path d="M22 0H0V22" fill="none" stroke="var(--fg-2)" strokeWidth="0.4"/>
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#hub-grid)"/>
        </svg>
        {/* trail polyline */}
        {trail.length > 1 && (
          <svg style={{ position: "absolute", inset: 0, pointerEvents: "none" }} width="100%" height="100%">
            <polyline
              points={trail.map(p => `${p.x},${p.y}`).join(" ")}
              fill="none" stroke="var(--mint-bright)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"
              style={{ filter: "drop-shadow(0 0 8px var(--mint-glow))" }}/>
          </svg>
        )}
        {/* liquid drop */}
        {trail.length > 0 && (() => {
          const last = trail[trail.length - 1];
          return (
            <div style={{
              position: "absolute", left: last.x - 18, top: last.y - 18,
              width: 36, height: 36, borderRadius: "50%",
              background: "radial-gradient(circle, var(--mint-bright) 0%, var(--mint) 50%, transparent 75%)",
              filter: "blur(2px)", pointerEvents: "none",
              boxShadow: "0 0 24px var(--mint-glow)",
            }}/>
          );
        })()}
        {/* hint */}
        {hint && (
          <div style={{
            position: "absolute", left: "50%", top: 14, transform: "translateX(-50%)",
            padding: "6px 12px", borderRadius: 999, background: "var(--bg-1)",
            border: "1px solid var(--mint-glow-soft)", color: "var(--mint-bright)",
            fontFamily: "var(--mono)", fontSize: 11, letterSpacing: 0.06, textTransform: "uppercase",
            animation: "btFadeIn 120ms ease-out",
          }}>{hint === "click" ? "Left click" : hint === "rclick" ? "Right click" : "Scroll"}</div>
        )}
        {/* legend in corner */}
        <div style={{
          position: "absolute", left: 12, bottom: 10, right: 12,
          display: "flex", justifyContent: "space-between", alignItems: "center",
          fontFamily: "var(--mono)", fontSize: 9, letterSpacing: 0.08, textTransform: "uppercase",
          color: "var(--fg-3)", pointerEvents: "none",
        }}>
          <span>1F · drag = move</span>
          <span>tap = click</span>
          <span>2F · scroll</span>
          <span>long = right</span>
        </div>
      </div>
    </div>
  );
};

// ---------- Activity strip preview ----------
const ActivityStrip = ({ items, onOpen, onItem }) => (
  <div style={{ padding: "0 18px", marginBottom: 12 }}>
    <SectionLabel action={<button onClick={onOpen} style={{
      background: "transparent", border: "none", color: "var(--fg-2)",
      fontFamily: "var(--mono)", fontSize: 10, letterSpacing: 0.08, textTransform: "uppercase", cursor: "pointer",
    }}>see all →</button>}>Activity</SectionLabel>
    <div className="bt-glass" style={{ borderRadius: 16, overflow: "hidden" }}>
      {items.slice(0, 4).map((it, i) => (
        <button key={i} onClick={() => onItem?.(it)} style={{
          width: "100%", textAlign: "left", background: "transparent", border: "none",
          display: "flex", alignItems: "center", gap: 10, padding: "10px 12px",
          borderTop: i ? "1px solid var(--hairline)" : "none",
          cursor: "pointer", color: "var(--fg-0)",
        }}>
          <span style={{
            width: 6, height: 6, borderRadius: "50%", flexShrink: 0,
            background: it.kind === "warn" ? "var(--warn)" : it.kind === "good" ? "var(--mint)" : "var(--fg-3)",
            boxShadow: it.kind === "warn" ? "0 0 8px var(--warn)" : it.kind === "good" ? "0 0 8px var(--mint-glow)" : "none",
          }}/>
          <span style={{ flex: 1, fontSize: 12, color: "var(--fg-1)" }}>{it.text}</span>
          <span className="bt-mono" style={{ fontSize: 10, color: "var(--fg-3)" }}>{it.t}</span>
          <svg width="10" height="10" viewBox="0 0 10 10" style={{ color: "var(--fg-3)", flexShrink: 0 }}>
            <path d="M3 1l4 4-4 4" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </button>
      ))}
    </div>
  </div>
);

// ---------- Gamepad shortcut card (with discoverability hint) ----------
const GamepadShortcut = ({ onEnter }) => (
  <div style={{ padding: "0 18px", marginBottom: 12 }}>
    <button onClick={onEnter} style={{
      width: "100%", textAlign: "left",
      background: "transparent", border: "none", padding: 0, cursor: "pointer",
    }}>
      <div className="bt-glass" style={{
        borderRadius: 16, padding: 14, display: "flex", alignItems: "center", gap: 12,
      }}>
        <div style={{
          width: 44, height: 44, borderRadius: 12,
          background: "rgba(109,214,255,0.12)",
          display: "grid", placeItems: "center", color: "var(--cool)",
        }}>
          <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
            <rect x="2" y="6" width="18" height="11" rx="3.5" stroke="currentColor" strokeWidth="1.4"/>
            <circle cx="14.5" cy="11.5" r="1" fill="currentColor"/>
            <circle cx="17" cy="11.5" r="1" fill="currentColor"/>
            <path d="M5.5 9.5v4M3.5 11.5h4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
          </svg>
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 500, marginBottom: 2 }}>Gamepad mode</div>
          <div style={{ fontSize: 11, color: "var(--fg-2)" }}>Sticks · D-pad · 16 buttons · rotates to landscape</div>
        </div>
        <span className="bt-mono" style={{
          fontSize: 10, color: "var(--mint-bright)", letterSpacing: 0.12, textTransform: "uppercase",
          display: "inline-flex", alignItems: "center", gap: 4,
        }}>Open ↗</span>
      </div>
    </button>
  </div>
);

// ---------- HUB ROOT ----------
const HubScreen = ({ id = "hub-screen", state = "connected", showFGChip = true, onNav = () => {}, label = "01 Hub" }) => {
  // state: 'unpaired' | 'connected'
  const [forget, setForget] = React.useState(false);
  const [qr, setQR] = React.useState(false);
  const [dock, setDock] = React.useState("hub");
  const handleNav = (k) => { setDock(k); onNav(k); };

  const host = state === "connected" ? HUB_HOSTS.mac : HUB_HOSTS.none;
  const trustState = state === "connected" ? "pinned" : "empty";

  return (
    <Phone id={id} label={label}>
      {state === "connected" && <NeonRibbon trigger={1}/>}
      <AppBar
        title={state === "connected" ? "Hub" : "Hub"}
        right={showFGChip ? <ServiceChip running={state === "connected"}/> : null}
      />

      <ScreenShell dock={<Dock active={dock} onChange={handleNav}/>}>
        <div className="bt-stagger" style={{ display: "flex", flexDirection: "column" }}>
          {/* status hero */}
          <div style={{ padding: "4px 18px 14px" }}>
            <div className="bt-glass-strong" style={{
              borderRadius: 22, padding: 18,
              display: "flex", alignItems: "center", gap: 14,
            }}>
              <div style={{
                width: 56, height: 56, borderRadius: 16, flexShrink: 0,
                position: "relative",
                background: state === "connected" ? "var(--mint-glow-soft)" : "rgba(255,255,255,0.05)",
                display: "grid", placeItems: "center",
                color: state === "connected" ? "var(--mint-bright)" : "var(--fg-2)",
              }}>
                {state === "connected" ? (
                  <>
                    <Pulse size={14}/>
                    <span style={{
                      position: "absolute", inset: -4, borderRadius: 22,
                      border: "1px solid var(--mint-glow-soft)",
                      animation: "btBreath 2.4s ease-in-out infinite",
                    }}/>
                  </>
                ) : (
                  <svg width="22" height="22" viewBox="0 0 22 22" fill="none"><path d="M6 6l10 10M16 6 6 16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/></svg>
                )}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="bt-cap" style={{ marginBottom: 4 }}>
                  {state === "connected" ? "Active link" : "Waiting"}
                </div>
                <div className="bt-display" style={{ fontSize: 26 }}>
                  {state === "connected" ? host.name : "no host"}
                </div>
                {state === "connected" && (
                  <div className="bt-mono" style={{ fontSize: 11, color: "var(--fg-2)", marginTop: 4 }}>
                    HID · 1000 Hz · 0 dropped
                  </div>
                )}
              </div>
            </div>
          </div>

          <VisibleAs/>

          {state === "unpaired" ? (
            <PairCTA onPair={() => {}}/>
          ) : (
            <>
              <PinBlock pin="304817" session={3} gattOpen={true}/>
              <TrustCard state={trustState} host={host} onForget={() => setForget(true)} onShowQR={() => setQR(true)}/>
              <Touchpad/>
              <GamepadShortcut onEnter={() => onNav("gamepad")}/>
              <ActivityStrip
                items={[
                  { kind: "good", text: "Connected to studio-mbp", t: "now" },
                  { kind: "good", text: "GATT session #3 opened", t: "−2m" },
                  { kind: "warn", text: "Replay window dropped 1", t: "−6m" },
                  { kind: "good", text: "Foreground service started", t: "−18m" },
                ]}
                onOpen={() => onNav("activity")}
                onItem={() => onNav("activity")}
              />
            </>
          )}
        </div>

        {state === "connected" && <Heartbeat active={true}/>}
      </ScreenShell>

      <ForgetConfirm open={forget} host={host} onClose={() => setForget(false)} onConfirm={() => setForget(false)}/>
      <QRSheet open={qr} fingerprint={host?.fingerprint} onClose={() => setQR(false)}/>
    </Phone>
  );
};

Object.assign(window, { HubScreen, ServiceChip, PinBlock, TrustCard, ActivityStrip });
