package com.themagicianleo.aircraftvision

/** Broad airframe shape: drives the top-down drawing and the "body" row of the spec table. */
enum class BodyKind(val description: String) {
    WIDE("Wide-body · twin aisle"),
    NARROW("Narrow-body · single aisle"),
    REGIONAL("Regional jet"),
    TURBOPROP("Turboprop airliner"),
    BUSINESS("Business aircraft"),
    CLASSIC_PROP("Piston airliner"),
    TRANSPORT("Military transport"),
    FIGHTER("Combat / trainer jet"),
    LIGHT("Light aircraft"),
}

/**
 * Typical, type-level facts for the result sheet. These describe the common configuration,
 * not any particular airline's cabin.
 */
data class AircraftSpecs(
    val kind: BodyKind,
    /** Seats per row, split by aisles, e.g. [3, 4, 3]; empty for cargo/cockpit-only types. */
    val seatGroups: List<Int>,
    val seating: String,
    val engines: Int,
    val engineType: String,
    /** Engines on the rear fuselage/tail rather than (or, for trijets, as well as) under the wings. */
    val tailEngines: Boolean,
) {
    val enginesText: String
        get() = "$engines × $engineType" + when {
            kind == BodyKind.WIDE && engines == 3 -> ", wing + tail"
            tailEngines -> ", rear-mounted"
            kind in setOf(BodyKind.FIGHTER, BodyKind.LIGHT) -> ""
            else -> ", wing-mounted"
        }
}

private fun cabin(kind: BodyKind, layout: String, engines: Int, type: String = "turbofan", tail: Boolean = false) =
    AircraftSpecs(kind, layout.split("-").map { it.toInt() }, "$layout economy", engines, type, tail)

private fun other(kind: BodyKind, seating: String, engines: Int, type: String, seats: List<Int> = emptyList()) =
    AircraftSpecs(kind, seats, seating, engines, type, tailEngines = false)

fun specsFor(name: String): AircraftSpecs = when {
    // Wide-bodies
    name.startsWith("747") || name == "A380" -> cabin(BodyKind.WIDE, "3-4-3", 4)
    name.startsWith("A340") -> cabin(BodyKind.WIDE, "2-4-2", 4)
    name.startsWith("777") -> cabin(BodyKind.WIDE, "3-4-3", 2)
    name.startsWith("787") || name.startsWith("A350") -> cabin(BodyKind.WIDE, "3-3-3", 2)
    name.startsWith("767") -> cabin(BodyKind.WIDE, "2-3-2", 2)
    name.startsWith("A330") || name == "A300B4" || name == "A310" -> cabin(BodyKind.WIDE, "2-4-2", 2)
    name == "DC-10" || name == "MD-11" || name == "L-1011" -> cabin(BodyKind.WIDE, "2-5-2", 3, tail = true)
    // Narrow-bodies
    name == "707-320" || name == "DC-8" -> cabin(BodyKind.NARROW, "3-3", 4)
    name == "727-200" || name == "Tu-154" || name == "Yak-42" -> cabin(BodyKind.NARROW, "3-3", 3, tail = true)
    name == "Tu-134" -> cabin(BodyKind.NARROW, "2-2", 2, tail = true)
    name.startsWith("737") || name.startsWith("757") || Regex("A3(18|19|20|21).*").matches(name) ->
        cabin(BodyKind.NARROW, "3-3", 2)
    name.startsWith("A220") -> cabin(BodyKind.NARROW, "2-3", 2)
    name == "Boeing 717" || name == "DC-9-30" || name.startsWith("MD-8") || name == "MD-90" ->
        cabin(BodyKind.NARROW, "2-3", 2, tail = true)
    // Regional jets
    name.startsWith("BAE 146") -> cabin(BodyKind.REGIONAL, "3-2", 4)
    name == "Fokker 100" || name == "Fokker 70" -> cabin(BodyKind.REGIONAL, "2-3", 2, tail = true)
    name.startsWith("E-1") || name == "E-Jet E2" -> cabin(BodyKind.REGIONAL, "2-2", 2)
    name.startsWith("CRJ") -> cabin(BodyKind.REGIONAL, "2-2", 2, tail = true)
    name.startsWith("ERJ") -> cabin(BodyKind.REGIONAL, "1-2", 2, tail = true)
    // Turboprops
    name.startsWith("ATR") || name.startsWith("DHC-8") || name == "Fokker 50" ->
        cabin(BodyKind.TURBOPROP, "2-2", 2, "turboprop")
    name.startsWith("Saab") || name == "Dornier 328" || name == "EMB-120" ->
        cabin(BodyKind.TURBOPROP, "1-2", 2, "turboprop")
    name == "Beechcraft 1900" || name == "Metroliner" || name == "DHC-6" ->
        cabin(BodyKind.TURBOPROP, "1-1", 2, "turboprop")
    // Business aircraft
    name == "Model B200" -> cabin(BodyKind.BUSINESS, "1-1", 2, "turboprop")
    name == "Falcon 900" -> cabin(BodyKind.BUSINESS, "1-1", 3, tail = true)
    name.startsWith("Gulfstream") || name == "Falcon 2000" || name == "Global Express" || name == "Challenger 600" ||
        name == "Cessna 525" || name == "Cessna 560" || name == "Embraer Legacy 600" || name == "BAE-125" ->
        cabin(BodyKind.BUSINESS, "1-1", 2, tail = true)
    // Military
    name == "C-130" || name == "An-12" -> other(BodyKind.TRANSPORT, "Cargo hold", 4, "turboprop")
    name == "Il-76" -> other(BodyKind.TRANSPORT, "Cargo hold", 4, "turbofan")
    name == "C-47" -> other(BodyKind.TRANSPORT, "Cargo hold", 2, "piston")
    name == "F-16A/B" || name == "F/A-18" || name == "Eurofighter Typhoon" ->
        other(BodyKind.FIGHTER, "1–2 crew", if (name == "F-16A/B") 1 else 2, "turbofan", listOf(1))
    name == "Tornado" -> other(BodyKind.FIGHTER, "2 crew, tandem", 2, "turbofan", listOf(1, 1))
    name == "Hawk T1" -> other(BodyKind.FIGHTER, "2 crew, tandem", 1, "turbofan", listOf(1, 1))
    name == "Spitfire" -> other(BodyKind.FIGHTER, "1 pilot", 1, "piston", listOf(1))
    // Piston classics and light aircraft
    name == "DC-3" -> cabin(BodyKind.CLASSIC_PROP, "2-1", 2, "piston")
    name == "DC-6" -> cabin(BodyKind.CLASSIC_PROP, "3-2", 4, "piston")
    name == "DH-82" || name == "DHC-1" -> other(BodyKind.LIGHT, "2 seats, tandem", 1, "piston", listOf(1, 1))
    name == "Cessna 208" -> cabin(BodyKind.LIGHT, "1-2", 1, "turboprop")
    else -> other(BodyKind.LIGHT, "4 seats", 1, "piston", listOf(2, 2))  // Cessna 172, PA-28, SR-20, DR-400
}
