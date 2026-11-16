import 'package:flutter_test/flutter_test.dart';
import 'package:sandbox/main.dart';

void main() {
  testWidgets('Sandbox dashboard smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const SandboxApp());
    await tester.pumpAndSettle();

    // Verify Sandbox branding appears
    expect(find.text('SANDBOX'), findsOneWidget);
    expect(find.text('v2.0.2 • Blackbox THC'), findsOneWidget);
  });
}
