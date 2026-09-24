import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'room_api.dart';

typedef RoomChatConnector = Future<WebSocket> Function(
  Uri uri,
  Map<String, String> headers,
);

class RoomChatSocket {
  RoomChatSocket({
    required this.roomId,
    required this.api,
    required this.onSubscribed,
    required this.onMessage,
    required this.onDisconnected,
    RoomChatConnector? connector,
  }) : _connector = connector ?? _defaultConnector;

  final String roomId;
  final RoomApi api;
  final void Function() onSubscribed;
  final void Function(RoomChatMessage) onMessage;
  final void Function() onDisconnected;
  final RoomChatConnector _connector;

  WebSocket? _peer;
  StreamSubscription<dynamic>? _subscription;
  Timer? _retry;
  bool _closed = false;
  int _generation = 0;
  int _attempts = 0;
  int _policyFailures = 0;

  static Future<WebSocket> _defaultConnector(
    Uri uri,
    Map<String, String> headers,
  ) => WebSocket.connect(uri.toString(), headers: headers);

  void start() => unawaited(_connect());

  Future<void> _connect() async {
    if (_closed) return;
    final generation = ++_generation;
    try {
      final peer = await api.session.withAccess(
        (token) => _connector(api.chatSocketUri, {
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
        onError: (_) => _disconnected(peer, generation),
        onDone: () => _disconnected(peer, generation),
      );
      peer.add(
        jsonEncode({
          'protocolVersion': 1,
          'type': 'SUBSCRIBE',
          'roomId': roomId,
        }),
      );
    } catch (_) {
      if (!_closed && generation == _generation) _scheduleRetry();
    }
  }

  void _handle(dynamic data, int generation) {
    if (_closed || generation != _generation) return;
    try {
      final value = jsonDecode(data as String);
      if (value is! Map<String, dynamic> ||
          value['protocolVersion'] != 1 ||
          value['roomId'] != roomId) {
        return;
      }
      if (value['type'] == 'CHAT_SUBSCRIBED') {
        _policyFailures = 0;
        onSubscribed();
      } else if (value['type'] == 'CHAT_MESSAGE') {
        final message = RoomChatMessage.parse(value['item']);
        if (message.roomId == roomId) onMessage(message);
      }
    } catch (_) {
      onDisconnected();
    }
  }

  void _disconnected(WebSocket peer, int generation) {
    if (_closed || generation != _generation || _peer != peer) return;
    _peer = null;
    final policyViolation =
        peer.closeCode == WebSocketStatus.policyViolation &&
        peer.closeReason != 'RATE_LIMITED';
    unawaited(_subscription?.cancel());
    _subscription = null;
    unawaited(peer.close());
    if (policyViolation) {
      if (++_policyFailures > 1) {
        onDisconnected();
      } else {
        // A near-expiry access token may rotate on the next connection.
        _scheduleRetry();
      }
      return;
    }
    _scheduleRetry();
  }

  void _scheduleRetry() {
    if (_closed) return;
    onDisconnected();
    _retry?.cancel();
    final delay = min(5000, 500 * (1 << min(_attempts++, 4)));
    _retry = Timer(Duration(milliseconds: delay), () => unawaited(_connect()));
  }

  void close() {
    if (_closed) return;
    _closed = true;
    _generation++;
    _retry?.cancel();
    unawaited(_subscription?.cancel());
    unawaited(_peer?.close());
    _peer = null;
  }
}
