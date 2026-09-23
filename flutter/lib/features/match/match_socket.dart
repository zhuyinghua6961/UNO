import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import '../auth/auth_session.dart';
import 'match_api.dart';
import 'match_models.dart';

enum MatchSocketStatus { connecting, connected, disconnected, unauthorized, takenOver }

typedef MatchWebSocketConnector = Future<WebSocket> Function(
  Uri uri,
  Map<String, String> headers,
);

abstract interface class MatchTransport {
  void start();
  bool send(MatchCommand command);
  void close();
}

class MatchSocketHandlers {
  const MatchSocketHandlers({
    required this.onStatus,
    required this.onSnapshot,
    required this.onAcknowledged,
    required this.onRejected,
    required this.onError,
  });

  final void Function(MatchSocketStatus) onStatus;
  final void Function(MatchState) onSnapshot;
  final void Function(MatchReceipt) onAcknowledged;
  final void Function(String commandId, String code) onRejected;
  final void Function(String message) onError;
}

class MatchSocket implements MatchTransport {
  MatchSocket({
    required this.matchId,
    required this.api,
    required this.session,
    required this.onStatus,
    required this.onSnapshot,
    required this.onAcknowledged,
    required this.onRejected,
    required this.onError,
    MatchWebSocketConnector? connector,
  }) : _connector = connector ?? _defaultConnector;

  final String matchId;
  final MatchApi api;
  final AuthSession session;
  final void Function(MatchSocketStatus) onStatus;
  final void Function(MatchState) onSnapshot;
  final void Function(MatchReceipt) onAcknowledged;
  final void Function(String commandId, String code) onRejected;
  final void Function(String message) onError;
  final MatchWebSocketConnector _connector;

  WebSocket? _peer;
  StreamSubscription<dynamic>? _subscription;
  Timer? _retry;
  bool _closed = false;
  bool _subscribed = false;
  int _generation = 0;
  int _attempts = 0;
  int _policyFailures = 0;

  static Future<WebSocket> _defaultConnector(
    Uri uri,
    Map<String, String> headers,
  ) => WebSocket.connect(uri.toString(), headers: headers);

  @override
  void start() => unawaited(_connect());

  Future<void> _connect() async {
    if (_closed) return;
    final generation = ++_generation;
    _subscribed = false;
    onStatus(MatchSocketStatus.connecting);
    try {
      // Recheck the app credential and restore the private view before each
      // subscription. This also rotates an expired access token if possible.
      final state = await api.state(matchId);
      if (_closed || generation != _generation) return;
      onSnapshot(state);
      final base = Uri.parse(api.baseUrl);
      final endpoint = base.replace(
        scheme: base.scheme == 'https' ? 'wss' : 'ws',
        path: '/ws/game',
        query: null,
        fragment: null,
      );
      final peer = await session.withAccess(
        (token) => _connector(endpoint, {
          'X-UNO-Client': 'APP',
          'Authorization': 'Bearer $token',
        }),
      );
      if (_closed || generation != _generation) {
        await peer.close();
        return;
      }
      _peer = peer;
      _attempts = 0;
      _subscription = peer.listen(
        (data) => _handle(data, generation),
        onError: (_) {
          if (!_closed && generation == _generation && _peer == peer) {
            onStatus(MatchSocketStatus.disconnected);
          }
        },
        onDone: () => _disconnected(peer, generation),
        cancelOnError: false,
      );
      peer.add(
        jsonEncode({
          'protocolVersion': 1,
          'type': 'SUBSCRIBE',
          'matchId': matchId,
        }),
      );
    } catch (failure) {
      if (_closed || generation != _generation) return;
      if (session.state == SessionState.guest) {
        onStatus(MatchSocketStatus.unauthorized);
      } else {
        onError('$failure');
        _scheduleRetry();
      }
    }
  }

  void _handle(dynamic data, int generation) {
    if (_closed || generation != _generation) return;
    try {
      if (data is! String) throw const MatchDataFailure();
      final value = jsonDecode(data);
      if (value is! Map<String, dynamic> || value['protocolVersion'] != 1) {
        throw const MatchDataFailure();
      }
      final type = value['type'];
      if (type == 'MATCH_SNAPSHOT' && value['matchId'] == matchId) {
        onSnapshot(MatchState.parse(value));
        _subscribed = true;
        _policyFailures = 0;
        onStatus(MatchSocketStatus.connected);
      } else if (type == 'COMMAND_ACK' && value['matchId'] == matchId) {
        onAcknowledged(MatchReceipt.parse(value['result']));
      } else if (type == 'COMMAND_REJECTED' &&
          value['matchId'] == matchId &&
          value['commandId'] is String &&
          value['code'] is String) {
        onRejected(value['commandId'] as String, value['code'] as String);
      } else if (type == 'ERROR' && value['code'] is String) {
        onError('对局连接返回 ${value['code']}，请同步状态。');
      } else {
        throw const MatchDataFailure();
      }
    } catch (_) {
      onError('收到无效的对局消息，请同步状态。');
    }
  }

  void _disconnected(WebSocket peer, int generation) {
    if (_closed || generation != _generation) return;
    _peer = null;
    _subscribed = false;
    if (peer.closeCode == 4001 && peer.closeReason == 'TAKEN_OVER') {
      onStatus(MatchSocketStatus.takenOver);
      return;
    }
    if (peer.closeCode == WebSocketStatus.policyViolation &&
        peer.closeReason != 'RATE_LIMITED') {
      if (++_policyFailures > 1) {
        onStatus(MatchSocketStatus.unauthorized);
      } else {
        // A previously valid access token may have expired while this socket
        // was open. The next state request rotates it before re-subscribing.
        _scheduleRetry();
      }
      return;
    }
    _scheduleRetry();
  }

  void _scheduleRetry() {
    if (_closed) return;
    onStatus(MatchSocketStatus.disconnected);
    _retry?.cancel();
    final delay = min(5000, 500 * (1 << min(_attempts++, 4)));
    _retry = Timer(Duration(milliseconds: delay), () => unawaited(_connect()));
  }

  @override
  bool send(MatchCommand command) {
    final peer = _peer;
    if (peer == null || !_subscribed || peer.readyState != WebSocket.open) {
      return false;
    }
    try {
      peer.add(
        jsonEncode({
          'protocolVersion': 1,
          'type': 'COMMAND',
          'matchId': matchId,
          'command': command.toJson(),
        }),
      );
      return true;
    } catch (_) {
      _subscribed = false;
      _scheduleRetry();
      return false;
    }
  }

  @override
  void close() {
    if (_closed) return;
    _closed = true;
    _generation++;
    _subscribed = false;
    _retry?.cancel();
    unawaited(_subscription?.cancel());
    unawaited(_peer?.close());
    _peer = null;
  }
}
