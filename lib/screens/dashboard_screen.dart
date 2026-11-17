import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import '../models/cloned_instance.dart';
import '../services/sandbox_service.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  final SandboxService _service = SandboxService();
  late TabController _tabController;

  List<ClonedInstance> _instances = [];
  List<Map<String, dynamic>> _coreApps = [];
  bool _isLoading = true;
  Map<String, dynamic> _servicesStatus = {};

  // Sticky Bottom Operation Progress State
  bool _isOperationVisible = false;
  bool _isOperationComplete = false;
  bool _showOpenSettingsFallback = false;
  String _operationTitle = "";
  String _operationSubtitle = "";
  double _operationProgress = 0.0;
  Color _operationColor = const Color(0xFF00E5FF);
  IconData _operationIcon = Icons.sync;
  StreamSubscription<Map<String, dynamic>>? _cloneProgressSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _tabController = TabController(length: 2, vsync: this);
    _tabController.addListener(() {
      if (mounted) setState(() {});
    });
    _cloneProgressSub = _service.cloneProgressStream.listen((event) {
      if (!mounted) return;
      final progress = (event['progress'] as num?)?.toDouble() ?? 0.0;
      final msg = event['message'] as String? ?? '';
      final done = event['done'] as bool? ?? false;
      final success = event['success'] as bool? ?? true;

      setState(() {
        _isOperationVisible = true;
        _operationProgress = progress;
        _operationSubtitle = msg;
        _isOperationComplete = done;
        if (done) {
          if (success) {
            _operationTitle = "App Cloned Successfully!";
            _operationColor = const Color(0xFF00FF88);
            _operationIcon = Icons.check_circle;
          } else {
            _operationTitle = "Cloning Failed";
            _operationColor = Colors.redAccent;
            _operationIcon = Icons.error_outline;
          }
        }
      });

      if (done) {
        _loadData();
      }
    });
    _loadData();
  }

  @override
  void dispose() {
    _cloneProgressSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _tabController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadData();
    }
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    final instances = await _service.getInstances();
    final coreApps = await _service.getWorkProfileCoreApps();
    final services = await _service.checkRealServicesStatus();
    if (!mounted) return;
    setState(() {
      _instances = instances;
      _coreApps = coreApps;
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

  void _showHudNotification(
    String message, {
    IconData icon = Icons.info_outline,
    Color color = const Color(0xFF00E5FF),
  }) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: const Color(0xFF141724),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: color, width: 1.5),
        ),
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        content: Row(
          children: [
            Icon(icon, color: color, size: 22),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                message,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ),
          ],
        ),
        duration: const Duration(seconds: 3),
      ),
    );
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
                    "Select an app to clone into Sandbox. Primary clones are provisioned locally into Work Profile with zero internet data required.",
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
                              final pkgName = app['packageName'] as String? ?? '';
                              final iconBase64 = app['iconBase64'] as String? ?? '';
                              final cloneCount = _instances.where((inst) => inst.packageName == pkgName).length;

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
                                  cloneCount > 0 ? "$pkgName ($cloneCount cloned)" : pkgName,
                                  style: TextStyle(color: Colors.grey[500], fontSize: 12),
                                ),
                                trailing: ElevatedButton(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: const Color(0xFF00E5FF),
                                    foregroundColor: Colors.black,
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                                  ),
                                  onPressed: () {
                                    Navigator.pop(context);
                                    _cloneWithProgress(app);
                                  },
                                  child: Text(
                                    cloneCount > 0 ? "Clone #${cloneCount + 1}" : "Clone",
                                    style: const TextStyle(fontWeight: FontWeight.bold),
                                  ),
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
                  "v2.1.0 • Blackbox THC",
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
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: const Color(0xFF00E5FF),
          indicatorWeight: 3,
          labelColor: const Color(0xFF00E5FF),
          unselectedLabelColor: Colors.grey[500],
          labelStyle: const TextStyle(fontWeight: FontWeight.bold, letterSpacing: 0.8, fontSize: 13),
          tabs: [
            Tab(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.apps, size: 18),
                  const SizedBox(width: 8),
                  Text("CLONED APPS (${_instances.length})"),
                ],
              ),
            ),
            Tab(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.work_outline, size: 18),
                  const SizedBox(width: 8),
                  Text("WORK SPACE (${_coreApps.length})"),
                ],
              ),
            ),
          ],
        ),
      ),
      body: Stack(
        children: [
          _isLoading
              ? const Center(child: CircularProgressIndicator(color: Color(0xFF00E5FF)))
              : TabBarView(
                  controller: _tabController,
                  children: [
                    _buildClonedAppsTab(),
                    _buildWorkSpaceCoreTab(),
                  ],
                ),
          if (_isOperationVisible)
            Positioned(
              left: 14,
              right: 14,
              bottom: 16,
              child: _buildStickyOperationBanner(),
            ),
        ],
      ),
      floatingActionButton: (!_isOperationVisible && _tabController.index == 0)
          ? FloatingActionButton.extended(
              backgroundColor: const Color(0xFF00E5FF),
              foregroundColor: Colors.black,
              icon: const Icon(Icons.add, fontWeight: FontWeight.bold),
              label: const Text(
                "CLONE APP",
                style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.0),
              ),
              onPressed: _showCloneModal,
            )
          : null,
    );
  }

  Widget _buildStickyOperationBanner() {
    final pct = (_operationProgress * 100).clamp(0, 100).toInt();
    return Material(
      elevation: 14,
      borderRadius: BorderRadius.circular(16),
      color: const Color(0xFF141724),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: _operationColor, width: 1.6),
          boxShadow: [
            BoxShadow(
              color: _operationColor.withValues(alpha: 0.2),
              blurRadius: 16,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(_operationIcon, color: _operationColor, size: 22),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    _operationTitle,
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text(
                  "$pct%",
                  style: TextStyle(
                    color: _operationColor,
                    fontWeight: FontWeight.w900,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              _operationSubtitle,
              style: TextStyle(color: Colors.grey[400], fontSize: 12),
            ),
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: _operationProgress,
                minHeight: 7,
                backgroundColor: const Color(0xFF22273B),
                valueColor: AlwaysStoppedAnimation<Color>(_operationColor),
              ),
            ),
            if (_isOperationComplete) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  if (_showOpenSettingsFallback) ...[
                    Expanded(
                      child: OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.amberAccent,
                          side: const BorderSide(color: Colors.amberAccent),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                          padding: const EdgeInsets.symmetric(vertical: 10),
                        ),
                        icon: const Icon(Icons.settings, size: 17),
                        label: const Text("SETTINGS", style: TextStyle(fontWeight: FontWeight.bold)),
                        onPressed: () => _service.openWorkProfileSettings(),
                      ),
                    ),
                    const SizedBox(width: 10),
                  ],
                  Expanded(
                    child: ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: _operationColor,
                        foregroundColor: Colors.black,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                        padding: const EdgeInsets.symmetric(vertical: 10),
                      ),
                      icon: const Icon(Icons.check, size: 18),
                      label: const Text(
                        "DONE",
                        style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.0),
                      ),
                      onPressed: () {
                        setState(() {
                          _isOperationVisible = false;
                          _showOpenSettingsFallback = false;
                        });
                      },
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _cloneWithProgress(Map<String, dynamic> app) async {
    final appName = app['appName'] as String? ?? 'App';
    final pkgName = app['packageName'] as String? ?? '';
    if (pkgName.isEmpty) return;

    setState(() {
      _isOperationVisible = true;
      _isOperationComplete = false;
      _showOpenSettingsFallback = false;
      _operationTitle = "Cloning $appName";
      _operationSubtitle = "Reading APK from device storage (Offline)...";
      _operationProgress = 0.05;
      _operationColor = const Color(0xFF00E5FF);
      _operationIcon = Icons.copy_all;
    });

    final started = await _service.cloneAppAsync(pkgName);
    if (!started && mounted) {
      setState(() {
        _operationTitle = "Cloning Failed";
        _operationSubtitle = "Could not start APK cloner for $appName.";
        _operationProgress = 1.0;
        _operationColor = Colors.redAccent;
        _operationIcon = Icons.error_outline;
        _isOperationComplete = true;
      });
    }
  }

  // TAB 1: User Cloned Apps & Games
  Widget _buildClonedAppsTab() {
    return RefreshIndicator(
      onRefresh: _loadData,
      color: const Color(0xFF00E5FF),
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_instances.isEmpty)
              Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 80),
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
                        "Tap '+ CLONE APP' below to isolate and run an app or game.",
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey[400], fontSize: 13),
                      ),
                    ],
                  ),
                ),
              )
            else
              ..._instances.map((inst) => _buildClonedAppCard(inst)),
            const SizedBox(height: 80), // Fab padding
          ],
        ),
      ),
    );
  }

  // TAB 2: Work Space Core & System Services
  Widget _buildWorkSpaceCoreTab() {
    final isWorkProfileActive = _servicesStatus['workProfileActive'] == true;
    final isGmsActive = _servicesStatus['gmsActive'] == true;
    final isBillingActive = _servicesStatus['playBillingActive'] == true;

    return RefreshIndicator(
      onRefresh: _loadData,
      color: const Color(0xFF00E5FF),
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Work Profile Management Card
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF141724),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: isWorkProfileActive ? const Color(0xFF00FF88).withValues(alpha: 0.4) : const Color(0xFFFF6D00).withValues(alpha: 0.4),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        isWorkProfileActive ? Icons.verified_user : Icons.security,
                        color: isWorkProfileActive ? const Color(0xFF00FF88) : const Color(0xFFFF6D00),
                        size: 26,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              isWorkProfileActive ? "Work Profile Dual Space Active" : "Work Profile Not Configured",
                              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              isWorkProfileActive
                                  ? "Isolated multi-user container active with native GMS & Play Store."
                                  : "Provision isolated Work Profile for complete hardware and account separation.",
                              style: TextStyle(color: Colors.grey[400], fontSize: 11),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      if (!isWorkProfileActive)
                        Expanded(
                          child: ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: const Color(0xFFFF6D00),
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                              padding: const EdgeInsets.symmetric(vertical: 11),
                            ),
                            icon: const Icon(Icons.add_moderator, size: 18),
                            label: const Text("SETUP WORK PROFILE", style: TextStyle(fontWeight: FontWeight.bold)),
                            onPressed: () async {
                              final ok = await _service.provisionWorkProfile();
                              if (ok) _loadData();
                            },
                          ),
                        )
                      else ...[
                        Expanded(
                          child: OutlinedButton.icon(
                            style: OutlinedButton.styleFrom(
                              foregroundColor: Colors.redAccent,
                              side: const BorderSide(color: Colors.redAccent, width: 1.2),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                              padding: const EdgeInsets.symmetric(vertical: 11),
                            ),
                            icon: const Icon(Icons.delete_forever, size: 18),
                            label: const Text("REMOVE DUAL SPACE", style: TextStyle(fontWeight: FontWeight.bold)),
                            onPressed: _confirmRemoveWorkProfile,
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Google Account in Work Space Card
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF141724),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: const Color(0xFF262C42)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: const Color(0xFF1E2235),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: const Icon(Icons.account_circle, color: Color(0xFF00E5FF), size: 24),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              "Google Account & Cloud Saves",
                              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              "Add a secondary Google account inside Work Profile for Play Games cloud sync.",
                              style: TextStyle(color: Colors.grey[400], fontSize: 11),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF1F2438),
                        foregroundColor: const Color(0xFF00E5FF),
                        side: const BorderSide(color: Color(0xFF00E5FF), width: 1),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                      icon: const Icon(Icons.person_add_alt_1, size: 18),
                      label: const Text(
                        "ADD / MANAGE GOOGLE ACCOUNT",
                        style: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 0.5),
                      ),
                      onPressed: () async {
                        final ok = await _service.openWorkProfileAddAccount();
                        if (ok) {
                          _showHudNotification(
                            "Opened Google Sign-in for Work Space",
                            icon: Icons.account_circle,
                            color: const Color(0xFF00E5FF),
                          );
                        } else {
                          _showHudNotification(
                            "Work Profile must be active to manage Google accounts",
                            icon: Icons.warning_amber_rounded,
                            color: Colors.amberAccent,
                          );
                        }
                      },
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Services Status Row
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
            const SizedBox(height: 22),

            // Core Apps Header
            Text(
              "WORK SPACE CORE APPS (${_coreApps.length})",
              style: const TextStyle(
                color: Colors.grey,
                fontWeight: FontWeight.w800,
                letterSpacing: 1.2,
                fontSize: 13,
              ),
            ),
            const SizedBox(height: 12),

            if (_coreApps.isEmpty)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: const Color(0xFF141724),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: const Color(0xFF262C42)),
                ),
                child: Center(
                  child: Text(
                    isWorkProfileActive
                        ? "Google Play Services & Core Framework Active"
                        : "Work Profile is not active. Tap 'SETUP WORK PROFILE' above to initialize Dual Space.",
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey[400], fontSize: 13),
                  ),
                ),
              )
            else
              ..._coreApps.map((app) => _buildCoreAppTile(app)),
          ],
        ),
      ),
    );
  }

  void _confirmRemoveWorkProfile() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF161926),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: const [
            Icon(Icons.warning, color: Colors.redAccent),
            SizedBox(width: 10),
            Text("Remove Dual Space?", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
          ],
        ),
        content: const Text(
          "This will permanently delete the Work Profile and wipe all cloned apps, accounts, and dual space data.\n\nAre you sure you want to proceed?",
          style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.4),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text("CANCEL", style: TextStyle(color: Colors.grey)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.redAccent,
              foregroundColor: Colors.white,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text("CONFIRM REMOVE", style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );

    if (confirm == true) {
      setState(() {
        _isOperationVisible = true;
        _isOperationComplete = false;
        _showOpenSettingsFallback = false;
        _operationTitle = "Removing Dual Space";
        _operationSubtitle = "Wiping Work Profile, accounts & cloned data...";
        _operationProgress = 0.20;
        _operationColor = Colors.redAccent;
        _operationIcon = Icons.delete_sweep;
      });

      await _service.removeWorkProfile();
      if (!mounted) return;

      bool removed = false;
      for (int i = 0; i < 12; i++) {
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted) return;
        final status = await _service.checkRealServicesStatus();
        final active = status['workProfileActive'] == true;
        setState(() {
          _operationProgress = (0.30 + (i + 1) * 0.05).clamp(0.30, 0.92);
          _operationSubtitle = active
              ? "Waiting for Android OS to delete Work Profile..."
              : "Finalizing Dual Space cleanup...";
        });
        if (!active) {
          removed = true;
          break;
        }
      }

      await _loadData();
      if (!mounted) return;

      setState(() {
        _operationProgress = 1.0;
        _isOperationComplete = true;
        _showOpenSettingsFallback = !removed;
        if (removed) {
          _operationTitle = "Dual Space Removed!";
          _operationSubtitle = "Work Profile and all isolated accounts deleted.";
          _operationColor = const Color(0xFF00FF88);
          _operationIcon = Icons.check_circle;
        } else {
          _operationTitle = "Removal Incomplete";
          _operationSubtitle = "Could not verify removal. Tap SETTINGS to remove manually.";
          _operationColor = Colors.amberAccent;
          _operationIcon = Icons.warning_amber_rounded;
        }
      });
    }
  }

  Widget _buildCoreAppTile(Map<String, dynamic> app) {
    final iconBase64 = app['iconBase64'] as String? ?? '';
    final name = app['appName'] as String? ?? 'Core App';
    final pkg = app['packageName'] as String? ?? '';
    final isPlayStore = app['isPlayStore'] == true;
    final componentName = app['componentName'] as String? ?? '';
    final canOpen = componentName.isNotEmpty || isPlayStore || pkg == 'com.google.android.play.games';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF141724),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFF22273B)),
      ),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: iconBase64.isNotEmpty
                ? Image.memory(
                    base64Decode(iconBase64),
                    width: 40,
                    height: 40,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => _fallbackAppIcon(),
                  )
                : _fallbackAppIcon(),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        name,
                        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (isPlayStore) ...[
                      const SizedBox(width: 6),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                        decoration: BoxDecoration(
                          color: const Color(0xFF00FF88).withValues(alpha: 0.2),
                          borderRadius: BorderRadius.circular(5),
                          border: Border.all(color: const Color(0xFF00FF88), width: 0.8),
                        ),
                        child: const Text("OFFICIAL", style: TextStyle(color: Color(0xFF00FF88), fontSize: 8, fontWeight: FontWeight.bold)),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(pkg, style: TextStyle(color: Colors.grey[500], fontSize: 11)),
              ],
            ),
          ),
          if (canOpen)
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF1E2235),
                foregroundColor: const Color(0xFF00E5FF),
                side: const BorderSide(color: Color(0xFF00E5FF), width: 1),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              ),
              onPressed: () {
                _service.launchApp("core", pkg);
              },
              child: const Text("OPEN", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
            )
          else
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: const Color(0xFF00FF88).withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: const Color(0xFF00FF88).withValues(alpha: 0.5)),
              ),
              child: const Text(
                "ACTIVE",
                style: TextStyle(color: Color(0xFF00FF88), fontWeight: FontWeight.bold, fontSize: 11),
              ),
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
                      inst.isStandaloneClone ? inst.clonedPackageName : inst.packageName,
                      style: TextStyle(color: Colors.grey[400], fontSize: 12),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert, color: Colors.grey),
                color: const Color(0xFF1F2438),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                onSelected: (value) async {
                  if (value == 'shortcut') {
                    final ok = await _service.createShortcut(inst.instanceId, inst.packageName, inst.appName, inst.badgeColor);
                    _showHudNotification(
                      ok ? "Home screen shortcut added!" : "Could not create shortcut",
                      icon: Icons.add_to_home_screen,
                      color: const Color(0xFFFF6D00),
                    );
                  } else if (value == 'clear_cache') {
                    await _service.clearCache(inst.instanceId, inst.packageName);
                    _loadData();
                    _showHudNotification("Cleared cache for ${inst.appName}", icon: Icons.cleaning_services, color: const Color(0xFF00E5FF));
                  } else if (value == 'clear_data') {
                    final confirm = await _showConfirmDialog("Clear All Data", "Are you sure you want to wipe data for ${inst.appName}?");
                    if (confirm == true) {
                      await _service.clearData(inst.instanceId, inst.packageName);
                      _loadData();
                      _showHudNotification("Wiped data for ${inst.appName}", icon: Icons.delete_sweep, color: Colors.amberAccent);
                    }
                  } else if (value == 'delete') {
                    final confirm = await _showConfirmDialog("Delete Cloned App", "Permanently remove ${inst.appName} from Sandbox?");
                    if (confirm == true) {
                      await _service.deleteInstance(inst.instanceId);
                      _loadData();
                      _showHudNotification("Deleted ${inst.appName}", icon: Icons.delete_forever, color: Colors.redAccent);
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

          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _buildMetaTag(Icons.storage, _formatBytes(inst.storageUsageBytes)),
              _buildMetaTag(Icons.smartphone, inst.fakeDeviceModel),
            ],
          ),
          const SizedBox(height: 12),

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
                if (!inst.isStandaloneClone && inst.isPrimary && _servicesStatus['workProfileActive'] != true) {
                  _promptSetupWorkProfile(inst);
                  return;
                }
                _showHudNotification(
                  "Launching ${inst.appName}...",
                  icon: Icons.rocket_launch,
                  color: const Color(0xFF00E5FF),
                );
                final success = await _service.launchApp(inst.instanceId, inst.packageName);
                if (!success && mounted) {
                  _showHudNotification(
                    "Could not open ${inst.appName}",
                    icon: Icons.error_outline,
                    color: Colors.redAccent,
                  );
                }
              },
            ),
          ),
        ],
      ),
    );
  }

  void _promptSetupWorkProfile(ClonedInstance inst) async {
    final setup = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF161926),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: const [
            Icon(Icons.security, color: Color(0xFFFF6D00)),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                "Activate Dual Space?",
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 17),
              ),
            ),
          ],
        ),
        content: Text(
          "To run '${inst.appName}' with an isolated Google Play account and separate app data, Dual Space Work Profile must be active.\n\nSet up Work Profile now?",
          style: const TextStyle(color: Colors.white70, fontSize: 13, height: 1.4),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text("LATER", style: TextStyle(color: Colors.grey)),
          ),
          ElevatedButton.icon(
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFFFF6D00),
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            icon: const Icon(Icons.add_moderator, size: 18),
            label: const Text("SETUP WORK PROFILE", style: TextStyle(fontWeight: FontWeight.bold)),
            onPressed: () => Navigator.pop(context, true),
          ),
        ],
      ),
    );

    if (setup == true) {
      await _service.provisionWorkProfile();
    }
  }

  Widget _buildBadgedIcon(String iconBase64, String badgeColorHex) {
    Color badgeColor = const Color(0xFFFF6D00);
    try {
      badgeColor = Color(int.parse(badgeColorHex.replaceFirst('#', '0xFF')));
    } catch (_) {}

    return SizedBox(
      width: 50,
      height: 50,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
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
