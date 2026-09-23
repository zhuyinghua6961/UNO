import 'package:flutter/foundation.dart';

class ApiConfig {
  static const configuredUrl = String.fromEnvironment('API_BASE_URL');

  // The gateway runs on 29080 in the local development profile.
  static String get baseUrl {
    final value = configuredUrl.isNotEmpty
        ? configuredUrl
        : defaultTargetPlatform == TargetPlatform.android
        ? 'http://10.0.2.2:29080'
        : 'http://localhost:29080';
    final uri = Uri.tryParse(value);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (kReleaseMode && uri.scheme != 'https')) {
      throw StateError('API_BASE_URL 必须是有效服务地址；正式构建必须使用 HTTPS。');
    }
    return value.replaceAll(RegExp(r'/$'), '');
  }
}
