import Foundation

/**
 * IPAPatcherEngine
 * Power User Cloner Engine for Sandbox v2.0.1 (Blackbox THC) on iOS.
 * Handles IPA bundle parsing, CFBundleIdentifier mutation, Keychain isolation,
 * and preparation for AltStore / SideStore / TrollStore deployment.
 */
class IPAPatcherEngine {

    static let shared = IPAPatcherEngine()

    private init() {}

    /**
     * Inspects target app bundle details and prepares cloning metadata.
     */
    func analyzeAppBundle(bundleId: String, appName: String) -> [String: Any] {
        let instanceSuffix = UUID().uuidString.prefix(6).lowercased()
        let newBundleId = "com.blackbox.sandbox.\(bundleId).\(instanceSuffix)"
        let clonedDisplayName = "\(appName) (Sandbox)"

        return [
            "originalBundleId": bundleId,
            "newBundleId": newBundleId,
            "clonedDisplayName": clonedDisplayName,
            "instanceId": String(instanceSuffix),
            "keychainIsolated": true,
            "appGroupSanitized": true,
            "sideStoreReady": true
        ]
    }

    /**
     * Mutates an unpacked Info.plist dictionary to isolate it from the original app.
     */
    func patchInfoPlist(originalPlist: [String: Any], newBundleId: String, newDisplayName: String) -> [String: Any] {
        var patched = originalPlist
        patched["CFBundleIdentifier"] = newBundleId
        patched["CFBundleDisplayName"] = newDisplayName
        patched["CFBundleName"] = newDisplayName

        // Strip conflicting URL schemes to prevent deep link hijacking of original app
        if var urlTypes = patched["CFBundleURLTypes"] as? [[String: Any]] {
            for i in 0..<urlTypes.count {
                if let schemes = urlTypes[i]["CFBundleURLSchemes"] as? [String] {
                    urlTypes[i]["CFBundleURLSchemes"] = schemes.map { "sandbox-\($0)" }
                }
            }
            patched["CFBundleURLTypes"] = urlTypes
        }

        return patched
    }

    /**
     * Generates sanitized entitlements for AltStore / SideStore / TrollStore re-signing.
     */
    func generateSanitizedEntitlements(teamId: String, newBundleId: String) -> [String: Any] {
        return [
            "application-identifier": "\(teamId).\(newBundleId)",
            "keychain-access-groups": ["\(teamId).\(newBundleId)"],
            "get-task-allow": true
        ]
    }
}
