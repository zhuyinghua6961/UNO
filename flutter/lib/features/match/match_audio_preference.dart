import 'package:flutter_secure_storage/flutter_secure_storage.dart';

abstract interface class MatchAudioPreference {
  Future<bool> readMuted();
  Future<void> writeMuted(bool muted);
}

class SecureMatchAudioPreference implements MatchAudioPreference {
  SecureMatchAudioPreference([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  static const _key = 'uno.app.match.muted.v1';
  final FlutterSecureStorage _storage;

  @override
  Future<bool> readMuted() async => await _storage.read(key: _key) == 'true';

  @override
  Future<void> writeMuted(bool muted) =>
      _storage.write(key: _key, value: muted.toString());
}
