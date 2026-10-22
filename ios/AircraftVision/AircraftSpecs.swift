import Foundation

/// Broad airframe shape: drives the top-down drawing and the "body" row of the spec table.
/// Mirrors android/.../AircraftSpecs.kt; keep the two in sync.
enum BodyKind {
    case wide, narrow, regional, turboprop, business, classicProp, transport, fighter, light

    var description: String {
        switch self {
        case .wide: return "Wide-body · twin aisle"
        case .narrow: return "Narrow-body · single aisle"
        case .regional: return "Regional jet"
        case .turboprop: return "Turboprop airliner"
        case .business: return "Business aircraft"
        case .classicProp: return "Piston airliner"
        case .transport: return "Military transport"
        case .fighter: return "Combat / trainer jet"
        case .light: return "Light aircraft"
        }
    }
}

/// Typical, type-level facts for the result sheet: the common configuration, not any airline's cabin.
struct AircraftSpecs {
    let kind: BodyKind
    /// Seats per row split by aisles, e.g. [3, 4, 3]; for cockpit-only types, seats per row front to back.
    let seatGroups: [Int]
    let seating: String
    let engines: Int
    let engineType: String
    /// Engines on the rear fuselage/tail rather than (or, for trijets, as well as) under the wings.
    let tailEngines: Bool

    var enginesText: String {
        let mount: String
        if kind == .wide && engines == 3 { mount = ", wing + tail" }
        else if tailEngines { mount = ", rear-mounted" }
        else if kind == .fighter || kind == .light { mount = "" }
        else { mount = ", wing-mounted" }
        return "\(engines) × \(engineType)\(mount)"
    }
}

private func cabin(_ kind: BodyKind, _ layout: String, _ engines: Int, _ type: String = "turbofan", tail: Bool = false) -> AircraftSpecs {
    AircraftSpecs(kind: kind, seatGroups: layout.split(separator: "-").compactMap { Int($0) },
                  seating: "\(layout) economy", engines: engines, engineType: type, tailEngines: tail)
}

private func other(_ kind: BodyKind, _ seating: String, _ engines: Int, _ type: String, _ seats: [Int] = []) -> AircraftSpecs {
    AircraftSpecs(kind: kind, seatGroups: seats, seating: seating, engines: engines, engineType: type, tailEngines: false)
}

func specsFor(_ name: String) -> AircraftSpecs {
    func starts(_ prefix: String) -> Bool { name.hasPrefix(prefix) }
    // Wide-bodies
    if starts("747") || name == "A380" { return cabin(.wide, "3-4-3", 4) }
    if starts("A340") { return cabin(.wide, "2-4-2", 4) }
    if starts("777") { return cabin(.wide, "3-4-3", 2) }
    if starts("787") || starts("A350") { return cabin(.wide, "3-3-3", 2) }
    if starts("767") { return cabin(.wide, "2-3-2", 2) }
    if starts("A330") || name == "A300B4" || name == "A310" { return cabin(.wide, "2-4-2", 2) }
    if ["DC-10", "MD-11", "L-1011"].contains(name) { return cabin(.wide, "2-5-2", 3, tail: true) }
    // Narrow-bodies
    if name == "707-320" || name == "DC-8" { return cabin(.narrow, "3-3", 4) }
    if ["727-200", "Tu-154", "Yak-42"].contains(name) { return cabin(.narrow, "3-3", 3, tail: true) }
    if name == "Tu-134" { return cabin(.narrow, "2-2", 2, tail: true) }
    if starts("737") || starts("757") || name.range(of: "^A3(18|19|20|21)", options: .regularExpression) != nil {
        return cabin(.narrow, "3-3", 2)
    }
    if starts("A220") { return cabin(.narrow, "2-3", 2) }
    if name == "Boeing 717" || name == "DC-9-30" || starts("MD-8") || name == "MD-90" { return cabin(.narrow, "2-3", 2, tail: true) }
    // Regional jets
    if starts("BAE 146") { return cabin(.regional, "3-2", 4) }
    if name == "Fokker 100" || name == "Fokker 70" { return cabin(.regional, "2-3", 2, tail: true) }
    if starts("E-1") || name == "E-Jet E2" { return cabin(.regional, "2-2", 2) }
    if starts("CRJ") { return cabin(.regional, "2-2", 2, tail: true) }
    if starts("ERJ") { return cabin(.regional, "1-2", 2, tail: true) }
    // Turboprops
    if starts("ATR") || starts("DHC-8") || name == "Fokker 50" { return cabin(.turboprop, "2-2", 2, "turboprop") }
    if starts("Saab") || name == "Dornier 328" || name == "EMB-120" { return cabin(.turboprop, "1-2", 2, "turboprop") }
    if ["Beechcraft 1900", "Metroliner", "DHC-6"].contains(name) { return cabin(.turboprop, "1-1", 2, "turboprop") }
    // Business aircraft
    if name == "Model B200" { return cabin(.business, "1-1", 2, "turboprop") }
    if name == "Falcon 900" { return cabin(.business, "1-1", 3, tail: true) }
    if starts("Gulfstream") || ["Falcon 2000", "Global Express", "Challenger 600", "Cessna 525", "Cessna 560",
                                "Embraer Legacy 600", "BAE-125"].contains(name) {
        return cabin(.business, "1-1", 2, tail: true)
    }
    // Military
    if name == "C-130" || name == "An-12" { return other(.transport, "Cargo hold", 4, "turboprop") }
    if name == "Il-76" { return other(.transport, "Cargo hold", 4, "turbofan") }
    if name == "C-47" { return other(.transport, "Cargo hold", 2, "piston") }
    if ["F-16A/B", "F/A-18", "Eurofighter Typhoon"].contains(name) {
        return other(.fighter, "1–2 crew", name == "F-16A/B" ? 1 : 2, "turbofan", [1])
    }
    if name == "Tornado" { return other(.fighter, "2 crew, tandem", 2, "turbofan", [1, 1]) }
    if name == "Hawk T1" { return other(.fighter, "2 crew, tandem", 1, "turbofan", [1, 1]) }
    if name == "Spitfire" { return other(.fighter, "1 pilot", 1, "piston", [1]) }
    // Piston classics and light aircraft
    if name == "DC-3" { return cabin(.classicProp, "2-1", 2, "piston") }
    if name == "DC-6" { return cabin(.classicProp, "3-2", 4, "piston") }
    if name == "DH-82" || name == "DHC-1" { return other(.light, "2 seats, tandem", 1, "piston", [1, 1]) }
    if name == "Cessna 208" { return cabin(.light, "1-2", 1, "turboprop") }
    return other(.light, "4 seats", 1, "piston", [2, 2])  // Cessna 172, PA-28, SR-20, DR-400
}
