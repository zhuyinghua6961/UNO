import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';

class AuthFailure implements Exception {
  const AuthFailure(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  @override
  String toString() => message;
}

class AuthUser {
  const AuthUser(this.id, this.email, this.nickname);
  final String id;
  final String email;
  final String nickname;

  factory AuthUser.fromJson(Object? value) {
    if (value
        case {
          'id': String id,
          'email': String email,
          'nickname': String nickname,
        }
        when id.isNotEmpty && email.isNotEmpty && nickname.isNotEmpty) {
      return AuthUser(id, email, nickname);
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '账号服务返回异常，请稍后重试。');
  }
}

class AuthCapabilities {
  const AuthCapabilities(this.loginAvailable, this.registrationAvailable);
  final bool loginAvailable;
  final bool registrationAvailable;
}

class AppGrant {
  const AppGrant(
    this.user,
    this.accessToken,
    this.refreshToken,
    this.expiresAt,
    this.refreshExpiresAt,
  );
  final AuthUser user;
  final String accessToken;
  final String refreshToken;
  final DateTime expiresAt;
  final DateTime refreshExpiresAt;

  factory AppGrant.fromJson(Object? value) {
    if (value
        case {
          'user': final Object? user,
          'accessToken': String accessToken,
          'refreshToken': String refreshToken,
          'tokenType': 'Bearer',
          'expiresAt': String expiresAt,
          'refreshExpiresAt': String refreshExpiresAt,
        }
        when accessToken.isNotEmpty && refreshToken.isNotEmpty) {
      final accessExpiry = DateTime.tryParse(expiresAt);
      final refreshExpiry = DateTime.tryParse(refreshExpiresAt);
      if (accessExpiry != null && refreshExpiry != null) {
        return AppGrant(
          AuthUser.fromJson(user),
          accessToken,
          refreshToken,
          accessExpiry,
          refreshExpiry,
        );
      }
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '账号服务返回异常，请稍后重试。');
  }
}

class AuthApi {
  AuthApi({http.Client? client, String? baseUrl})
    : _client = client ?? http.Client(),
      _baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client _client;
  final String _baseUrl;

  Future<Object?> _request(String path, {Object? body, String? bearer}) async {
    final headers = <String, String>{
      'X-UNO-Client': 'APP',
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
      if (bearer != null) 'Authorization': 'Bearer $bearer',
    };
    late http.Response response;
    try {
      final request =
          http.Request(
              body == null ? 'GET' : 'POST',
              Uri.parse('$_baseUrl$path'),
            )
            ..headers.addAll(headers)
            ..followRedirects = false;
      if (body != null) request.body = jsonEncode(body);
      response = await (() async {
        final streamed = await _client.send(request);
        return http.Response.fromStream(streamed);
      })().timeout(const Duration(seconds: 12));
    } on SocketException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '网络连接失败，请检查地址与网络后重试。');
    } on HttpException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '网络连接失败，请检查地址与网络后重试。');
    } on HandshakeException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '安全连接失败，请检查服务器证书。');
    } on FormatException catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '服务地址无效，请检查配置。');
    } catch (_) {
      throw const AuthFailure(0, 'NETWORK_ERROR', '网络连接失败或超时；上次请求可能已被处理。');
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      String code = 'REQUEST_FAILED';
      try {
        final decoded = jsonDecode(response.body);
        if (decoded is Map && decoded['code'] is String) code = decoded['code'];
      } catch (_) {
        /* Keep the generic error. */
      }
      final message = switch (response.statusCode) {
        401 => '登录信息无效、邮箱尚未验证或会话已失效，请重新登录。',
        403 => '安全校验未通过，请检查服务地址配置。',
        429 => '操作过于频繁，请稍后再试。',
        >= 500 => '账号服务暂不可用，请稍后重试。',
        _ when code == 'INVALID_TOKEN' => '邮件凭证无效、已使用或已过期，请重新申请。',
        _ => '请求未完成，请检查填写内容后重试。',
      };
      throw AuthFailure(response.statusCode, code, message);
    }
    if (response.statusCode == 204) return null;
    try {
      return jsonDecode(response.body);
    } catch (_) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '账号服务返回异常，请稍后重试。');
    }
  }

  Future<AuthCapabilities> status() async {
    final value = await _request('/api/auth/status');
    if (value case {
      'service': 'identity-service',
      'loginAvailable': bool login,
      'registrationAvailable': bool registration,
    }) {
      return AuthCapabilities(login, registration);
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '无法确认账号功能状态，请稍后重试。');
  }

  Future<AuthUser> me(String accessToken) async =>
      AuthUser.fromJson(await _request('/api/users/me', bearer: accessToken));

  Future<AuthUser> updateProfile(String accessToken, String nickname) async =>
      AuthUser.fromJson(await _request('/api/users/me/profile',
          body: {'nickname': nickname}, bearer: accessToken));

  Future<AppGrant> login(String email, String password) async =>
      AppGrant.fromJson(
        await _request(
          '/api/auth/login',
          body: {'email': email, 'password': password},
        ),
      );

  Future<AppGrant> refresh(String token) async => AppGrant.fromJson(
    await _request('/api/auth/refresh', body: {'token': token}),
  );

  Future<void> logout(String accessToken) async {
    await _request(
      '/api/auth/logout',
      body: <String, Object>{},
      bearer: accessToken,
    );
  }

  Future<void> register(String email, String password, String nickname) async {
    await _request(
      '/api/auth/register',
      body: {'email': email, 'password': password, 'nickname': nickname},
    );
  }

  Future<void> resend(String email) async {
    await _request('/api/auth/verification/request', body: {'email': email});
  }

  Future<void> verify(String token) async {
    await _request('/api/auth/verify-email', body: {'token': token});
  }

  Future<void> forgot(String email) async {
    await _request('/api/auth/password/forgot', body: {'email': email});
  }

  Future<void> reset(String token, String password) async {
    await _request(
      '/api/auth/password/reset',
      body: {'token': token, 'password': password},
    );
  }

  void close() => _client.close();
}
