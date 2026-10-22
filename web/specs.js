// Typical, type-level facts for the result sheet: the common configuration, not any airline's cabin.
// Mirrors android/.../AircraftSpecs.kt and ios/.../AircraftSpecs.swift; keep the three in sync.

export const BODY = {
  wide: "Wide-body · twin aisle",
  narrow: "Narrow-body · single aisle",
  regional: "Regional jet",
  turboprop: "Turboprop airliner",
  business: "Business aircraft",
  classicProp: "Piston airliner",
  transport: "Military transport",
  fighter: "Combat / trainer jet",
  light: "Light aircraft",
};

function cabin(kind, layout, engines, type = "turbofan", tail = false) {
  return { kind, seatGroups: layout.split("-").map(Number), seating: `${layout} economy`, engines, engineType: type, tailEngines: tail };
}

function other(kind, seating, engines, type, seats = []) {
  return { kind, seatGroups: seats, seating, engines, engineType: type, tailEngines: false };
}

export function enginesText(s) {
  let mount = ", wing-mounted";
  if (s.kind === "wide" && s.engines === 3) mount = ", wing + tail";
  else if (s.tailEngines) mount = ", rear-mounted";
  else if (s.kind === "fighter" || s.kind === "light") mount = "";
  return `${s.engines} × ${s.engineType}${mount}`;
}

export function specsFor(name) {
  const starts = (p) => name.startsWith(p);
  const is = (...names) => names.includes(name);
  // Wide-bodies
  if (starts("747") || name === "A380") return cabin("wide", "3-4-3", 4);
  if (starts("A340")) return cabin("wide", "2-4-2", 4);
  if (starts("777")) return cabin("wide", "3-4-3", 2);
  if (starts("787") || starts("A350")) return cabin("wide", "3-3-3", 2);
  if (starts("767")) return cabin("wide", "2-3-2", 2);
  if (starts("A330") || is("A300B4", "A310")) return cabin("wide", "2-4-2", 2);
  if (is("DC-10", "MD-11", "L-1011")) return cabin("wide", "2-5-2", 3, "turbofan", true);
  // Narrow-bodies
  if (is("707-320", "DC-8")) return cabin("narrow", "3-3", 4);
  if (is("727-200", "Tu-154", "Yak-42")) return cabin("narrow", "3-3", 3, "turbofan", true);
  if (name === "Tu-134") return cabin("narrow", "2-2", 2, "turbofan", true);
  if (starts("737") || starts("757") || /^A3(18|19|20|21)/.test(name)) return cabin("narrow", "3-3", 2);
  if (starts("A220")) return cabin("narrow", "2-3", 2);
  if (is("Boeing 717", "DC-9-30", "MD-90") || starts("MD-8")) return cabin("narrow", "2-3", 2, "turbofan", true);
  // Regional jets
  if (starts("BAE 146")) return cabin("regional", "3-2", 4);
  if (is("Fokker 100", "Fokker 70")) return cabin("regional", "2-3", 2, "turbofan", true);
  if (starts("E-1") || name === "E-Jet E2") return cabin("regional", "2-2", 2);
  if (starts("CRJ")) return cabin("regional", "2-2", 2, "turbofan", true);
  if (starts("ERJ")) return cabin("regional", "1-2", 2, "turbofan", true);
  // Turboprops
  if (starts("ATR") || starts("DHC-8") || name === "Fokker 50") return cabin("turboprop", "2-2", 2, "turboprop");
  if (starts("Saab") || is("Dornier 328", "EMB-120")) return cabin("turboprop", "1-2", 2, "turboprop");
  if (is("Beechcraft 1900", "Metroliner", "DHC-6")) return cabin("turboprop", "1-1", 2, "turboprop");
  // Business aircraft
  if (name === "Model B200") return cabin("business", "1-1", 2, "turboprop");
  if (name === "Falcon 900") return cabin("business", "1-1", 3, "turbofan", true);
  if (starts("Gulfstream") || is("Falcon 2000", "Global Express", "Challenger 600", "Cessna 525", "Cessna 560", "Embraer Legacy 600", "BAE-125")) {
    return cabin("business", "1-1", 2, "turbofan", true);
  }
  // Military
  if (is("C-130", "An-12")) return other("transport", "Cargo hold", 4, "turboprop");
  if (name === "Il-76") return other("transport", "Cargo hold", 4, "turbofan");
  if (name === "C-47") return other("transport", "Cargo hold", 2, "piston");
  if (is("F-16A/B", "F/A-18", "Eurofighter Typhoon")) return other("fighter", "1–2 crew", name === "F-16A/B" ? 1 : 2, "turbofan", [1]);
  if (name === "Tornado") return other("fighter", "2 crew, tandem", 2, "turbofan", [1, 1]);
  if (name === "Hawk T1") return other("fighter", "2 crew, tandem", 1, "turbofan", [1, 1]);
  if (name === "Spitfire") return other("fighter", "1 pilot", 1, "piston", [1]);
  // Piston classics and light aircraft
  if (name === "DC-3") return cabin("classicProp", "2-1", 2, "piston");
  if (name === "DC-6") return cabin("classicProp", "3-2", 4, "piston");
  if (is("DH-82", "DHC-1")) return other("light", "2 seats, tandem", 1, "piston", [1, 1]);
  if (name === "Cessna 208") return cabin("light", "1-2", 1, "turboprop");
  return other("light", "4 seats", 1, "piston", [2, 2]); // Cessna 172, PA-28, SR-20, DR-400
}
