import 'dart:convert';
import 'package:flutter/material.dart';
import '../models/cloned_instance.dart';
import '../services/sandbox_service.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final SandboxService _service = SandboxService();
  List<ClonedInstance> _instances = [];
  bool _isLoading = true;
  Map<String, dynamic> _servicesStatus = {};

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    final instances = await _service.getInstances();
    final services = await _service.checkRealServicesStatus();
    if (!mounted) return;
    setState(() {
      _instances = instances;
      _servicesStatus = services;
      _isLoading = false;
    });
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return "0 MB";
    final mb = bytes / (1024 * 1024);
    if (mb < 1000) return "${mb.toStringAsFixed(1)} MB";
    return "${(mb / 1024).toStringAsFixed(2)} GB";
  }

  void _showCloneModal() async {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator(color: Color(0xFF00E5FF))),
    );

    final availableApps = await _service.getAvailableApps();
    if (!mounted) return;
    Navigator.pop(context); // Dismiss progress

    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF161926),
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            return Container(
              height: MediaQuery.of(context).size.height * 0.75,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        "Clone Installed App",
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close, color: Colors.grey),
                        onPressed: () => Navigator.pop(context),
                      )
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    "Select an app to permanently clone into Sandbox with an isolated environment and home screen shortcut.",
                    style: TextStyle(fontSize: 13, color: Colors.grey[400]),
                  ),
                  const SizedBox(height: 16),
                  Expanded(
                    child: availableApps.isEmpty
                        ? const Center(
                            child: Text(
                              "No cloneable apps found",
                              style: TextStyle(color: Colors.grey),
                            ),
                          )
                        : ListView.separated(
                            itemCount: availableApps.length,
                            separatorBuilder: (_, _) => const Divider(color: Color(0xFF232738), height: 1),
                            itemBuilder: (context, index) {
                              final app = availableApps[index];
                              final iconBase64 = app['iconBase64'] as String? ?? '';

                              return ListTile(
                                contentPadding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
                                leading: ClipRRect(
                                  borderRadius: BorderRadius.circular(10),
                                  child: iconBase64.isNotEmpty
                                      ? Image.memory(
                                          base64Decode(iconBase64),
                                          width: 44,
                                          height: 44,
                                          fit: BoxFit.cover,
                                          errorBuilder: (_, _, _) => _fallbackAppIcon(),
                                        )
                                      : _fallbackAppIcon(),
                                ),
                                title: Text(
                                  app['appName'] ?? '',
                                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600, fontSize: 15),
                                ),
                                subtitle: Text(
                                  app['packageName'] ?? '',
                                  style: TextStyle(color: Colors.grey[500], fontSize: 12),
                                ),
                                trailing: ElevatedButton(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: const Color(0xFF00E5FF),
                                    foregroundColor: Colors.black,
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                                  ),
                                  onPressed: () async {
                                    final scaffoldMessenger = ScaffoldMessenger.of(context);
                                    Navigator.pop(context);
                                    final newClone = await _service.cloneApp(
                                      app['packageName']!,
                                    );
                                    if (!mounted) return;
                                    _loadData();
                                    if (newClone != null) {
                                      scaffoldMessenger.showSnackBar(
                                        SnackBar(
                                          backgroundColor: const Color(0xFF1E2235),
                                          content: Text(
                                            newClone.isPrimary
                                                ? "Cloned ${app['appName']} (Primary - Orange SB Badge)"
                                                : "Cloned ${app['appName']} (Multi-Instance - Pink SB Badge)",
                                          ),
                                        ),
                                      );
                                    }
                                  },
                                  child: const Text("Clone", style: TextStyle(fontWeight: FontWeight.bold)),
                                ),
                              );
                            },
                          ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _fallbackAppIcon() {
    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: const Color(0xFF22273D),
        borderRadius: BorderRadius.circular(10),
      ),
      child: const Icon(Icons.android, color: Color(0xFF00E5FF)),
    );
  }

  void _showBillingModal() {
    final isGmsActive = _servicesStatus['gmsActive'] == true;
    final isBillingActive = _servicesStatus['playBillingActive'] == true;
    final provider = _servicesStatus['provider']?.toString() ?? 'Google Play Store';
    final gmsVersion = _servicesStatus['gmsVersion']?.toString() ?? 'Active';

    showDialog(
      context: context,
      builder: (context) {
        return AlertDialog(
          backgroundColor: const Color(0xFF161926),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          title: Row(
            children: const [
              Icon(Icons.verified, color: Color(0xFF00FF88)),
              SizedBox(width: 10),
              Text("Google Play & Billing", style: TextStyle(color: Colors.white)),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildInfoRow("Google Play Services:", isGmsActive ? "Active ($gmsVersion)" : "Unavailable", isGmsActive ? Colors.greenAccent : Colors.redAccent),
              _buildInfoRow("Play Store Provider:", provider, Colors.white70),
              _buildInfoRow("Play Billing Bridge:", isBillingActive ? "Active & Bound" : "Unavailable", isBillingActive ? Colors.greenAccent : Colors.redAccent),
              _buildInfoRow("Account Sync:", isGmsActive ? "Active" : "Disabled", isGmsActive ? Colors.greenAccent : Colors.grey),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF1F2438),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFF00FF88).withValues(alpha: 0.3)),
                ),
                child: const Text(
                  "Cloned apps have native access to Google Play Services for push notifications, Google sign-in, and in-app purchase billing.",
                  style: TextStyle(fontSize: 12, color: Colors.white70),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text("Close", style: TextStyle(color: Color(0xFF00E5FF))),
            )
          ],
        );
      },
    );
  }

  Widget _buildInfoRow(String label, String value, Color valueColor) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(color: Colors.grey, fontSize: 13)),
          Text(value, style: TextStyle(color: valueColor, fontWeight: FontWeight.bold, fontSize: 13)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isGmsActive = _servicesStatus['gmsActive'] == true;
    final isBillingActive = _servicesStatus['playBillingActive'] == true;

    return Scaffold(
      backgroundColor: const Color(0xFF0D0F18),
      appBar: AppBar(
        backgroundColor: const Color(0xFF131622),
        elevation: 0,
        title: Row(
          children: [
            Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: const Color(0xFF00E5FF).withValues(alpha: 0.3)),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(9),
                child: Image.asset(
                  'icon/app_icon.png',
                  width: 36,
                  height: 36,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => Image.asset(
                    'assets/icon/app_icon.png',
                    width: 36,
                    height: 36,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: const Color(0xFF00E5FF).withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(9),
                      ),
                      child: const Icon(Icons.shield_outlined, color: Color(0xFF00E5FF), size: 22),
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  "SANDBOX",
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 2.0,
                    fontSize: 18,
                  ),
                ),
                Text(
                  "v2.0.2 • Blackbox THC",
                  style: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: "Google Services & Billing Status",
            icon: const Icon(Icons.verified, color: Color(0xFF00FF88)),
            onPressed: _showBillingModal,
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator(color: Color(0xFF00E5FF)))
          : RefreshIndicator(
              onRefresh: _loadData,
              color: const Color(0xFF00E5FF),
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // System Status Card (Google Play Services & Billing only)
                    _buildRealServicesCard(isGmsActive, isBillingActive),
                    const SizedBox(height: 22),

                    // Cloned Apps Header
                    Text(
                      "CLONED APPS (${_instances.length})",
                      style: const TextStyle(
                        color: Colors.grey,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.2,
                        fontSize: 13,
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Cloned App Cards
                    if (_instances.isEmpty)
                      Center(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 60),
                          child: Column(
                            children: [
                              Icon(Icons.layers_clear_outlined, color: Colors.grey[600], size: 64),
                              const SizedBox(height: 16),
                              const Text(
                                "No Cloned Apps Yet",
                                style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                "Tap '+ CLONE APP' below to clone and isolate an app or game.",
                                textAlign: TextAlign.center,
                                style: TextStyle(color: Colors.grey[400], fontSize: 13),
                              ),
                            ],
                          ),
                        ),
                      )
                    else
                      ..._instances.map((inst) => _buildClonedAppCard(inst)),
                  ],
                ),
              ),
            ),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: const Color(0xFF00E5FF),
        foregroundColor: Colors.black,
        icon: const Icon(Icons.add, fontWeight: FontWeight.bold),
        label: const Text(
          "CLONE APP",
          style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.0),
        ),
        onPressed: _showCloneModal,
      ),
    );
  }

  Widget _buildRealServicesCard(bool isGmsActive, bool isBillingActive) {
    final isWorkProfileActive = _servicesStatus['workProfileActive'] == true;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF141724),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF262C42)),
      ),
      child: Column(
        children: [
          // Work Profile Dual Space Banner
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: isWorkProfileActive
                  ? const Color(0xFF00FF88).withValues(alpha: 0.1)
                  : const Color(0xFFFF6D00).withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: isWorkProfileActive ? const Color(0xFF00FF88) : const Color(0xFFFF6D00),
                width: 1,
              ),
            ),
            child: Row(
              children: [
                Icon(
                  isWorkProfileActive ? Icons.verified_user : Icons.security,
                  color: isWorkProfileActive ? const Color(0xFF00FF88) : const Color(0xFFFF6D00),
                  size: 24,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        isWorkProfileActive
                            ? "Work Profile Dual Space Active"
                            : "Work Profile Setup Required",
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 13,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        isWorkProfileActive
                            ? "Official 2nd Google Play Store & GMS Isolation Online."
                            : "Enable official 2nd Google Play Store & GMS isolation for fresh accounts.",
                        style: TextStyle(color: Colors.grey[400], fontSize: 11),
                      ),
                    ],
                  ),
                ),
                if (!isWorkProfileActive)
                  ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFFF6D00),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                    ),
                    onPressed: () async {
                      final ok = await _service.provisionWorkProfile();
                      if (ok) {
                        _loadData();
                      }
                    },
                    child: const Text("Activate", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _buildStatusPill(
                  title: "Google Play Services",
                  statusText: isGmsActive ? "Active" : "Unavailable",
                  isActive: isGmsActive,
                  icon: Icons.cloud_done,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildStatusPill(
                  title: "Play Billing Bridge",
                  statusText: isBillingActive ? "Active" : "Unavailable",
                  isActive: isBillingActive,
                  icon: Icons.payment,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatusPill({
    required String title,
    required String statusText,
    required bool isActive,
    required IconData icon,
  }) {
    final color = isActive ? const Color(0xFF00FF88) : Colors.redAccent;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF0F111B),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: color, size: 16),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(color: Colors.grey[400], fontSize: 11, fontWeight: FontWeight.w500),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Container(
                width: 7,
                height: 7,
                decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              ),
              const SizedBox(width: 6),
              Text(
                statusText,
                style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 13),
              ),
            ],
          )
        ],
      ),
    );
  }

  Widget _buildClonedAppCard(ClonedInstance inst) {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF141724),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF22273B)),
      ),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              // Badged App Icon (Orange for Primary, Pink for Multi)
              _buildBadgedIcon(inst.iconBase64, inst.badgeColor),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            inst.appName,
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                              fontSize: 15,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: inst.isPrimary ? const Color(0xFFFF6D00).withValues(alpha: 0.2) : const Color(0xFFFF2D75).withValues(alpha: 0.2),
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(
                              color: inst.isPrimary ? const Color(0xFFFF6D00) : const Color(0xFFFF2D75),
                              width: 1,
                            ),
                          ),
                          child: Text(
                            inst.isPrimary ? "PRIMARY" : "MULTI",
                            style: TextStyle(
                              color: inst.isPrimary ? const Color(0xFFFF6D00) : const Color(0xFFFF2D75),
                              fontSize: 9,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(
                      inst.packageName,
                      style: TextStyle(color: Colors.grey[400], fontSize: 12),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              // 3-Dot Context Menu
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert, color: Colors.grey),
                color: const Color(0xFF1F2438),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                onSelected: (value) async {
                  final messenger = ScaffoldMessenger.of(context);
                  if (value == 'shortcut') {
                    final ok = await _service.createShortcut(inst.instanceId, inst.packageName, inst.appName, inst.badgeColor);
                    messenger.showSnackBar(
                      SnackBar(
                        backgroundColor: const Color(0xFF1E2235),
                        content: Text(ok ? "Home screen shortcut requested!" : "Could not create shortcut"),
                      ),
                    );
                  } else if (value == 'clear_cache') {
                    await _service.clearCache(inst.instanceId, inst.packageName);
                    _loadData();
                    messenger.showSnackBar(
                      SnackBar(backgroundColor: const Color(0xFF1E2235), content: Text("Cleared cache for ${inst.appName}")),
                    );
                  } else if (value == 'clear_data') {
                    final confirm = await _showConfirmDialog("Clear All Data", "Are you sure you want to wipe data for ${inst.appName}?");
                    if (confirm == true) {
                      await _service.clearData(inst.instanceId, inst.packageName);
                      _loadData();
                      messenger.showSnackBar(
                        SnackBar(backgroundColor: const Color(0xFF1E2235), content: Text("Wiped data for ${inst.appName}")),
                      );
                    }
                  } else if (value == 'delete') {
                    final confirm = await _showConfirmDialog("Delete Cloned App", "Permanently remove ${inst.appName} from Sandbox?");
                    if (confirm == true) {
                      await _service.deleteInstance(inst.instanceId);
                      _loadData();
                      messenger.showSnackBar(
                        SnackBar(backgroundColor: const Color(0xFF1E2235), content: Text("Deleted ${inst.appName}")),
                      );
                    }
                  }
                },
                itemBuilder: (context) => [
                  const PopupMenuItem(
                    value: 'shortcut',
                    child: Row(
                      children: [
                        Icon(Icons.add_to_home_screen, color: Color(0xFFFF6D00), size: 18),
                        SizedBox(width: 10),
                        Text("Add to Home Screen", style: TextStyle(color: Colors.white)),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'clear_cache',
                    child: Row(
                      children: [
                        Icon(Icons.cleaning_services, color: Color(0xFF00E5FF), size: 18),
                        SizedBox(width: 10),
                        Text("Clear Cache", style: TextStyle(color: Colors.white)),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'clear_data',
                    child: Row(
                      children: [
                        Icon(Icons.delete_sweep, color: Colors.amberAccent, size: 18),
                        SizedBox(width: 10),
                        Text("Clear All Data", style: TextStyle(color: Colors.white)),
                      ],
                    ),
                  ),
                  const PopupMenuDivider(height: 1),
                  const PopupMenuItem(
                    value: 'delete',
                    child: Row(
                      children: [
                        Icon(Icons.delete_forever, color: Colors.redAccent, size: 18),
                        SizedBox(width: 10),
                        Text("Delete App", style: TextStyle(color: Colors.redAccent)),
                      ],
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Divider(color: Color(0xFF1E2235), height: 1),
          const SizedBox(height: 10),

          // Metadata row (Storage & Device profile)
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _buildMetaTag(Icons.storage, _formatBytes(inst.storageUsageBytes)),
              _buildMetaTag(Icons.smartphone, inst.fakeDeviceModel),
            ],
          ),
          const SizedBox(height: 12),

          // Primary Action: OPEN APP
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF00E5FF),
                foregroundColor: Colors.black,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              icon: const Icon(Icons.launch, size: 20, color: Colors.black),
              label: const Text(
                "OPEN APP",
                style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.0, fontSize: 14),
              ),
              onPressed: () async {
                final success = await _service.launchApp(inst.instanceId, inst.packageName);
                if (!success && mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      backgroundColor: const Color(0xFF1E2235),
                      content: Text("Could not open ${inst.appName}"),
                    ),
                  );
                }
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBadgedIcon(String iconBase64, String badgeColorHex) {
    Color badgeColor = const Color(0xFFFF6D00); // Default Orange
    try {
      badgeColor = Color(int.parse(badgeColorHex.replaceFirst('#', '0xFF')));
    } catch (_) {}

    return SizedBox(
      width: 50,
      height: 50,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          // Main app icon
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: iconBase64.isNotEmpty
                ? Image.memory(
                    base64Decode(iconBase64),
                    width: 50,
                    height: 50,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => _fallbackAppIcon(),
                  )
                : _fallbackAppIcon(),
          ),
          // Circular badge with "SB" at bottom-left (Orange for Primary, Pink for Multi)
          Positioned(
            bottom: -2,
            left: -2,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
              decoration: BoxDecoration(
                color: badgeColor,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.white, width: 1.5),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.5),
                    blurRadius: 4,
                  )
                ],
              ),
              child: const Text(
                "SB",
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w900,
                  fontSize: 9,
                  letterSpacing: 0.5,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<bool?> _showConfirmDialog(String title, String message) {
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF161926),
        title: Text(title, style: const TextStyle(color: Colors.white)),
        content: Text(message, style: const TextStyle(color: Colors.white70)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text("Confirm", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  Widget _buildMetaTag(IconData icon, String label) {
    return Row(
      children: [
        Icon(icon, size: 14, color: Colors.grey[400]),
        const SizedBox(width: 4),
        Text(
          label,
          style: TextStyle(
            color: Colors.grey[400],
            fontSize: 11,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }
}
