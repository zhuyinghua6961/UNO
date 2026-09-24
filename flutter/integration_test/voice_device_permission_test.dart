import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:uno_app/features/voice/voice_device_permission.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Android voice channel sees granted Bluetooth permission', (tester) async {
    expect(await const NativeVoiceDevicePermission().prepareBluetooth(), true);
  });
}
