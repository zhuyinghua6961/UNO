import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'native secure storage survives a new instance and can be deleted',
    (tester) async {
      const key = 'uno.integration.storage.check';
      const storage = FlutterSecureStorage();
      await storage.delete(key: key);
      await storage.write(key: key, value: 'test-only-value');
      const reopened = FlutterSecureStorage();
      expect(await reopened.read(key: key), 'test-only-value');
      await reopened.delete(key: key);
      expect(await storage.read(key: key), isNull);
    },
  );
}
