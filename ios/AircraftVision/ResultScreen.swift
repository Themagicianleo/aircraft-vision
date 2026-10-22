import SwiftUI

struct ResultScreen: View {
    @Bindable var model: AppModel
    @State private var sheetShown = true
    private let peek: CGFloat = 0.42

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()
                if let image = model.image, let crop = model.crop {
                    CropEditor(image: image, crop: crop, analysing: model.busy) { model.setCrop($0) }
                        .padding(20)
                        .padding(.bottom, geo.size.height * peek - 10)
                }
                GlassButton(systemName: "xmark", label: "Back to camera") { model.clear() }
                    .padding(12)
            }
        }
        .sheet(isPresented: $sheetShown) {
            ResultSheet(model: model)
                .presentationDetents([.fraction(peek), .large])
                .presentationBackgroundInteraction(.enabled(upThrough: .fraction(peek)))
                .presentationCornerRadius(28)
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled()
        }
    }
}

private enum Handle { case none, move, topLeft, topRight, bottomLeft, bottomRight }

/// The whole photo with a box marking what gets identified. Drag a corner to resize the box,
/// drag inside it to move it; letting go identifies the new area. Sparkles show while it works.
struct CropEditor: View {
    let image: CGImage
    let crop: CropBox
    let analysing: Bool
    let onChange: (CropBox) -> Void

    @State private var dragBox: CropBox?
    @State private var startBox: CropBox?
    @State private var handle: Handle = .none
    private static let dots: [(CGFloat, CGFloat, Double)] = {
        var seed: UInt64 = 42
        func next() -> Double {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Double(seed >> 11) / Double(1 << 53)
        }
        return (0..<40).map { _ in (CGFloat(next()), CGFloat(next()), next()) }
    }()

    var body: some View {
        GeometryReader { geo in
            let area = fitted(CGSize(width: image.width, height: image.height), in: geo.size)
            let box = dragBox ?? crop
            ZStack {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .frame(width: area.width, height: area.height)
                    .position(x: area.midX, y: area.midY)
                TimelineView(.animation(paused: !analysing)) { timeline in
                    let t = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.4) / 2.4
                    Canvas { ctx, _ in
                        let b = rect(of: box, in: area)
                        let dim = Color.black.opacity(0.5)
                        ctx.fill(Path(CGRect(x: area.minX, y: area.minY, width: area.width, height: b.minY - area.minY)), with: .color(dim))
                        ctx.fill(Path(CGRect(x: area.minX, y: b.maxY, width: area.width, height: area.maxY - b.maxY)), with: .color(dim))
                        ctx.fill(Path(CGRect(x: area.minX, y: b.minY, width: b.minX - area.minX, height: b.height)), with: .color(dim))
                        ctx.fill(Path(CGRect(x: b.maxX, y: b.minY, width: area.maxX - b.maxX, height: b.height)), with: .color(dim))
                        ctx.stroke(Path(b), with: .color(.white.opacity(0.5)), lineWidth: 1)
                        if analysing {
                            for (x, y, phase) in Self.dots {
                                let a = pow((sin((t + phase) * 2 * .pi) + 1) / 2, 2)
                                let c = CGPoint(x: b.minX + x * b.width, y: b.minY + y * b.height)
                                let glow = 3 + 4 * a, core = 1 + 1.5 * a
                                ctx.fill(Path(ellipseIn: CGRect(x: c.x - glow, y: c.y - glow, width: glow * 2, height: glow * 2)),
                                         with: .color(Theme.accent.opacity(0.35 * a)))
                                ctx.fill(Path(ellipseIn: CGRect(x: c.x - core, y: c.y - core, width: core * 2, height: core * 2)),
                                         with: .color(.white.opacity(a)))
                            }
                        }
                        drawCornerBrackets(&ctx, in: b)
                    }
                }
                .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if startBox == nil {
                            startBox = box
                            handle = hitTest(value.startLocation, rect(of: box, in: area))
                        }
                        guard let start = startBox, handle != .none, area.width > 0 else { return }
                        dragBox = start.dragged(
                            handle,
                            dx: value.translation.width / area.width, dy: value.translation.height / area.height,
                            minW: 56 / area.width, minH: 56 / area.height
                        )
                    }
                    .onEnded { _ in
                        if handle != .none, let dragged = dragBox { onChange(dragged) }
                        startBox = nil
                        handle = .none
                    }
            )
            .onChange(of: crop) { _, _ in dragBox = nil }  // e.g. "Reset box" from the sheet
        }
    }

    private func fitted(_ image: CGSize, in container: CGSize) -> CGRect {
        let scale = min(container.width / image.width, container.height / image.height)
        let w = image.width * scale, h = image.height * scale
        return CGRect(x: (container.width - w) / 2, y: (container.height - h) / 2, width: w, height: h)
    }

    private func rect(of box: CropBox, in area: CGRect) -> CGRect {
        CGRect(
            x: area.minX + box.left * area.width, y: area.minY + box.top * area.height,
            width: (box.right - box.left) * area.width, height: (box.bottom - box.top) * area.height
        )
    }

    private func hitTest(_ p: CGPoint, _ box: CGRect) -> Handle {
        let corners: [(Handle, CGPoint)] = [
            (.topLeft, CGPoint(x: box.minX, y: box.minY)), (.topRight, CGPoint(x: box.maxX, y: box.minY)),
            (.bottomLeft, CGPoint(x: box.minX, y: box.maxY)), (.bottomRight, CGPoint(x: box.maxX, y: box.maxY)),
        ]
        let nearest = corners.min { hypot($0.1.x - p.x, $0.1.y - p.y) < hypot($1.1.x - p.x, $1.1.y - p.y) }!
        if hypot(nearest.1.x - p.x, nearest.1.y - p.y) <= 40 { return nearest.0 }
        return box.contains(p) ? .move : .none
    }
}

private extension CropBox {
    /// The box after dragging `handle` by (dx, dy) in 0...1 image units; stays inside the photo.
    func dragged(_ handle: Handle, dx: CGFloat, dy: CGFloat, minW: CGFloat, minH: CGFloat) -> CropBox {
        func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat { Swift.max(lo, Swift.min(v, hi)) }
        var b = self
        switch handle {
        case .move:
            let w = right - left, h = bottom - top
            b.left = clamp(left + dx, 0, 1 - w)
            b.top = clamp(top + dy, 0, 1 - h)
            b.right = b.left + w
            b.bottom = b.top + h
        case .topLeft:
            b.left = clamp(left + dx, 0, right - minW)
            b.top = clamp(top + dy, 0, bottom - minH)
        case .topRight:
            b.right = clamp(right + dx, left + minW, 1)
            b.top = clamp(top + dy, 0, bottom - minH)
        case .bottomLeft:
            b.left = clamp(left + dx, 0, right - minW)
            b.bottom = clamp(bottom + dy, top + minH, 1)
        case .bottomRight:
            b.right = clamp(right + dx, left + minW, 1)
            b.bottom = clamp(bottom + dy, top + minH, 1)
        case .none:
            break
        }
        return b
    }
}

/// The results sheet: hint + reset, then searching / error / the result spec sheet.
struct ResultSheet: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let image = model.image, !model.busy {
                    let centre = CropBox.centreSquare(width: image.width, height: image.height)
                    HStack {
                        Text("Drag the box corners to fit the aircraft")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Spacer()
                        if model.crop != centre {
                            Button("Reset box") { model.setCrop(centre) }.font(.callout.weight(.semibold))
                        }
                    }
                }
                if let error = model.error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
                } else if model.busy || model.predictions.isEmpty {
                    Searching()
                } else {
                    ResultDetails(predictions: model.predictions, inferenceMs: model.inferenceMs)
                }
                if !model.busy {
                    Button {
                        model.clear()
                    } label: {
                        Label("Search another aircraft", systemImage: "magnifyingglass")
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 16))
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
    }
}

private struct Searching: View {
    @State private var pulse = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ProgressView()
                Text("Looking closely…").font(.headline)
            }
            Group {
                RoundedRectangle(cornerRadius: 10).frame(width: 200, height: 34)
                RoundedRectangle(cornerRadius: 8).frame(width: 130, height: 18)
                ForEach(0..<3, id: \.self) { _ in RoundedRectangle(cornerRadius: 12).frame(height: 44) }
            }
            .foregroundStyle(Color(.secondarySystemFill))
            .opacity(pulse ? 0.9 : 0.4)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}
