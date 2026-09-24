import 'dart:io';

import 'package:flutter/services.dart';

abstract interface class VoiceDevicePermission {
  Future<bool> prepareBluetooth();
}

class NativeVoiceDevicePermission implements VoiceDevicePermission {
  const NativeVoiceDevicePermission();

  static const _channel = MethodChannel('uno/voice_device');

  @override
  Future<bool> prepareBluetooth() async {
    if (!Platform.isAndroid) return true;
    try {
      return await _channel.invokeMethod<bool>('prepareBluetooth') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}
