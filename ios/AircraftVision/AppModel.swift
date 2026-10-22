import CoreGraphics
import Foundation
import Observation

/// App state: the photo being identified, the box around the area to analyse, and the result.
@MainActor
@Observable
final class AppModel {
    var image: CGImage?
    /// The area being identified; starts as the centre square, then wherever the user drags it.
    var crop: CropBox?
    var predictions: [Prediction] = []
    var busy = false
    var inferenceMs: Int?
    var error: String?
    var supportedTypes: [AircraftLabel] = []

    var showingResult: Bool { image != nil || busy || !predictions.isEmpty }

    private var classifier: Classifier?
    private var task: Task<Void, Never>?

    init() {
        // Load the model up front: the first photo is analysed faster, and the type list is ready.
        Task { _ = await loadClassifier() }
    }

    /// A frame frozen from the camera, or a photo from the library.
    func analyse(_ photo: CGImage) {
        image = photo
        crop = .centreSquare(width: photo.width, height: photo.height)
        run()
    }

    /// The user moved or resized the box: identify what's inside it now.
    func setCrop(_ box: CropBox) {
        crop = box
        run()
    }

    /// Back to the camera.
    func clear() {
        task?.cancel()
        image = nil
        crop = nil
        predictions = []
        busy = false
        inferenceMs = nil
        error = nil
    }

    private func run() {
        guard let photo = image, let box = crop else { return }
        task?.cancel()
        busy = true
        error = nil
        predictions = []
        inferenceMs = nil
        task = Task {
            guard let clf = await loadClassifier() else {
                busy = false
                return
            }
            let start = Date()
            do {
                let result = try await Task.detached(priority: .userInitiated) { () throws -> [Prediction] in
                    guard let region = cropRegion(photo, box) else { throw ClassifierError.badImage }
                    return try clf.classify(region)
                }.value
                guard !Task.isCancelled else { return }
                predictions = result
                inferenceMs = Int(Date().timeIntervalSince(start) * 1000)
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
            }
            busy = false
        }
    }

    private func loadClassifier() async -> Classifier? {
        if let classifier { return classifier }
        do {
            let loaded = try await Task.detached(priority: .userInitiated) { try Classifier() }.value
            classifier = loaded
            supportedTypes = loaded.labels
            return loaded
        } catch {
            self.error = error.localizedDescription
            return nil
        }
    }
}
