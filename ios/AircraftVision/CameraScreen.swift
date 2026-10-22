import AVFoundation
import CoreImage
import PhotosUI
import SwiftUI

/// Runs the back camera and keeps the latest frame, so the shutter can freeze exactly what's on screen.
final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "camera")
    private let lock = NSLock()
    private var latest: CVPixelBuffer?
    private let ciContext = CIContext()
    private var device: AVCaptureDevice?
    private var configured = false

    func start() {
        queue.async {
            if !self.configured { self.configure() }
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    /// The most recent frame, upright.
    func snapshot() -> CGImage? {
        lock.lock()
        let buffer = latest
        lock.unlock()
        guard let buffer else { return nil }
        let image = CIImage(cvPixelBuffer: buffer)
        return ciContext.createCGImage(image, from: image.extent)
    }

    func setTorch(_ on: Bool) {
        queue.async {
            guard let device = self.device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        }
    }

    func setZoom(_ factor: CGFloat) {
        queue.async {
            guard let device = self.device, (try? device.lockForConfiguration()) != nil else { return }
            device.videoZoomFactor = max(1, min(factor, min(device.activeFormat.videoMaxZoomFactor, 10)))
            device.unlockForConfiguration()
        }
    }

    var zoom: CGFloat { device?.videoZoomFactor ?? 1 }

    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .hd1920x1080
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        self.device = device
        if (try? device.lockForConfiguration()) != nil {
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            device.unlockForConfiguration()
        }
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        // Portrait frames, matching the portrait-only UI.
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        configured = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lock.lock()
        latest = buffer
        lock.unlock()
    }
}

private final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
}

struct CameraScreen: View {
    let model: AppModel
    @State private var camera = CameraController()
    @State private var authorized = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var denied = AVCaptureDevice.authorizationStatus(for: .video) == .denied
    @State private var torch = false
    @State private var zoomAtPinchStart: CGFloat = 1
    @State private var pickedItem: PhotosPickerItem?
    @State private var showTypes = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if authorized {
                CameraPreview(session: camera.session)
                    .ignoresSafeArea()
                    .gesture(
                        MagnifyGesture()
                            .onChanged { value in camera.setZoom(zoomAtPinchStart * value.magnification) }
                            .onEnded { _ in zoomAtPinchStart = camera.zoom }
                    )
                Canvas { ctx, size in
                    let side = min(size.width, size.height) * 0.82
                    drawCornerBrackets(&ctx, in: CGRect(x: (size.width - side) / 2, y: (size.height - side) / 2, width: side, height: side))
                }
                .ignoresSafeArea()
                .allowsHitTesting(false)
            } else {
                permissionPrompt
            }

            VStack {
                topBar
                Spacer()
                bottomControls
            }
        }
        .onAppear(perform: startCamera)
        .onDisappear { camera.stop() }
        .onChange(of: torch) { _, on in camera.setTorch(on) }
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let photo = UIImage(data: data), let image = uprightImage(photo) {
                    model.analyse(image)
                }
                pickedItem = nil
            }
        }
        .sheet(isPresented: $showTypes) { SupportedTypesView(types: model.supportedTypes) }
    }

    private var topBar: some View {
        HStack {
            GlassButton(systemName: torch ? "bolt.fill" : "bolt.slash.fill", label: "Torch") { torch.toggle() }
                .opacity(authorized ? 1 : 0.4)
                .disabled(!authorized)
            Spacer()
            Label("Spotter", systemImage: "airplane")
                .font(.headline)
                .foregroundStyle(.white)
            Spacer()
            GlassButton(systemName: "list.bullet", label: "Aircraft it knows") { showTypes = true }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    private var bottomControls: some View {
        VStack(spacing: 20) {
            if let error = model.error {
                Text(error)
                    .font(.callout)
                    .padding(12)
                    .background(.red.opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(.white)
                    .padding(.horizontal)
            }
            Text(authorized ? "Point at an aircraft and tap search" : "Or pick a photo from your library")
                .font(.callout)
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.black.opacity(0.45), in: Capsule())
            HStack {
                PhotosPicker(selection: $pickedItem, matching: .images) {
                    Image(systemName: "photo.on.rectangle")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 52, height: 52)
                        .background(.black.opacity(0.35), in: Circle())
                }
                .accessibilityLabel("Choose from library")
                Spacer()
                Button {
                    if let frame = camera.snapshot() { model.analyse(frame) }
                } label: {
                    ZStack {
                        Circle().stroke(.white, lineWidth: 4).frame(width: 84, height: 84)
                        Circle().fill(.white).frame(width: 68, height: 68)
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(Color(hex: 0x0B63B6))
                    }
                }
                .disabled(!authorized)
                .opacity(authorized ? 1 : 0.4)
                .accessibilityLabel("Identify")
                Spacer()
                Color.clear.frame(width: 52, height: 52)  // balances the library button
            }
            .padding(.horizontal, 36)
            Text("Aircraft")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.black)
                .padding(.horizontal, 18)
                .padding(.vertical, 8)
                .background(.white, in: Capsule())
        }
        .padding(.bottom, 12)
    }

    private var permissionPrompt: some View {
        VStack(spacing: 14) {
            Image(systemName: "camera.viewfinder").font(.system(size: 48)).foregroundStyle(.white)
            Text("Identify aircraft with your camera").font(.title3.bold()).foregroundStyle(.white)
            Text("Allow camera access to point at a plane and identify it. Photos never leave your phone.")
                .font(.callout)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.75))
            if denied {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button("Allow camera", action: startCamera).buttonStyle(.borderedProminent)
            }
        }
        .padding(32)
    }

    private func startCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
            camera.start()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    authorized = granted
                    denied = !granted
                    if granted { camera.start() }
                }
            }
        default:
            denied = true
        }
    }
}
