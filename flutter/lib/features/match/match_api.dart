import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';
import '../auth/auth_api.dart';
import '../auth/auth_session.dart';
import 'match_models.dart';

class MatchApi {
  MatchApi({required this.session, http.Client? client, String? baseUrl})
    : _client = client ?? http.Client(),
      baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final AuthSession session;
  final http.Client _client;
  final String baseUrl;

  Future<Object?> _request(String path, {Object? body}) => session.withAccess((
    token,
  ) async {
    late http.Response response;
    try {
      final request =
          http.Request(
              body == null ? 'GET' : 'POST',
              Uri.parse('$baseUrl$path'),
            )
            ..followRedirects = false
            ..headers.addAll({
              'X-UNO-Client': 'APP',
              'Authorization': 'Bearer $token',
              'Accept': 'application/json',
              if (body != null) 'Content-Type': 'application/json',
            });
      if (body != null) request.body = jsonEncode(body);
      final streamed = await _client
          .send(request)
          .timeout(const Duration(seconds: 12));
      response = await http.Response.fromStream(streamed);
    } on SocketException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '对局连接失败，请检查网络后重试。');
    } on HandshakeException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '安全连接失败，请检查服务器证书。');
    } catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '对局连接失败或超时；已提交的操作请先同步状态。');
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      String code = 'REQUEST_FAILED';
      String? detail;
      try {
        final payload = jsonDecode(response.body);
        if (payload is Map) {
          if (payload['code'] is String) code = payload['code'];
          if (payload['message'] is String) detail = payload['message'];
        }
      } catch (_) {
        // Keep the generic response message.
      }
      final message = switch (response.statusCode) {
        401 => '登录已失效，请重新登录。',
        403 => '无法访问这场对局或安全校验未通过。',
        >= 500 => '对局服务暂不可用，请稍后重试。',
        _ when code == 'TURN_EXPIRED' => '操作窗口已结束，请同步局面。',
        _ when code == 'MATCH_CONFLICT' => '牌局已变化，请同步最新状态。',
        _ => detail ?? '对局操作失败，请同步后重试。',
      };
      throw AuthFailure(response.statusCode, code, message);
    }
    if (response.statusCode == 204) return null;
    try {
      return jsonDecode(response.body);
    } catch (_) {
      throw const MatchDataFailure();
    }
  });

  Future<MatchStart> start(String roomId, int expectedVersion) async =>
      MatchStart.parse(
        await _request(
          '/api/rooms/$roomId/start',
          body: {'expectedVersion': expectedVersion},
        ),
      );

  Future<MatchStart?> current(String roomId) async {
    final response = await _request('/api/rooms/$roomId/match');
    return response == null ? null : MatchStart.parse(response);
  }

  Future<MatchState> state(String matchId) async =>
      MatchState.parse(await _request('/api/matches/$matchId/state'));

  void close() => _client.close();
}
