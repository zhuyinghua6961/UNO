import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';
import '../auth/auth_api.dart';
import '../auth/auth_session.dart';

class VoiceGrant {
  const VoiceGrant(this.url, this.token, this.expiresAt);
  final String url;
  final String token;
  final DateTime expiresAt;

  factory VoiceGrant.parse(Object? raw) {
    if (raw is! Map ||
        raw['url'] is! String ||
        raw['token'] is! String ||
        raw['expiresAt'] is! String) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '语音服务返回异常。');
    }
    final url = Uri.tryParse(raw['url'] as String);
    final token = raw['token'] as String;
    final expiry = DateTime.tryParse(raw['expiresAt'] as String);
    if (url == null ||
        !url.hasAuthority ||
        !['ws', 'wss'].contains(url.scheme) ||
        token.split('.').length != 3 ||
        expiry == null ||
        !expiry.isAfter(DateTime.now())) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '语音服务返回异常。');
    }
    return VoiceGrant(url.toString(), token, expiry);
  }
}

class VoiceApi {
  VoiceApi({required this.session, http.Client? client, String? baseUrl})
    : client = client ?? http.Client(),
      baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final AuthSession session;
  final http.Client client;
  final String baseUrl;

  Future<http.Response> _send(
    String path, {
    String? access,
    Object? body,
  }) async {
    try {
      final request =
          http.Request(
              body == null ? 'GET' : 'POST',
              Uri.parse('$baseUrl$path'),
            )
            ..followRedirects = false
            ..headers.addAll({
              'Accept': 'application/json',
              if (access != null) 'X-UNO-Client': 'APP',
              if (access != null) 'Authorization': 'Bearer $access',
              if (body != null) 'Content-Type': 'application/json',
            });
      if (body != null) request.body = jsonEncode(body);
      final streamed = await client
          .send(request)
          .timeout(const Duration(seconds: 12));
      return await http.Response.fromStream(streamed);
    } on SocketException {
      throw const AuthFailure(0, 'NETWORK_ERROR', '语音服务连接失败，请检查网络。');
    } on HandshakeException {
      throw const AuthFailure(0, 'NETWORK_ERROR', '语音连接证书无效，请检查服务地址。');
    } catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '语音服务连接失败或超时。');
    }
  }

  Object? _body(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      String code = 'REQUEST_FAILED';
      try {
        final body = jsonDecode(response.body);
        if (body is Map && body['code'] is String) {
          code = body['code'] as String;
        }
      } catch (_) {
        /* Keep the generic error. */
      }
      final message = switch (response.statusCode) {
        401 => '登录已失效，请重新登录。',
        404 => '当前对局没有可加入的队友语音。',
        429 => '语音加入过快，请稍后重试。',
        >= 500 => '队友语音暂不可用，请稍后重试。',
        _ => '无法加入队友语音，请刷新后重试。',
      };
      throw AuthFailure(response.statusCode, code, message);
    }
    try {
      return jsonDecode(response.body);
    } catch (_) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '语音服务返回异常。');
    }
  }

  Future<bool> available() async {
    final data = _body(await _send('/api/system/bootstrap'));
    return data is Map &&
        data['features'] is Map &&
        (data['features'] as Map)['teamVoice'] == true;
  }

  Future<VoiceGrant> token(String matchId) =>
      session.withAccess((access) async {
        final data = _body(
          await _send(
            '/api/voice/token',
            access: access,
            body: {'matchId': matchId},
          ),
        );
        return VoiceGrant.parse(data);
      });

  void close() => client.close();
}
