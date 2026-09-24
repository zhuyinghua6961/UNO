import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

Future<Map<String, dynamic>> next(StreamIterator<dynamic> stream) async {
  if (!await stream.moveNext().timeout(const Duration(seconds: 10))) {
    throw StateError('Chat WebSocket closed before its next event');
  }
  final value = jsonDecode(stream.current as String);
  if (value is! Map<String, dynamic>) {
    throw StateError('Chat WebSocket returned a non-object event');
  }
  return value;
}

String randomId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

void expect(bool condition, String description) {
  if (!condition) throw StateError(description);
}

Future<void> main() async {
  final env = Platform.environment;
  final api = Uri.parse(env['UNO_WS_GATEWAY_URL']!);
  final roomId = env['UNO_WS_ROOM_ID']!;
  final endpoint = api.replace(
    scheme: api.scheme == 'https' ? 'wss' : 'ws',
    path: '/ws/chat',
    query: null,
    fragment: null,
  );
  Future<WebSocket> connect(String token) => WebSocket.connect(
    endpoint.toString(),
    headers: {'X-UNO-Client': 'APP', 'Authorization': 'Bearer $token'},
  );

  final host = await connect(env['UNO_WS_HOST_TOKEN']!);
  final guest = await connect(env['UNO_WS_GUEST_TOKEN']!);
  final hostEvents = StreamIterator<dynamic>(host);
  final guestEvents = StreamIterator<dynamic>(guest);
  try {
    final subscribe = jsonEncode({
      'protocolVersion': 1,
      'type': 'SUBSCRIBE',
      'roomId': roomId,
    });
    host.add(subscribe);
    guest.add(subscribe);
    expect(
      (await next(hostEvents))['type'] == 'CHAT_SUBSCRIBED',
      'Host chat subscription failed',
    );
    expect(
      (await next(guestEvents))['type'] == 'CHAT_SUBSCRIBED',
      'Guest chat subscription failed',
    );

    final clientId = randomId();
    host.add(
      jsonEncode({
        'protocolVersion': 1,
        'type': 'CHAT_SEND',
        'roomId': roomId,
        'clientMessageId': clientId,
        'channel': 'ROOM',
        'content': 'Gateway chat WebSocket smoke',
      }),
    );
    final hostEvent = await next(hostEvents);
    final ack = await next(hostEvents);
    final guestEvent = await next(guestEvents);
    expect(
      hostEvent['type'] == 'CHAT_MESSAGE' &&
          ack['type'] == 'CHAT_ACK' &&
          guestEvent['type'] == 'CHAT_MESSAGE',
      'Room chat send was not broadcast and acknowledged',
    );
    final item = hostEvent['item'] as Map<String, dynamic>;
    final guestItem = guestEvent['item'] as Map<String, dynamic>;
    expect(
      item['id'] == guestItem['id'] &&
          item['clientMessageId'] == clientId &&
          item['roomId'] == roomId &&
          item['channel'] == 'ROOM' &&
          item['sequence'] is int &&
          DateTime.tryParse(item['createdAt'] as String) != null,
      'Chat message fields differ across gateway subscribers',
    );
    print(
      'PASS: gateway Chat WebSocket subscribe, send, ACK and peer broadcast.',
    );
  } finally {
    await host.close();
    await guest.close();
    await hostEvents.cancel();
    await guestEvents.cancel();
  }
}
