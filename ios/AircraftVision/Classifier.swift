import CoreGraphics
import Foundation
import OnnxRuntimeBindings
import UIKit

struct AircraftLabel: Hashable {
    let name: String
    let family: String
    let manufacturer: String
}

struct Prediction: Identifiable {
    let id = UUID()
    let label: AircraftLabel
    let confidence: Float
    /// Probability summed over every variant in this label's family (e.g. all Boeing 737s).
    let familyConfidence: Float
    /// How many variants the model knows in this family.
    let familySize: Int
}

/// A box in image coordinates normalised to 0...1.
struct CropBox: Equatable {
    var left: CGFloat
    var top: CGFloat
    var right: CGFloat
    var bottom: CGFloat

    /// The centre square: what the model sees when nobody draws a box.
    static func centreSquare(width: Int, height: Int) -> CropBox {
        let w = CGFloat(width), h = CGFloat(height)
        let side = min(w, h)
        let x = (w - side) / 2 / w
        let y = (h - side) / 2 / h
        return CropBox(left: x, top: y, right: 1 - x, bottom: 1 - y)
    }
}

enum ClassifierError: LocalizedError {
    case modelMissing
    case badImage
    case badOutput

    var errorDescription: String? {
        switch self {
        case .modelMissing: return "Model not found. Put aircraft.onnx and labels.json in ios/Model and rebuild."
        case .badImage: return "Couldn't read that photo."
        case .badOutput: return "The model returned something unexpected."
        }
    }
}

/// Cuts `box` out of `image`, first widening it if needed so its aspect ratio stays within what
/// training's random crops covered (about 3:5 to 5:3); a squashed sliver would confuse the model.
func cropRegion(_ image: CGImage, _ box: CropBox) -> CGImage? {
    let w = CGFloat(image.width), h = CGFloat(image.height)
    var l = box.left * w, t = box.top * h, r = box.right * w, b = box.bottom * h
    let maxAspect: CGFloat = 1.66
    if (r - l) / (b - t) > maxAspect {
        let grow = ((r - l) / maxAspect - (b - t)) / 2
        t = max(0, t - grow)
        b = min(h, b + grow)
    } else if (b - t) / (r - l) > maxAspect {
        let grow = ((b - t) / maxAspect - (r - l)) / 2
        l = max(0, l - grow)
        r = min(w, r + grow)
    }
    return image.cropping(to: CGRect(x: l, y: t, width: r - l, height: b - t).integral)
}

/// An upright (EXIF orientation applied) CGImage, capped at `maxSide` px so huge photos don't exhaust memory.
func uprightImage(_ image: UIImage, maxSide: CGFloat = 1600) -> CGImage? {
    let scale = min(1, maxSide / max(image.size.width, image.size.height))
    let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    return UIGraphicsImageRenderer(size: size, format: format).image { _ in
        image.draw(in: CGRect(origin: .zero, size: size))
    }.cgImage
}

/// Runs the exported ONNX model entirely on the phone. Same model and preprocessing as the
/// Android app: the region is resized to inputSize x inputSize, RGB floats in 0...1, planar;
/// normalisation and softmax are inside the model.
final class Classifier: @unchecked Sendable {
    let labels: [AircraftLabel]
    let inputSize: Int
    private let env: ORTEnv
    private let session: ORTSession
    private let inputName: String
    private let outputName: String
    private let familySizes: [String: Int]

    init() throws {
        guard let modelURL = Bundle.main.url(forResource: "aircraft", withExtension: "onnx"),
              let labelsURL = Bundle.main.url(forResource: "labels", withExtension: "json") else {
            throw ClassifierError.modelMissing
        }
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: labelsURL)) as? [String: Any] ?? [:]
        inputSize = json["img_size"] as? Int ?? 256
        labels = (json["classes"] as? [[String: Any]] ?? []).map {
            AircraftLabel(
                name: $0["name"] as? String ?? "?",
                family: $0["family"] as? String ?? "",
                manufacturer: $0["manufacturer"] as? String ?? ""
            )
        }
        familySizes = Dictionary(grouping: labels, by: \.family).mapValues(\.count)

        env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(4)
        try options.setGraphOptimizationLevel(ORTGraphOptimizationLevel.all)
        session = try ORTSession(env: env, modelPath: modelURL.path, sessionOptions: options)
        inputName = try session.inputNames().first ?? "image"
        outputName = try session.outputNames().first ?? "probs"
    }

    func classify(_ image: CGImage, topK: Int = 5) throws -> [Prediction] {
        let s = inputSize
        let input = try planarRGB(image, size: s)
        let data = input.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Float>.size) }
        let tensor = try ORTValue(
            tensorData: data,
            elementType: ORTTensorElementDataType.float,
            shape: [1, 3, NSNumber(value: s), NSNumber(value: s)]
        )
        let outputs = try session.run(withInputs: [inputName: tensor], outputNames: [outputName], runOptions: nil)
        guard let output = outputs[outputName] else { throw ClassifierError.badOutput }
        let raw = try output.tensorData() as Data
        let probs: [Float] = raw.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        guard probs.count == labels.count else { throw ClassifierError.badOutput }

        var familyTotals: [String: Float] = [:]
        for (i, label) in labels.enumerated() { familyTotals[label.family, default: 0] += probs[i] }
        return probs.indices
            .sorted { probs[$0] > probs[$1] }
            .prefix(topK)
            .map { i in
                Prediction(
                    label: labels[i],
                    confidence: probs[i],
                    familyConfidence: familyTotals[labels[i].family] ?? probs[i],
                    familySize: familySizes[labels[i].family] ?? 1
                )
            }
    }

    /// Resizes to size x size with high-quality filtering and returns planar RGB floats in 0...1.
    private func planarRGB(_ image: CGImage, size s: Int) throws -> [Float] {
        var rgba = [UInt8](repeating: 0, count: s * s * 4)
        let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: s, height: s, bitsPerComponent: 8, bytesPerRow: s * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: s, height: s))
            return true
        }
        guard drawn else { throw ClassifierError.badImage }
        let plane = s * s
        var out = [Float](repeating: 0, count: 3 * plane)
        for i in 0..<plane {
            out[i] = Float(rgba[i * 4]) / 255
            out[plane + i] = Float(rgba[i * 4 + 1]) / 255
            out[2 * plane + i] = Float(rgba[i * 4 + 2]) / 255
        }
        return out
    }
}
