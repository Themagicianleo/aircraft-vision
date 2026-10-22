import SwiftUI
import UIKit

/// The same aviation palette as the Android app: navy sky, sky-blue accent, runway amber.
enum Theme {
    static let accent = dynamic(light: 0x0B63B6, dark: 0x8CCBFF)
    static let premium = dynamic(light: 0xE59A1F, dark: 0xFFB547)
    static let confidenceHigh = Color(hex: 0x2EB872)
    static let confidenceMid = Color(hex: 0xFFB547)
    static let confidenceLow = Color(hex: 0xE5534B)

    static func confidenceColor(_ c: Float) -> Color {
        c >= 0.7 ? confidenceHigh : c >= 0.4 ? confidenceMid : confidenceLow
    }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(hex: UInt32) { self.init(uiColor: UIColor(hex: hex)) }
}

/// Small grey capitals used for section labels.
struct CapsLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(1.2)
            .foregroundStyle(.secondary)
    }
}

/// A round translucent icon button floating over the camera or photo.
struct GlassButton: View {
    let systemName: String
    var size: CGFloat = 44
    var label: String = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(.black.opacity(0.35), in: Circle())
        }
        .accessibilityLabel(label)
    }
}

/// Rounded corner brackets around `rect`, like a viewfinder.
func drawCornerBrackets(_ ctx: inout GraphicsContext, in rect: CGRect, color: Color = .white) {
    let short = min(rect.width, rect.height)
    let arm = short * 0.18
    let r = short * 0.07
    var path = Path()
    // top-left
    path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
    path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
    path.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
    path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))
    // top-right
    path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
    path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
    path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
    path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))
    // bottom-right
    path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
    path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
    path.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
    path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))
    // bottom-left
    path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
    path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
    path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
    path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))
    ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
}
