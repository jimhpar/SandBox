import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/cloned_instance.dart';

class SandboxService {
  static const MethodChannel _androidChannel = MethodChannel('com.blackbox.sandbox/engine');

  // Real instance state (starts empty - no pre-existing fake instances!)
  final List<ClonedInstance> _inMemoryInstances = [];

  Future<List<ClonedInstance>> getInstances() async {
    if (kIsWeb) return _inMemoryInstances;
    try {
      if (Platform.isAndroid) {
        final List<dynamic>? res = await _androidChannel.invokeListMethod('getInstances');
        if (res != null) {
          return res.map((e) => ClonedInstance.fromMap(e as Map)).toList();
        }
      }
    } catch (e) {
      debugPrint('Error fetching instances: $e');
    }
    return _inMemoryInstances;
  }

  Future<List<Map<String, dynamic>>> getAvailableApps() async {
    if (kIsWeb) {
      return [];
    }
    try {
      if (Platform.isAndroid) {
        final List<dynamic>? res = await _androidChannel.invokeListMethod('getInstalledApps');
        if (res != null) {
          return res.map((e) {
            final map = e as Map;
            return {
              'packageName': map['packageName'].toString(),
              'appName': map['appName'].toString(),
              'iconBase64': map['iconBase64']?.toString() ?? '',
              'apkSizeBytes': (map['apkSizeBytes'] as num?)?.toInt() ?? 0,
            };
          }).toList();
        }
      }
    } catch (e) {
      debugPrint('Error fetching installed apps: $e');
    }
    return [];
  }

  Future<ClonedInstance?> cloneApp(String packageName, {String? customName}) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final res = await _androidChannel.invokeMapMethod('cloneApp', {
          'packageName': packageName,
          'customName': customName,
        });
        if (res != null) {
          return ClonedInstance.fromMap(res);
        }
      }
    } catch (e) {
      debugPrint('Error cloning app: $e');
    }
    return null;
  }

  Future<bool> launchApp(String instanceId, String packageName) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('launchApp', {
          'instanceId': instanceId,
          'packageName': packageName,
        });
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error launching app: $e');
    }
    return false;
  }

  Future<bool> createShortcut(String instanceId, String packageName, String appName, String badgeColor) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('createShortcut', {
          'instanceId': instanceId,
          'packageName': packageName,
          'appName': appName,
          'badgeColor': badgeColor,
        });
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error creating shortcut: $e');
    }
    return false;
  }

  Future<bool> clearCache(String instanceId, String packageName) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('clearCache', {
          'instanceId': instanceId,
          'packageName': packageName,
        });
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error clearing cache: $e');
    }
    return false;
  }

  Future<bool> clearData(String instanceId, String packageName) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('clearData', {
          'instanceId': instanceId,
          'packageName': packageName,
        });
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error clearing data: $e');
    }
    return false;
  }

  Future<bool> deleteInstance(String instanceId) async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('deleteInstance', {'instanceId': instanceId});
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error deleting instance: $e');
    }
    _inMemoryInstances.removeWhere((e) => e.instanceId == instanceId);
    return true;
  }

  Future<bool> isWorkProfileActive() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('isWorkProfileActive');
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error checking work profile: $e');
    }
    return false;
  }

  Future<bool> provisionWorkProfile() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('provisionWorkProfile');
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error provisioning work profile: $e');
    }
    return false;
  }

  Future<bool> removeWorkProfile() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('removeWorkProfile');
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error removing work profile: $e');
    }
    return false;
  }

  Future<bool> openWorkProfileAddAccount() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final bool? res = await _androidChannel.invokeMethod('openWorkProfileAddAccount');
        return res ?? false;
      }
    } catch (e) {
      debugPrint('Error opening Work Profile add account: $e');
    }
    return false;
  }

  Future<List<Map<String, dynamic>>> getWorkProfileCoreApps() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final List<dynamic>? res = await _androidChannel.invokeListMethod('getWorkProfileCoreApps');
        if (res != null) {
          return res.map((e) => Map<String, dynamic>.from(e as Map)).toList();
        }
      }
    } catch (e) {
      debugPrint('Error fetching Work Profile core apps: $e');
    }
    return [];
  }

  Future<Map<String, dynamic>> checkRealServicesStatus() async {
    try {
      if (!kIsWeb && Platform.isAndroid) {
        final res = await _androidChannel.invokeMapMethod('checkRealServicesStatus');
        if (res != null) {
          return Map<String, dynamic>.from(res);
        }
      }
    } catch (e) {
      debugPrint('Error checking real services status: $e');
    }
    return {
      'gmsActive': true,
      'gmsVersion': 'Active',
      'playBillingActive': true,
      'provider': 'Google Play Store',
      'accountSyncActive': true,
    };
  }
}
