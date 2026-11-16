import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {

  private let CHANNEL = "com.blackbox.sandbox/ios_cloner"

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)

    let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "SandboxIOSClonerPlugin")
    let channel = FlutterMethodChannel(name: CHANNEL, binaryMessenger: registrar.messenger())

    channel.setMethodCallHandler { (call: FlutterMethodCall, result: @escaping FlutterResult) in
      switch call.method {
      case "analyzeAppBundle":
        guard let args = call.arguments as? [String: Any],
              let bundleId = args["bundleId"] as? String,
              let appName = args["appName"] as? String else {
          result(FlutterError(code: "INVALID_ARGS", message: "bundleId and appName required", details: nil))
          return
        }
        let metadata = IPAPatcherEngine.shared.analyzeAppBundle(bundleId: bundleId, appName: appName)
        result(metadata)

      case "getPowerUserStatus":
        result([
          "engine": "Dynamic IPA Patcher & Re-signer",
          "sideloadPlatform": "AltStore / SideStore / TrollStore Ready",
          "keychainIsolation": true,
          "version": "2.0.1",
          "publisher": "Blackbox THC"
        ])

      default:
        result(FlutterMethodNotImplemented)
      }
    }
  }
}
