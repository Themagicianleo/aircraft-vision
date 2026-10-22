import SwiftUI

@main
struct AircraftVisionApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if model.showingResult {
                    ResultScreen(model: model)
                } else {
                    CameraScreen(model: model)
                }
            }
            .tint(Theme.accent)
        }
    }
}
