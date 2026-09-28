import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'auth_api.dart';

class StoredTokens {
  const StoredTokens(
    this.accessToken,
    this.refreshToken,
    this.expiresAt,
    this.refreshExpiresAt,
  );
  final String accessToken;
  final String refreshToken;
  final DateTime expiresAt;
  final DateTime refreshExpiresAt;

  factory StoredTokens.fromGrant(AppGrant grant) => StoredTokens(
    grant.accessToken,
    grant.refreshToken,
    grant.expiresAt,
    grant.refreshExpiresAt,
  );

  String encode() => jsonEncode({
    'accessToken': accessToken,
    'refreshToken': refreshToken,
    'expiresAt': expiresAt.toIso8601String(),
    'refreshExpiresAt': refreshExpiresAt.toIso8601String(),
  });

  static StoredTokens? decode(String? raw) {
    if (raw == null) return null;
    try {
      final value = jsonDecode(raw);
      if (value
          case {
            'accessToken': String access,
            'refreshToken': String refresh,
            'expiresAt': String accessExpiry,
            'refreshExpiresAt': String refreshExpiry,
          }
          when access.isNotEmpty && refresh.isNotEmpty) {
        final a = DateTime.tryParse(accessExpiry);
        final r = DateTime.tryParse(refreshExpiry);
        if (a != null && r != null) return StoredTokens(access, refresh, a, r);
      }
    } catch (_) {
      /* Invalid local data is treated as an expired login. */
    }
    return null;
  }
}

abstract class TokenStore {
  Future<StoredTokens?> read();
  Future<void> write(StoredTokens tokens);
  Future<void> clear();
}

/// Local unsigned iOS Simulator previews cannot use Keychain entitlements.
/// This store deliberately loses the session when the app process exits.
class EphemeralTokenStore implements TokenStore {
  StoredTokens? _tokens;

  @override
  Future<StoredTokens?> read() async => _tokens;

  @override
  Future<void> write(StoredTokens tokens) async {
    _tokens = tokens;
  }

  @override
  Future<void> clear() async {
    _tokens = null;
  }
}

class SecureTokenStore implements TokenStore {
  SecureTokenStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();
  static const _key = 'uno.app.session.v1';
  final FlutterSecureStorage _storage;

  @override
  Future<StoredTokens?> read() async =>
      StoredTokens.decode(await _storage.read(key: _key));

  @override
  Future<void> write(StoredTokens tokens) =>
      _storage.write(key: _key, value: tokens.encode());

  @override
  Future<void> clear() => _storage.delete(key: _key);
}

enum SessionState { unknown, guest, authenticated, unavailable }

class AuthSession extends ChangeNotifier {
  AuthSession({
    required this.api,
    required this.store,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final AuthApi api;
  final TokenStore store;
  final DateTime Function() _now;
  StoredTokens? _tokens;
  Future<StoredTokens>? _refreshing;
  Future<void>? _initializing;
  bool _busy = false;

  SessionState state = SessionState.unknown;
  AuthUser? user;
  AuthCapabilities? capabilities;
  String error = '';
  String notice = '';
  bool get busy => _busy;

  Future<void> initialize() => _initializing ??= _initialize();

  Future<void> _initialize() async {
    _busy = true;
    notifyListeners();
    try {
      try {
        capabilities = await api.status();
      } on AuthFailure catch (failure) {
        error = failure.message;
      }
      _tokens = await store.read();
      if (_tokens == null) {
        state = SessionState.guest;
      } else {
        await _loadProfile();
      }
    } catch (_) {
      state = SessionState.unavailable;
      user = null;
      error = '无法读取安全存储或恢复登录，请重试。';
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<T> _run<T>(Future<T> Function() action) async {
    if (_busy) throw const AuthFailure(409, 'BUSY', '请等待当前操作完成。');
    _busy = true;
    error = '';
    notice = '';
    notifyListeners();
    try {
      return await action();
    } on AuthFailure catch (failure) {
      error = failure.message;
      rethrow;
    } catch (_) {
      error = '安全存储操作失败，请检查设备后重试。';
      throw const AuthFailure(0, 'STORAGE_ERROR', '安全存储操作失败，请检查设备后重试。');
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> _clear() async {
    try {
      await store.clear();
    } finally {
      // A revoked server session must not remain visible if device storage fails.
      _tokens = null;
      user = null;
      state = SessionState.guest;
      notifyListeners();
    }
  }

  Future<StoredTokens> _refresh() {
    final pending = _refreshing;
    if (pending != null) return pending;
    final future = _rotate();
    _refreshing = future;
    // Keep the same Future for every caller until rotation has fully settled.
    future.then(
      (_) {
        if (identical(_refreshing, future)) _refreshing = null;
      },
      onError: (Object _) {
        if (identical(_refreshing, future)) _refreshing = null;
      },
    );
    return future;
  }

  Future<StoredTokens> _rotate() async {
    final previous = _tokens;
    if (previous == null) throw const AuthFailure(401, 'NO_SESSION', '请先登录。');
    if (!previous.refreshExpiresAt.isAfter(_now())) {
      await _clear();
      throw const AuthFailure(401, 'SESSION_EXPIRED', '登录已过期，请重新登录。');
    }
    try {
      final grant = await api.refresh(previous.refreshToken);
      final next = StoredTokens.fromGrant(grant);
      await store.write(next);
      _tokens = next;
      return next;
    } on AuthFailure catch (failure) {
      if (failure.status == 401) await _clear();
      rethrow;
    }
  }

  Future<String> _access() async {
    final current = _tokens;
    if (current == null) throw const AuthFailure(401, 'NO_SESSION', '请先登录。');
    if (!current.expiresAt.isAfter(_now().add(const Duration(seconds: 30)))) {
      return (await _refresh()).accessToken;
    }
    return current.accessToken;
  }

  Future<T> withAccess<T>(Future<T> Function(String) request) async {
    final access = await _access();
    try {
      return await request(access);
    } on AuthFailure catch (failure) {
      if (failure.status != 401) rethrow;
      final current = _tokens;
      final next = current != null && current.accessToken != access
          ? current
          : await _refresh();
      return request(next.accessToken);
    }
  }

  Future<AuthUser> _authenticatedUser() => withAccess(api.me);

  Future<void> _loadProfile() async {
    try {
      final restored = await _authenticatedUser();
      user = restored;
      state = SessionState.authenticated;
      error = '';
    } on AuthFailure catch (failure) {
      user = null;
      if (failure.status == 401) {
        await _clear();
        error = '登录已失效，请重新登录。';
      } else {
        state = SessionState.unavailable;
        error = failure.message;
      }
    }
    notifyListeners();
  }

  Future<void> retry() => _run(() async {
    capabilities = await api.status();
    _tokens ??= await store.read();
    if (_tokens == null) {
      state = SessionState.guest;
    } else {
      await _loadProfile();
    }
  });

  Future<void> revalidate() async {
    if (_busy || _tokens == null) return;
    await _run(_loadProfile);
  }

  Future<void> login(String email, String password) => _run(() async {
    final grant = await api.login(email, password);
    final tokens = StoredTokens.fromGrant(grant);
    await store.write(tokens);
    _tokens = tokens;
    user = grant.user;
    state = SessionState.authenticated;
    notifyListeners();
  });

  Future<void> logout() => _run(() async {
    try {
      await api.logout(await _access());
    } on AuthFailure catch (failure) {
      if (failure.status != 401) rethrow;
    }
    await _clear();
  });

  Future<void> updateNickname(String nickname) => _run(() async {
    final updated = await withAccess(
      (token) => api.updateProfile(token, nickname),
    );
    if (updated.id != user?.id) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '账号身份不一致，请重新登录。');
    }
    user = updated;
    notifyListeners();
  });

  Future<void> register(String email, String password, String nickname) =>
      _run(() async {
        await api.register(email, password, nickname);
        notice = '请求已受理。请查看最新验证邮件；若邮箱已注册，账号不会重复创建。';
      });

  Future<void> resend(String email) => _run(() async {
    await api.resend(email);
    notice = '请求已受理；若符合条件，请查看最新邮件。';
  });

  Future<void> verify(String token) => _run(() async {
    await api.verify(token);
    notice = '邮箱验证完成，现在可以登录。';
  });

  Future<void> forgot(String email) => _run(() async {
    await api.forgot(email);
    notice = '请求已受理；若符合条件，请查看最新邮件。';
  });

  Future<void> reset(String token, String password) => _run(() async {
    await api.reset(token, password);
    await _clear();
    notice = '密码已更新，请重新登录。';
  });
}
