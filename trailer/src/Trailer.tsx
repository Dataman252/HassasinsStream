import React from "react";
import {
  AbsoluteFill,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
  Easing,
} from "remotion";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { slide } from "@remotion/transitions/slide";
import { wipe } from "@remotion/transitions/wipe";
import { loadFont as loadGrotesk } from "@remotion/google-fonts/SpaceGrotesk";
import { loadFont as loadInter } from "@remotion/google-fonts/Inter";

const { fontFamily: HEAD } = loadGrotesk("normal", { weights: ["700"], subsets: ["latin"] });
const { fontFamily: BODY } = loadInter("normal", { weights: ["400", "600", "800"], subsets: ["latin"] });

const VIOLET = "#8B5CF6";
const INDIGO = "#6366F1";
const CYAN = "#22D3EE";
const INK = "#F8FAFC";
const MUTED = "#CBD5E1";

// Scene lengths in frames (30 fps). Transitions overlap 15 frames each.
const T = 15;
const SCENES = [120, 135, 135, 150, 135, 135, 180];
export const TRAILER_FRAMES = SCENES.reduce((a, b) => a + b, 0) - T * (SCENES.length - 1);

// ---------- shared pieces ----------

const useSpring = (delay = 0, damping = 14) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping, mass: 0.8 } });
};

/** Moving aurora behind every scene. */
const Backdrop: React.FC = () => {
  const frame = useCurrentFrame();
  const a = Math.sin(frame / 50) * 120;
  const b = Math.cos(frame / 60) * 140;
  return (
    <AbsoluteFill style={{ background: "linear-gradient(#0F0F23, #030409)", overflow: "hidden" }}>
      <div style={{ position: "absolute", width: 1100, height: 1100, borderRadius: "50%", left: -250 + a, top: -420 + b / 2, background: `radial-gradient(circle, ${VIOLET}88, transparent 65%)` }} />
      <div style={{ position: "absolute", width: 1000, height: 1000, borderRadius: "50%", right: -260 - a, bottom: -460 - b / 2, background: `radial-gradient(circle, ${CYAN}66, transparent 65%)` }} />
      <div style={{ position: "absolute", width: 800, height: 800, borderRadius: "50%", left: 700 + b, top: -500, background: `radial-gradient(circle, ${INDIGO}77, transparent 65%)` }} />
      <AbsoluteFill
        style={{
          backgroundImage: "linear-gradient(rgba(255,255,255,.035) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.035) 1px, transparent 1px)",
          backgroundSize: "60px 60px",
          backgroundPosition: `${frame * 0.6}px ${frame * 0.3}px`,
        }}
      />
      {/* light streaks */}
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          style={{
            position: "absolute",
            height: 3,
            width: 260 - i * 60,
            borderRadius: 2,
            top: 160 + i * 330,
            left: ((frame * (14 + i * 5) + i * 700) % 2600) - 300,
            background: i === 1 ? "#A78BFA" : "#67E8F9",
            opacity: 0.5,
          }}
        />
      ))}
    </AbsoluteFill>
  );
};

/** Words that rise into place one after another. */
const Words: React.FC<{ text: string; size: number; delay?: number; gradient?: boolean; align?: "center" | "left"; font?: string; color?: string; weight?: number }> = ({
  text,
  size,
  delay = 0,
  gradient = false,
  align = "center",
  font = HEAD,
  color = INK,
  weight = 700,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const lines = text.split("\n");
  let index = 0;
  return (
    <div style={{ textAlign: align, lineHeight: 1.05 }}>
      {lines.map((line, li) => (
        <div key={li} style={{ display: "flex", flexWrap: "wrap", gap: size * 0.26, justifyContent: align === "center" ? "center" : "flex-start" }}>
          {line.split(" ").map((w, wi) => {
            const s = spring({ frame: frame - delay - index++ * 4, fps, config: { damping: 15 } });
            return (
              <span
                key={wi}
                style={{
                  display: "inline-block",
                  fontFamily: font,
                  fontWeight: weight,
                  fontSize: size,
                  letterSpacing: font === HEAD ? -1.5 : 0,
                  transform: `translateY(${(1 - s) * 60}px)`,
                  opacity: s,
                  ...(gradient
                    ? { background: `linear-gradient(90deg, #C4B5FD, #ffffff 45%, #ffffff 55%, #67E8F9)`, WebkitBackgroundClip: "text", color: "transparent" }
                    : { color }),
                }}
              >
                {w}
              </span>
            );
          })}
        </div>
      ))}
    </div>
  );
};

const Kicker: React.FC<{ text: string; delay?: number }> = ({ text, delay = 0 }) => {
  const s = useSpring(delay);
  return (
    <div style={{ fontFamily: BODY, fontWeight: 800, fontSize: 28, letterSpacing: "0.22em", color: "#67E8F9", opacity: s, transform: `translateY(${(1 - s) * 20}px)`, marginBottom: 20 }}>
      {text}
    </div>
  );
};

const Pill: React.FC<{ text: string; delay: number; brand?: boolean; size?: number }> = ({ text, delay, brand = false, size = 34 }) => {
  const s = useSpring(delay, 11);
  return (
    <div
      style={{
        padding: `${size * 0.45}px ${size}px`,
        borderRadius: 999,
        fontFamily: BODY,
        fontWeight: 800,
        fontSize: size,
        color: INK,
        transform: `scale(${s})`,
        opacity: Math.min(1, s * 1.5),
        background: brand ? `linear-gradient(90deg, ${VIOLET}, ${CYAN})` : "rgba(255,255,255,.08)",
        border: brand ? "none" : "2px solid rgba(255,255,255,.22)",
        boxShadow: brand ? `0 0 60px ${VIOLET}88` : "none",
      }}
    >
      {text}
    </div>
  );
};

const Phone: React.FC<{ sharing?: boolean }> = ({ sharing = true }) => {
  const frame = useCurrentFrame();
  const pulse = 0.6 + 0.4 * Math.sin(frame / 6);
  return (
    <div style={{ width: 300, height: 580, borderRadius: 50, background: "#0B0B12", border: `5px solid ${VIOLET}`, boxShadow: `0 0 90px ${VIOLET}77`, padding: 22, boxSizing: "border-box", display: "flex", flexDirection: "column", gap: 16 }}>
      <div style={{ height: 170, borderRadius: 18, background: "linear-gradient(135deg,#1B2A4A,#3B1F5C,#0B3B4A)" }} />
      <div style={{ display: "flex", gap: 16 }}>
        <div style={{ flex: 1, height: 150, borderRadius: 18, background: "linear-gradient(135deg,#3B1F5C,#1B2A4A)" }} />
        <div style={{ flex: 1, height: 150, borderRadius: 18, background: "linear-gradient(135deg,#0B3B4A,#3B1F5C)" }} />
      </div>
      {sharing && (
        <div style={{ marginTop: "auto", height: 66, borderRadius: 33, background: CYAN, opacity: pulse, color: "#05070C", fontFamily: BODY, fontWeight: 800, fontSize: 26, display: "flex", alignItems: "center", justifyContent: "center" }}>
          SHARING
        </div>
      )}
    </div>
  );
};

const Tv: React.FC<{ width?: number; height?: number; children?: React.ReactNode }> = ({ width = 900, height = 510, children }) => (
  <div style={{ width, height, borderRadius: 28, background: "#0B0B12", border: `5px solid ${CYAN}`, boxShadow: `0 0 110px ${CYAN}55`, padding: 18, boxSizing: "border-box" }}>
    <div style={{ width: "100%", height: "100%", borderRadius: 14, background: "linear-gradient(135deg,#1B2A4A,#3B1F5C 55%,#0B3B4A)", position: "relative", overflow: "hidden" }}>{children}</div>
  </div>
);

// ---------- scenes ----------

const LogoScene: React.FC = () => {
  const frame = useCurrentFrame();
  const s = useSpring(0, 10);
  const glow = 40 + 30 * Math.sin(frame / 8);
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
      <Img
        src={staticFile("logo.svg")}
        style={{ width: 300, height: 300, transform: `scale(${s}) rotate(${(1 - s) * -25}deg)`, filter: `drop-shadow(0 0 ${glow}px ${VIOLET})` }}
      />
      <div style={{ marginTop: 50 }}>
        <Words text="Hassasin’s Stream" size={128} delay={12} gradient />
      </div>
      <div style={{ marginTop: 26 }}>
        <Words text="Your private cinema. On every screen." size={46} delay={32} font={BODY} color={MUTED} weight={400} />
      </div>
    </AbsoluteFill>
  );
};

const ServerScene: React.FC = () => {
  const frame = useCurrentFrame();
  const phoneIn = useSpring(0);
  const folders = ["Movies", "Shows", "Music", "USB drive"];
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 130 }}>
        <div style={{ position: "relative", transform: `translateX(${(1 - phoneIn) * -500}px)` }}>
          <Phone />
          {/* folders flying into the phone */}
          {folders.map((f, i) => {
            const t = interpolate(frame, [20 + i * 10, 50 + i * 10], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.inOut(Easing.cubic) });
            return (
              <div
                key={f}
                style={{
                  position: "absolute",
                  left: interpolate(t, [0, 1], [-330, 70]),
                  top: 120 + i * 90 - t * 40,
                  opacity: t < 1 ? 1 : 0,
                  transform: `scale(${1 - t * 0.6})`,
                  padding: "12px 22px",
                  borderRadius: 14,
                  background: "rgba(255,255,255,.12)",
                  border: "2px solid rgba(255,255,255,.25)",
                  fontFamily: BODY,
                  fontWeight: 600,
                  fontSize: 26,
                  color: INK,
                  whiteSpace: "nowrap",
                }}
              >
                📁 {f}
              </div>
            );
          })}
          {/* Wi-Fi arcs */}
          <svg width="160" height="200" viewBox="0 0 160 200" style={{ position: "absolute", right: -170, top: 190 }}>
            {[30, 60, 90].map((r, i) => (
              <path
                key={r}
                d={`M 20 ${100 - r} A ${r} ${r} 0 0 1 20 ${100 + r}`}
                fill="none"
                stroke={CYAN}
                strokeWidth={8}
                strokeLinecap="round"
                opacity={0.25 + 0.75 * Math.max(0, Math.sin((frame - i * 6) / 7))}
              />
            ))}
          </svg>
        </div>
        <div style={{ maxWidth: 900 }}>
          <Kicker text="NO SERVER · NO CLOUD" delay={6} />
          <Words text={"Your phone\nis the server."} size={116} delay={10} gradient align="left" />
          <div style={{ marginTop: 28 }}>
            <Words text={"Share any folder or USB drive.\nNothing leaves your home."} size={44} delay={34} font={BODY} color={MUTED} weight={400} align="left" />
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

const CinemaScene: React.FC = () => {
  const frame = useCurrentFrame();
  const tv = useSpring(0, 13);
  const progress = interpolate(frame, [10, 130], [0.05, 0.78], { extrapolateRight: "clamp" });
  const badges = ["4K", "Dolby Vision", "HDR10+", "Dolby Atmos", "DTS"];
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
      <Kicker text="EVERY SCREEN IS A CINEMA" />
      <div style={{ transform: `scale(${0.7 + tv * 0.3})`, opacity: tv }}>
        <Tv>
          <div style={{ position: "absolute", left: 60, bottom: 80, fontFamily: HEAD, fontWeight: 700, fontSize: 70, color: INK }}>Now playing</div>
          <div style={{ position: "absolute", left: 62, bottom: 50, fontFamily: BODY, fontSize: 30, color: MUTED }}>Untouched, bit for bit</div>
          <div style={{ position: "absolute", left: 60, right: 60, bottom: 26, height: 10, borderRadius: 5, background: "rgba(255,255,255,.25)" }}>
            <div style={{ width: `${progress * 100}%`, height: 10, borderRadius: 5, background: CYAN, boxShadow: `0 0 20px ${CYAN}` }} />
          </div>
          {/* light sweep */}
          <div style={{ position: "absolute", top: 0, bottom: 0, width: 160, left: ((frame * 12) % 1300) - 200, background: "linear-gradient(90deg, transparent, rgba(255,255,255,.12), transparent)", transform: "skewX(-20deg)" }} />
        </Tv>
      </div>
      <div style={{ display: "flex", gap: 22, marginTop: 50 }}>
        {badges.map((b, i) => (
          <Pill key={b} text={b} delay={24 + i * 7} />
        ))}
      </div>
    </AbsoluteFill>
  );
};

const QrScene: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const phoneIn = spring({ frame: frame - 25, fps, config: { damping: 14 } });
  const scan = interpolate(frame, [45, 85], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const done = spring({ frame: frame - 90, fps, config: { damping: 9 } });
  const cells = [
    "111010111", "101101101", "111010111", "010110010", "101011101", "011101011", "111010101", "101101110", "111011011",
  ];
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 110 }}>
        <div style={{ maxWidth: 760 }}>
          <Kicker text="ZERO TYPING" />
          <Words text={"Scan.\nConnected."} size={120} delay={6} gradient align="left" />
          <div style={{ marginTop: 28 }}>
            <Words text={"The TV shows a code, the phone scans it.\nNo router? The TV makes its own link."} size={40} delay={30} font={BODY} color={MUTED} weight={400} align="left" />
          </div>
        </div>
        <div style={{ position: "relative" }}>
          <Tv width={680} height={440}>
            <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
              <div style={{ position: "relative", width: 250, height: 250, background: "#fff", borderRadius: 22, padding: 20, boxSizing: "border-box", display: "grid", gridTemplateColumns: "repeat(9, 1fr)", gap: 4, opacity: 1 - done }}>
                {cells.join("").split("").map((c, i) => (
                  <div key={i} style={{ background: c === "1" ? "#05070C" : "transparent", borderRadius: 3 }} />
                ))}
                <div style={{ position: "absolute", left: 0, right: 0, height: 5, top: 10 + scan * 230, background: CYAN, boxShadow: `0 0 24px ${CYAN}`, opacity: scan > 0 && scan < 1 ? 1 : 0 }} />
              </div>
              <div style={{ position: "absolute", transform: `scale(${done})`, display: "flex", flexDirection: "column", alignItems: "center", gap: 16 }}>
                <div style={{ width: 150, height: 150, borderRadius: 75, background: "#22C55E", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 96, color: "#fff", boxShadow: "0 0 80px #22C55E" }}>✓</div>
                <div style={{ fontFamily: HEAD, fontWeight: 700, fontSize: 48, color: INK }}>Connected</div>
              </div>
            </AbsoluteFill>
          </Tv>
          <div style={{ position: "absolute", right: -80, bottom: -120, transform: `translateY(${(1 - phoneIn) * 500}px) rotate(-8deg) scale(0.62)` }}>
            <Phone sharing={done > 0.5} />
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

const LayoutCard: React.FC<{ name: string; note: string; delay: number; active: boolean; bg: string; accent: string; kind: number }> = ({ name, note, delay, active, bg, accent, kind }) => {
  const s = useSpring(delay, 12);
  return (
    <div style={{ transform: `translateY(${(1 - s) * 200}px) scale(${active ? 1.06 : 1})`, opacity: s, transition: "none" }}>
      <div style={{ width: 400, height: 250, borderRadius: 22, background: bg, position: "relative", overflow: "hidden", border: `4px solid ${active ? accent : "transparent"}`, boxShadow: active ? `0 0 60px ${accent}88` : "none" }}>
        {kind === 0 && (
          <>
            <div style={{ position: "absolute", left: 120, top: 18, width: 160, height: 26, borderRadius: 13, background: "rgba(255,255,255,.3)" }} />
            <div style={{ position: "absolute", left: 24, top: 64, width: 220, height: 100, borderRadius: 16, background: "rgba(255,255,255,.25)" }} />
            <div style={{ position: "absolute", left: 24, right: 24, bottom: 20, height: 46, borderRadius: 12, background: "rgba(255,255,255,.16)" }} />
          </>
        )}
        {kind === 1 && (
          <>
            <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 80, background: "rgba(0,0,0,.4)" }} />
            <div style={{ position: "absolute", left: 104, top: 66, width: 210, height: 36, borderRadius: 4, background: "#fff" }} />
            <div style={{ position: "absolute", left: 104, top: 112, width: 130, height: 12, borderRadius: 4, background: accent }} />
            <div style={{ position: "absolute", left: 104, right: 18, bottom: 22, height: 50, borderRadius: 6, background: "rgba(255,255,255,.16)" }} />
          </>
        )}
        {kind === 2 && (
          <>
            <div style={{ position: "absolute", left: 20, top: 18, width: 180, height: 20, borderRadius: 10, background: "#D0BCFF77" }} />
            <div style={{ position: "absolute", left: 20, top: 54, width: 270, height: 110, borderRadius: 24, background: "linear-gradient(120deg,#4F378B,#1E3A5F)" }} />
            <div style={{ position: "absolute", left: 304, top: 54, width: 120, height: 110, borderRadius: 24, background: "#7F67BE" }} />
            {[0, 1, 2].map((i) => (
              <div key={i} style={{ position: "absolute", left: 24 + i * 120, bottom: 14, fontFamily: HEAD, fontSize: 56, fontWeight: 700, color: "transparent", WebkitTextStroke: `2px ${accent}` }}>{i + 1}</div>
            ))}
          </>
        )}
        {kind === 3 && (
          <>
            <div style={{ position: "absolute", left: 16, top: 16, bottom: 16, width: 184, borderRadius: 16, background: "linear-gradient(135deg,#334A7A,#6B3A8C)" }} />
            <div style={{ position: "absolute", left: 214, right: 16, top: 16, height: 100, borderRadius: 16, background: "#18181D" }} />
            <div style={{ position: "absolute", left: 214, width: 76, top: 128, bottom: 16, borderRadius: 16, background: "#18181D" }} />
            <div style={{ position: "absolute", right: 16, width: 76, top: 128, bottom: 16, borderRadius: 16, background: "#18181D" }} />
          </>
        )}
      </div>
      <div style={{ fontFamily: HEAD, fontWeight: 700, fontSize: 40, color: INK, marginTop: 18 }}>{name}</div>
      <div style={{ fontFamily: BODY, fontSize: 24, color: "#94A3B8" }}>{note}</div>
    </div>
  );
};

const LayoutsScene: React.FC = () => {
  const frame = useCurrentFrame();
  const active = Math.floor(Math.max(0, frame - 40) / 22) % 4;
  const cards = [
    { name: "Glass", note: "Floating glass, big hero", bg: "linear-gradient(120deg,#1B2A4A,#3B1F5C,#0B3B4A)", accent: "#67E8F9" },
    { name: "Spotlight", note: "Full-screen art, side menu", bg: "linear-gradient(120deg,#1C1917,#3F2A14,#2A1A3F)", accent: "#F5B14C" },
    { name: "Material", note: "Carousel and Top picks", bg: "#141218", accent: "#D0BCFF" },
    { name: "Bento", note: "Everything in tiles", bg: "#0B0B0E", accent: "#7AA2FF" },
  ];
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
      <Kicker text="MAKE IT YOURS" />
      <Words text="Four stunning looks." size={110} delay={4} gradient />
      <div style={{ display: "flex", gap: 36, marginTop: 70 }}>
        {cards.map((c, i) => (
          <LayoutCard key={c.name} {...c} kind={i} delay={18 + i * 6} active={frame > 40 && active === i} />
        ))}
      </div>
    </AbsoluteFill>
  );
};

const FeaturesScene: React.FC = () => {
  const items: [string, string][] = [
    ["▶", "Continue watching"],
    ["⏭", "Up next & skip intro"],
    ["💬", "Subtitles in any language"],
    ["🖼", "Seek-bar previews"],
    ["🎚", "Equalizer & night mode"],
    ["🔄", "Always up to date"],
  ];
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
      <Kicker text="FEELS LIKE A STREAMING APP" />
      <Words text="Everything you expect." size={104} delay={4} gradient />
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 440px)", gap: 28, marginTop: 64 }}>
        {items.map(([icon, label], i) => (
          <FeatureCard key={label} icon={icon} label={label} delay={16 + i * 5} />
        ))}
      </div>
    </AbsoluteFill>
  );
};

const FeatureCard: React.FC<{ icon: string; label: string; delay: number }> = ({ icon, label, delay }) => {
  const s = useSpring(delay, 12);
  return (
    <div
      style={{
        borderRadius: 28,
        background: "rgba(255,255,255,.07)",
        border: "2px solid rgba(255,255,255,.14)",
        padding: 34,
        transform: `scale(${0.6 + 0.4 * s}) rotateX(${(1 - s) * 50}deg)`,
        opacity: s,
      }}
    >
      <div style={{ fontSize: 56 }}>{icon}</div>
      <div style={{ fontFamily: BODY, fontWeight: 800, fontSize: 34, color: INK, marginTop: 10 }}>{label}</div>
    </div>
  );
};

const CtaScene: React.FC = () => {
  const frame = useCurrentFrame();
  const logo = useSpring(0, 10);
  const pulse = 1 + 0.04 * Math.sin(frame / 5);
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
      <Img src={staticFile("logo.svg")} style={{ width: 200, height: 200, transform: `scale(${logo})`, filter: `drop-shadow(0 0 50px ${VIOLET})` }} />
      <div style={{ marginTop: 44 }}>
        <Words text="Free. No ads. No account." size={116} delay={10} gradient />
      </div>
      <div style={{ marginTop: 22 }}>
        <Words text="Android phones · Android TV · Fire TV" size={44} delay={30} font={BODY} color={MUTED} weight={400} />
      </div>
      <div style={{ marginTop: 56, transform: `scale(${frame > 50 ? pulse : 1})` }}>
        <Pill text="Download free on GitHub" delay={46} brand size={40} />
      </div>
      <div style={{ marginTop: 26 }}>
        <Words text="github.com/Dataman252/HassasinsStream" size={34} delay={60} font={BODY} color="#94A3B8" weight={600} />
      </div>
    </AbsoluteFill>
  );
};

// ---------- the trailer ----------

export const Trailer: React.FC = () => {
  const frame = useCurrentFrame();
  const fadeOut = interpolate(frame, [TRAILER_FRAMES - 20, TRAILER_FRAMES], [1, 0], { extrapolateLeft: "clamp" });
  const scenes = [LogoScene, ServerScene, CinemaScene, QrScene, LayoutsScene, FeaturesScene, CtaScene];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const transitions: any[] = [
    fade(),
    slide({ direction: "from-right" }),
    wipe({ direction: "from-left" }),
    slide({ direction: "from-bottom" }),
    fade(),
    slide({ direction: "from-right" }),
  ];
  return (
    <AbsoluteFill style={{ backgroundColor: "#030409", opacity: fadeOut }}>
      <Backdrop />
      <TransitionSeries>
        {scenes.map((Scene, i) => (
          <React.Fragment key={i}>
            <TransitionSeries.Sequence durationInFrames={SCENES[i]}>
              <Scene />
            </TransitionSeries.Sequence>
            {i < scenes.length - 1 && <TransitionSeries.Transition presentation={transitions[i]} timing={linearTiming({ durationInFrames: T })} />}
          </React.Fragment>
        ))}
      </TransitionSeries>
    </AbsoluteFill>
  );
};
