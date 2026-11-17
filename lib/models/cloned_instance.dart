class ClonedInstance {
  final String instanceId;
  final String packageName;
  final String appName;
  final String fakeAndroidId;
  final String fakeDeviceModel;
  final bool playBillingEnabled;
  final int storageUsageBytes;
  final String iconBase64;
  final bool isPrimary;
  final String badgeColor;
  final String clonedPackageName;

  ClonedInstance({
    required this.instanceId,
    required this.packageName,
    required this.appName,
    required this.fakeAndroidId,
    required this.fakeDeviceModel,
    this.playBillingEnabled = true,
    this.storageUsageBytes = 0,
    this.iconBase64 = '',
    this.isPrimary = true,
    this.badgeColor = '#FF6D00',
    this.clonedPackageName = '',
  });

  bool get isStandaloneClone => clonedPackageName.isNotEmpty && clonedPackageName != packageName;

  factory ClonedInstance.fromMap(Map<dynamic, dynamic> map) {
    final pkg = map['packageName'] as String? ?? '';
    return ClonedInstance(
      instanceId: map['instanceId'] as String? ?? '',
      packageName: pkg,
      appName: map['appName'] as String? ?? '',
      fakeAndroidId: map['fakeAndroidId'] as String? ?? 'N/A',
      fakeDeviceModel: map['fakeDeviceModel'] as String? ?? 'Pixel 9 Pro',
      playBillingEnabled: map['playBillingEnabled'] as bool? ?? true,
      storageUsageBytes: (map['storageUsageBytes'] as num?)?.toInt() ?? 0,
      iconBase64: map['iconBase64'] as String? ?? '',
      isPrimary: map['isPrimary'] as bool? ?? true,
      badgeColor: map['badgeColor'] as String? ?? '#FF6D00',
      clonedPackageName: map['clonedPackageName'] as String? ?? pkg,
    );
  }

  ClonedInstance copyWith({
    String? instanceId,
    String? packageName,
    String? appName,
    String? fakeAndroidId,
    String? fakeDeviceModel,
    bool? playBillingEnabled,
    int? storageUsageBytes,
    String? iconBase64,
    bool? isPrimary,
    String? badgeColor,
  }) {
    return ClonedInstance(
      instanceId: instanceId ?? this.instanceId,
      packageName: packageName ?? this.packageName,
      appName: appName ?? this.appName,
      fakeAndroidId: fakeAndroidId ?? this.fakeAndroidId,
      fakeDeviceModel: fakeDeviceModel ?? this.fakeDeviceModel,
      playBillingEnabled: playBillingEnabled ?? this.playBillingEnabled,
      storageUsageBytes: storageUsageBytes ?? this.storageUsageBytes,
      iconBase64: iconBase64 ?? this.iconBase64,
      isPrimary: isPrimary ?? this.isPrimary,
      badgeColor: badgeColor ?? this.badgeColor,
    );
  }
}
