import SwiftUI

/// The identification, laid out like a cabin spec sheet: type name, a top-down drawing of a
/// typical layout for that type, a spec table, then the runners-up. Mirrors the Android ResultSheet.kt.
struct ResultDetails: View {
    let predictions: [Prediction]
    let inferenceMs: Int?

    var body: some View {
        let best = predictions[0]
        let specs = specsFor(best.label.name)
        // Close variants (737-800 vs -900) are the hardest call. When the model is torn between
        // variants but sure of the family, say the family rather than guess a variant.
        let familyMode = best.familySize > 1 && best.confidence < 0.6 && best.familyConfidence >= 0.75
        let score = familyMode ? best.familyConfidence : best.confidence
        let likely = predictions.filter { $0.label.family == best.label.family }.prefix(2)

        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    CapsLabel(text: best.label.manufacturer)
                    Text(familyMode ? familyTitle(best.label) : best.label.name)
                        .font(.system(size: 36, weight: .heavy))
                        .tracking(-1)
                        .fixedSize(horizontal: false, vertical: true)
                    if familyMode {
                        Text("Variant unclear · most likely " + likely.map(\.label.name).joined(separator: " or "))
                            .font(.callout).foregroundStyle(.secondary)
                    } else if !best.label.family.isEmpty && best.label.family != best.label.name {
                        Text(best.label.family).font(.callout).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                MatchScore(confidence: score)
            }

            if score < 0.4 {
                Label("Low match. Fit the box tightly around one aircraft.", systemImage: "info.circle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 12)
            }

            Planform(specs: specs)
                .frame(height: 116)
                .padding(.vertical, 14)
                .padding(.horizontal, 10)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                .padding(.top, 18)
            HStack(spacing: 14) {
                if !specs.seatGroups.isEmpty && ![.fighter, .light, .transport].contains(specs.kind) {
                    LegendSwatch(color: Theme.premium, text: "Premium")
                    LegendSwatch(color: Theme.accent, text: "Economy")
                }
                Spacer()
                Text("Typical layout · illustrative").font(.caption2).italic().foregroundStyle(.secondary)
            }
            .padding(.top, 8)

            CapsLabel(text: "Specifications").padding(.top, 22).padding(.bottom, 4)
            SpecRow(label: "Manufacturer", value: best.label.manufacturer.isEmpty ? "—" : best.label.manufacturer)
            SpecRow(label: "Family", value: best.label.family.isEmpty ? best.label.name : best.label.family)
            if familyMode {
                SpecRow(label: "Variant", value: likely.map { "\($0.label.name) \(Int($0.confidence * 100))%" }.joined(separator: " / "))
            }
            SpecRow(label: "Body", value: specs.kind.description)
            SpecRow(label: "Seating", value: specs.seating)
            SpecRow(label: "Engines", value: specs.enginesText, last: true)

            if predictions.count > 1 {
                CapsLabel(text: "Could also be").padding(.top, 24).padding(.bottom, 4)
                ForEach(Array(predictions.dropFirst().enumerated()), id: \.element.id) { i, p in
                    RunnerUp(prediction: p, last: i == predictions.count - 2)
                }
            }

            if let ms = inferenceMs {
                Text("Identified on your phone in \(ms) ms · no internet used")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)
            }
        }
    }

    /// "Boeing 737" -> "Boeing 737 family"; "A320" -> "Airbus A320 family".
    private func familyTitle(_ label: AircraftLabel) -> String {
        let family = label.family.isEmpty ? label.name : label.family
        let withMaker = !label.manufacturer.isEmpty && !family.hasPrefix(label.manufacturer) ? "\(label.manufacturer) \(family)" : family
        return "\(withMaker) family"
    }
}

private struct MatchScore: View {
    let confidence: Float
    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text("\(Int(confidence * 100))%")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Theme.confidenceColor(confidence))
                .contentTransition(.numericText())
            CapsLabel(text: "Match")
        }
    }
}

private struct SpecRow: View {
    let label: String
    let value: String
    var last = false
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(label).font(.callout).foregroundStyle(.secondary).frame(width: 118, alignment: .leading)
                Text(value).font(.body.weight(.medium)).multilineTextAlignment(.trailing).frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.vertical, 11)
            if !last { Divider() }
        }
    }
}

private struct LegendSwatch: View {
    let color: Color
    let text: String
    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 3).fill(color).frame(width: 10, height: 10)
            Text(text).font(.caption.weight(.medium)).foregroundStyle(.secondary)
        }
    }
}

private struct RunnerUp: View {
    let prediction: Prediction
    let last: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(prediction.label.name).font(.headline)
                    Text([prediction.label.manufacturer, specsFor(prediction.label.name).kind.description]
                        .filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(Int(prediction.confidence * 100))%").font(.headline).foregroundStyle(.secondary)
            }
            ProgressView(value: Double(max(prediction.confidence, 0.01)))
                .tint(.secondary)
        }
        .padding(.vertical, 10)
        if !last { Divider() }
    }
}

// MARK: - Top-down drawing

/// A top-down drawing in the style of a cabin layout plan, nose to the left: wings, engines and
/// fuselage outline, with seat rows filled in (premium rows first) for passenger types.
struct Planform: View {
    let specs: AircraftSpecs

    var body: some View {
        Canvas { ctx, size in draw(&ctx, size) }
    }

    private struct PlanShape { let bodyWidth, bodyLength: CGFloat; let swept: Bool }

    private var shape: PlanShape {
        switch specs.kind {
        case .wide: return PlanShape(bodyWidth: 0.42, bodyLength: 0.98, swept: true)
        case .narrow: return PlanShape(bodyWidth: 0.32, bodyLength: 0.96, swept: true)
        case .regional: return PlanShape(bodyWidth: 0.28, bodyLength: 0.86, swept: true)
        case .turboprop: return PlanShape(bodyWidth: 0.26, bodyLength: 0.80, swept: false)
        case .business: return PlanShape(bodyWidth: 0.24, bodyLength: 0.74, swept: true)
        case .classicProp: return PlanShape(bodyWidth: 0.28, bodyLength: 0.80, swept: false)
        case .transport: return PlanShape(bodyWidth: 0.34, bodyLength: 0.86, swept: false)
        case .fighter: return PlanShape(bodyWidth: 0.20, bodyLength: 0.70, swept: true)
        case .light: return PlanShape(bodyWidth: 0.20, bodyLength: 0.52, swept: false)
        }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        let ink = Color.primary
        let fighter = specs.kind == .fighter
        let len = size.width * shape.bodyLength
        let bw = size.height * shape.bodyWidth
        let x0 = (size.width - len) / 2
        let cy = size.height / 2
        let noseLen = bw * (fighter ? 2.2 : 1.0)
        let tailLen = bw * 1.7
        let outline = StrokeStyle(lineWidth: 1.5, lineJoin: .round)

        // Wings from about 40% of the length, reaching the edges of the drawing; tailplane at the back.
        let wingRoot = x0 + len * (fighter ? 0.48 : 0.40)
        let rootChord = len * (fighter ? 0.40 : 0.20)
        let tipChord = len * (shape.swept ? 0.07 : 0.11)
        let sweep = shape.swept ? len * (fighter ? 0.30 : 0.17) : len * 0.02
        let span = size.height / 2
        for side: CGFloat in [-1, 1] {
            var wing = Path()
            wing.move(to: CGPoint(x: wingRoot, y: cy + side * bw / 2))
            wing.addLine(to: CGPoint(x: wingRoot + sweep, y: cy + side * span))
            wing.addLine(to: CGPoint(x: wingRoot + sweep + tipChord, y: cy + side * span))
            wing.addLine(to: CGPoint(x: wingRoot + rootChord, y: cy + side * bw / 2))
            wing.closeSubpath()
            ctx.fill(wing, with: .color(ink.opacity(0.06)))
            ctx.stroke(wing, with: .color(ink.opacity(0.3)), style: outline)
            let tx = x0 + len - tailLen * 0.75
            var tail = Path()
            tail.move(to: CGPoint(x: tx, y: cy + side * bw * 0.3))
            tail.addLine(to: CGPoint(x: tx + tailLen * 0.35, y: cy + side * bw * 1.25))
            tail.addLine(to: CGPoint(x: tx + tailLen * 0.55, y: cy + side * bw * 1.25))
            tail.addLine(to: CGPoint(x: tx + tailLen * 0.6, y: cy + side * bw * 0.3))
            tail.closeSubpath()
            ctx.fill(tail, with: .color(ink.opacity(0.06)))
            ctx.stroke(tail, with: .color(ink.opacity(0.3)), style: outline)
        }

        drawEngines(&ctx, x0: x0, len: len, cy: cy, bw: bw, wingRoot: wingRoot, sweep: sweep, span: span, tailLen: tailLen, ink: ink)

        // Fuselage: rounded nose, straight cabin, tapering tail cone.
        var body = Path()
        body.move(to: CGPoint(x: x0 + noseLen, y: cy - bw / 2))
        body.addLine(to: CGPoint(x: x0 + len - tailLen, y: cy - bw / 2))
        body.addLine(to: CGPoint(x: x0 + len, y: cy - bw * 0.08))
        body.addLine(to: CGPoint(x: x0 + len, y: cy + bw * 0.08))
        body.addLine(to: CGPoint(x: x0 + len - tailLen, y: cy + bw / 2))
        body.addLine(to: CGPoint(x: x0 + noseLen, y: cy + bw / 2))
        body.addCurve(to: CGPoint(x: x0, y: cy), control1: CGPoint(x: x0 + noseLen * 0.35, y: cy + bw / 2), control2: CGPoint(x: x0, y: cy + bw * 0.2))
        body.addCurve(to: CGPoint(x: x0 + noseLen, y: cy - bw / 2), control1: CGPoint(x: x0, y: cy - bw * 0.2), control2: CGPoint(x: x0 + noseLen * 0.35, y: cy - bw / 2))
        body.closeSubpath()
        ctx.fill(body, with: .color(Color(.systemBackground)))
        ctx.stroke(body, with: .color(ink.opacity(0.55)), style: outline)

        let cabinStart = x0 + noseLen + bw * 0.25
        let cabinEnd = x0 + len - tailLen
        switch specs.kind {
        case .fighter, .light:
            drawCockpit(&ctx, x: x0 + noseLen * 0.9, cy: cy, bw: bw, ink: ink)
        case .transport:
            ctx.stroke(Path(roundedRect: CGRect(x: cabinStart, y: cy - bw * 0.32, width: cabinEnd - cabinStart, height: bw * 0.64), cornerRadius: 4),
                       with: .color(ink.opacity(0.35)), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
        default:
            drawSeats(&ctx, start: cabinStart, end: cabinEnd, cy: cy, bw: bw)
        }
    }

    /// Seat rows across the cabin: groups of seats separated by aisles, premium rows at the front.
    private func drawSeats(_ ctx: inout GraphicsContext, start: CGFloat, end: CGFloat, cy: CGFloat, bw: CGFloat) {
        let groups = specs.seatGroups
        guard !groups.isEmpty else { return }
        let seats = CGFloat(groups.reduce(0, +))
        let aisles = CGFloat(groups.count - 1)
        let unit = min(bw * 0.78 / (seats + aisles * 0.9), 9)
        let seat = unit * 0.82
        let pitch = unit * 1.3
        let rows = Int(((end - start) / pitch).rounded(.down))
        let premiumRows = max(2, rows / 7)
        let total = unit * (seats + aisles * 0.9)
        for row in 0..<max(rows, 0) {
            let x = start + CGFloat(row) * pitch
            var y = cy - total / 2
            let color = row < premiumRows ? Theme.premium : Theme.accent
            for (g, n) in groups.enumerated() {
                for _ in 0..<n {
                    ctx.fill(Path(roundedRect: CGRect(x: x, y: y + (unit - seat) / 2, width: seat, height: seat), cornerRadius: seat * 0.25),
                             with: .color(color.opacity(0.85)))
                    y += unit
                }
                if g < groups.count - 1 { y += unit * 0.9 }
            }
        }
    }

    /// Canopy and seats for small and military types; seatGroups lists seats per row, front to back.
    private func drawCockpit(_ ctx: inout GraphicsContext, x: CGFloat, cy: CGFloat, bw: CGFloat, ink: Color) {
        let rows = specs.seatGroups
        let seat = bw * 0.26
        let canopyLen = seat * (CGFloat(rows.count) * 1.5 + 0.8)
        ctx.fill(Path(ellipseIn: CGRect(x: x, y: cy - bw * 0.3, width: canopyLen, height: bw * 0.6)), with: .color(ink.opacity(0.12)))
        for (r, n) in rows.enumerated() {
            let sx = x + seat * 0.6 + CGFloat(r) * seat * 1.5
            for i in 0..<n {
                let sy = cy - CGFloat(n) * seat * 0.6 + CGFloat(i) * seat * 1.2 + seat * 0.1
                ctx.fill(Path(roundedRect: CGRect(x: sx, y: sy, width: seat, height: seat), cornerRadius: seat * 0.25),
                         with: .color(Theme.accent.opacity(0.85)))
            }
        }
    }

    private func drawEngines(_ ctx: inout GraphicsContext, x0: CGFloat, len: CGFloat, cy: CGFloat, bw: CGFloat,
                             wingRoot: CGFloat, sweep: CGFloat, span: CGFloat, tailLen: CGFloat, ink: Color) {
        if specs.kind == .fighter || specs.kind == .light { return }
        let podLen = len * 0.11
        let podW = min(bw * 0.36, (span - bw / 2) * 0.3)  // four engines must fit on each half-span
        func pod(_ cx: CGFloat, _ cyPod: CGFloat) {
            let r = CGRect(x: cx - podLen / 2, y: cyPod - podW / 2, width: podLen, height: podW)
            ctx.fill(Path(roundedRect: r, cornerRadius: podW / 2), with: .color(ink.opacity(0.16)))
            ctx.stroke(Path(roundedRect: r, cornerRadius: podW / 2), with: .color(ink.opacity(0.45)), lineWidth: 1)
            if specs.engineType != "turbofan" {  // propeller disc in front of the nacelle
                var prop = Path()
                prop.move(to: CGPoint(x: r.minX - 1, y: cyPod - podW * 1.3))
                prop.addLine(to: CGPoint(x: r.minX - 1, y: cyPod + podW * 1.3))
                ctx.stroke(prop, with: .color(ink.opacity(0.4)), lineWidth: 1.5)
            }
        }
        let wingEngines = specs.kind == .wide && specs.engines == 3 ? 2 : (specs.tailEngines ? 0 : specs.engines)
        let perSide = wingEngines / 2
        for side: CGFloat in [-1, 1] {
            for i in 0..<perSide {
                let frac: CGFloat = perSide == 1 ? 0.42 : 0.28 + CGFloat(i) * 0.42
                let y = cy + side * (bw / 2 + (span - bw / 2) * frac)
                let leading = wingRoot + sweep * ((y - cy) * side - bw / 2) / (span - bw / 2)
                pod(leading + podLen * 0.05, y)
            }
        }
        if specs.tailEngines {
            let x = x0 + len - tailLen * 1.05
            if specs.kind != .wide {
                pod(x, cy - bw / 2 - podW / 2)
                pod(x, cy + bw / 2 + podW / 2)
            }
            if specs.engines == 3 {
                let r = podW * 0.45
                let c = CGPoint(x: x0 + len - tailLen * 0.55, y: cy)
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(ink.opacity(0.25)))
            }
        }
    }
}

/// Every type the model knows, grouped by manufacturer, searchable.
struct SupportedTypesView: View {
    let types: [AircraftLabel]
    @State private var query = ""

    var body: some View {
        NavigationStack {
            let filtered = types.filter {
                query.isEmpty || [$0.name, $0.family, $0.manufacturer].contains { $0.localizedCaseInsensitiveContains(query) }
            }
            let groups = Dictionary(grouping: filtered) { $0.manufacturer.isEmpty ? "Other" : $0.manufacturer }
            List {
                ForEach(groups.keys.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }, id: \.self) { maker in
                    Section(maker) {
                        ForEach(groups[maker]!.sorted { $0.name < $1.name }, id: \.name) { t in
                            HStack {
                                Text(t.name)
                                Spacer()
                                if !t.family.isEmpty && t.family != t.name {
                                    Text(t.family).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search, e.g. 787 or Airbus")
            .navigationTitle("\(types.count) aircraft types")
        }
    }
}
