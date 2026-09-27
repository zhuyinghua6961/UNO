import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

typedef Json = Map<String, dynamic>;

void require(bool condition, String detail) {
  if (!condition) throw StateError(detail);
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

class Reply {
  Reply(this.status, this.body);
  final int status;
  final Json body;
}

Future<Reply> request(
  HttpClient client,
  Uri base,
  String path, {
  String method = 'GET',
  String? token,
  Json? body,
}) async {
  final uri = base.resolve(path);
  final outgoing = await client.openUrl(method, uri);
  outgoing.headers.set('X-UNO-Client', 'APP');
  if (token != null) outgoing.headers.set('Authorization', 'Bearer $token');
  if (body != null) {
    outgoing.headers.contentType = ContentType.json;
    outgoing.write(jsonEncode(body));
  }
  final incoming = await outgoing.close();
  final raw = await utf8.decoder.bind(incoming).join();
  final parsed = raw.isEmpty ? <String, dynamic>{} : jsonDecode(raw);
  return Reply(
    incoming.statusCode,
    parsed is Json ? parsed : <String, dynamic>{},
  );
}

Future<Json> next(StreamIterator<dynamic> events, String description) async {
  require(
    await events.moveNext().timeout(const Duration(seconds: 10)),
    '$description closed before the expected event',
  );
  final decoded = jsonDecode(events.current as String);
  require(decoded is Json, '$description returned a non-object event');
  return decoded as Json;
}

Future<WebSocket> connect(Uri base, String path, String token) {
  final uri = base
      .resolve(path)
      .replace(scheme: base.scheme == 'https' ? 'wss' : 'ws');
  return WebSocket.connect(
    uri.toString(),
    headers: {'X-UNO-Client': 'APP', 'Authorization': 'Bearer $token'},
  );
}

Future<String> docker(List<String> args) async {
  final result = await Process.run('docker', args);
  require(
    result.exitCode == 0,
    'docker ${args.first} failed: ${result.stderr}',
  );
  return (result.stdout as String).trim();
}

Future<void> verifyCrashTarget(String container, Uri first) async {
  require(
    RegExp(r'^uno-stage12-multi-game-service-[0-9]+$').hasMatch(container),
    'Crash test only accepts an isolated uno-stage12-multi Game container',
  );
  final identity = await docker([
    'inspect',
    '--format',
    '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}',
    container,
  ]);
  require(
    identity == 'uno-stage12-multi|game-service|running',
    'Crash target must be a running Game container in uno-stage12-multi',
  );
  final published = await docker(['port', container, '8082/tcp']);
  require(
    first.host == '127.0.0.1' &&
        published.split('\n').any((line) => line == '127.0.0.1:${first.port}'),
    'Game A URL must match the isolated container localhost port',
  );
}

Future<void> waitForReadiness(HttpClient client, String container) async {
  final deadline = DateTime.now().add(const Duration(seconds: 30));
  while (DateTime.now().isBefore(deadline)) {
    try {
      final published = await docker(['port', container, '8082/tcp']);
      final address = published
          .split('\n')
          .firstWhere((line) => line.startsWith('127.0.0.1:'));
      final response = await request(
        client,
        Uri.parse('http://$address'),
        '/actuator/health/readiness',
      );
      if (response.status == 200 && response.body['status'] == 'UP') return;
    } catch (_) {
      // A newly restarted container may not accept connections yet.
    }
    await Future<void>.delayed(const Duration(milliseconds: 500));
  }
  throw StateError('Restarted Game A did not become ready within 30 seconds');
}

Future<void> verifyCrashRecovery(
  HttpClient client,
  Uri first,
  Uri second,
  String matchId,
  String actorToken,
  Json action,
  String subscribe,
  String command,
  String container,
) async {
  await verifyCrashTarget(container, first);
  final oldSocket = await connect(first, '/ws/game', actorToken);
  final oldEvents = StreamIterator<dynamic>(oldSocket);
  var stopped = false;
  try {
    oldSocket.add(subscribe);
    final initial = await next(oldEvents, 'Game A before crash');
    require(
      initial['type'] == 'MATCH_SNAPSHOT',
      'Game A rejected subscription',
    );
    final oldView = initial['view'] as Json;
    require(
      oldView['version'] == 1,
      'Initial match version changed unexpectedly',
    );

    await docker(['stop', '--time', '0', container]);
    stopped = true;
    final blocked = await request(
      client,
      second,
      '/api/matches/$matchId/commands',
      method: 'POST',
      token: actorToken,
      body: action,
    );
    require(
      blocked.status == 409 && blocked.body['code'] == 'MATCH_SOCKET_OWNED',
      'Game B HTTP command bypassed the crashed connection owner',
    );

    final newSocket = await connect(second, '/ws/game', actorToken);
    final newEvents = StreamIterator<dynamic>(newSocket);
    try {
      newSocket.add(subscribe);
      final recovered = await next(newEvents, 'Game B after crash');
      require(
        recovered['type'] == 'MATCH_SNAPSHOT',
        'Game B rejected subscription after Game A crashed',
      );
      final newView = recovered['view'] as Json;
      require(
        newView['version'] == oldView['version'] &&
            newView['deadlineAt'] == oldView['deadlineAt'] &&
            jsonEncode(newView['ownHand']) == jsonEncode(oldView['ownHand']),
        'Recovered match lost its version, deadline, or private hand',
      );
      newSocket.add(command);
      final acknowledged = await next(newEvents, 'Game B recovered command');
      require(
        acknowledged['type'] == 'COMMAND_ACK' &&
            (acknowledged['result'] as Json)['appliedVersion'] == 2,
        'Game B did not apply the next action after Game A crashed',
      );
      await newSocket.close();
      await newEvents.cancel();
      final retry = await request(
        client,
        second,
        '/api/matches/$matchId/commands',
        method: 'POST',
        token: actorToken,
        body: action,
      );
      require(
        retry.status == 200 && retry.body['duplicate'] == true,
        'Game B HTTP retry did not find the recovered command',
      );
    } finally {
      await newSocket.close();
      await newEvents.cancel();
    }
  } finally {
    try {
      if (stopped) {
        await docker(['start', container]);
        await waitForReadiness(client, container);
      }
    } finally {
      try {
        await oldSocket.close();
        await oldEvents.cancel();
      } catch (_) {
        // The container stop can sever the old socket without a close frame.
      }
    }
  }
}

Future<void> main() async {
  final env = Platform.environment;
  final gateway = Uri.parse(env['UNO_GATEWAY_URL']!);
  final first = Uri.parse(env['GAME_INSTANCE_A_URL']!);
  final second = Uri.parse(env['GAME_INSTANCE_B_URL']!);
  final roomId = env['UNO_ROOM_ID']!;
  final host = env['UNO_HOST_TOKEN']!;
  final guest = env['UNO_GUEST_TOKEN']!;
  require(first != second, 'Game instances must have different addresses');
  final client = HttpClient();
  try {
    final chat = await connect(second, '/ws/chat', guest);
    final chatEvents = StreamIterator<dynamic>(chat);
    try {
      chat.add(
        jsonEncode({
          'protocolVersion': 1,
          'type': 'SUBSCRIBE',
          'roomId': roomId,
        }),
      );
      require(
        (await next(chatEvents, 'remote chat'))['type'] == 'CHAT_SUBSCRIBED',
        'Remote Game instance rejected chat subscription',
      );
      final messageId = randomId();
      final sent = await request(
        client,
        first,
        '/api/rooms/$roomId/messages',
        method: 'POST',
        token: host,
        body: {
          'clientMessageId': messageId,
          'channel': 'ROOM',
          'content': 'Two-container message',
        },
      );
      require(
        sent.status == 200,
        'First Game instance rejected room text: ${sent.status}',
      );
      final delivered = await next(chatEvents, 'remote chat delivery');
      require(
        delivered['type'] == 'CHAT_MESSAGE' &&
            (delivered['item'] as Json)['clientMessageId'] == messageId,
        'Second Game instance did not deliver the committed message',
      );
    } finally {
      await chat.close();
      await chatEvents.cancel();
    }

    var waiting = await request(
      client,
      gateway,
      '/api/rooms/$roomId',
      token: host,
    );
    require(waiting.status == 200, 'Waiting room lookup failed');
    for (final token in [host, guest]) {
      waiting = await request(
        client,
        gateway,
        '/api/rooms/$roomId/ready',
        method: 'POST',
        token: token,
        body: {'ready': true, 'expectedVersion': waiting.body['version']},
      );
      require(waiting.status == 200, 'Ready failed: ${waiting.status}');
    }
    final started = await request(
      client,
      gateway,
      '/api/rooms/$roomId/start',
      method: 'POST',
      token: host,
      body: {'expectedVersion': waiting.body['version']},
    );
    require(started.status == 200, 'Match start failed: ${started.status}');
    final matchId = started.body['matchId'] as String;
    final view = started.body['view'] as Json;
    final players = view['players'] as List<dynamic>;
    final actorId = (players[view['currentSeat'] as int] as Json)['userId'];
    final hostProfile = await request(
      client,
      gateway,
      '/api/users/me',
      token: host,
    );
    require(hostProfile.status == 200, 'Host identity lookup failed');
    final actorToken = actorId == hostProfile.body['id'] ? host : guest;
    final actorState = await request(
      client,
      gateway,
      '/api/matches/$matchId/state',
      token: actorToken,
    );
    require(actorState.status == 200, 'Actor state lookup failed');
    final actorView = actorState.body['view'] as Json;
    final action = <String, dynamic>{
      'protocolVersion': 1,
      'commandId': randomId(),
      'expectedVersion': actorView['version'],
      'type': actorView['phase'] == 'INITIAL_WILD_COLOR'
          ? 'CHOOSE_INITIAL_COLOR'
          : 'DRAW',
    };
    if (action['type'] == 'CHOOSE_INITIAL_COLOR') action['chosenColor'] = 'RED';
    final subscribe = jsonEncode({
      'protocolVersion': 1,
      'type': 'SUBSCRIBE',
      'matchId': matchId,
    });
    final command = jsonEncode({
      'protocolVersion': 1,
      'type': 'COMMAND',
      'matchId': matchId,
      'command': action,
    });

    if (env['SMOKE_GAME_CRASH'] == 'true') {
      await verifyCrashRecovery(
        client,
        first,
        second,
        matchId,
        actorToken,
        action,
        subscribe,
        command,
        env['GAME_INSTANCE_A_CONTAINER']!,
      );
    } else {
      final oldSocket = await connect(first, '/ws/game', actorToken);
      final newSocket = await connect(second, '/ws/game', actorToken);
      final oldEvents = StreamIterator<dynamic>(oldSocket);
      final newEvents = StreamIterator<dynamic>(newSocket);
      try {
        oldSocket.add(subscribe);
        require(
          (await next(oldEvents, 'first Game snapshot'))['type'] ==
              'MATCH_SNAPSHOT',
          'First Game instance rejected match subscription',
        );
        newSocket.add(subscribe);
        require(
          (await next(newEvents, 'second Game snapshot'))['type'] ==
              'MATCH_SNAPSHOT',
          'Second Game instance rejected takeover',
        );
        final blocked = await request(
          client,
          first,
          '/api/matches/$matchId/commands',
          method: 'POST',
          token: actorToken,
          body: action,
        );
        require(
          blocked.status == 409 && blocked.body['code'] == 'MATCH_SOCKET_OWNED',
          'HTTP action bypassed current WebSocket owner',
        );
        if (oldSocket.readyState == WebSocket.open) oldSocket.add(command);
        require(
          !await oldEvents.moveNext().timeout(const Duration(seconds: 10)),
          'Old Game socket received an action response after takeover',
        );
        require(
          oldSocket.closeCode == 4001 && oldSocket.closeReason == 'TAKEN_OVER',
          'Old Game socket was not closed as taken over',
        );
        newSocket.add(command);
        final acknowledged = await next(newEvents, 'new Game command');
        require(
          acknowledged['type'] == 'COMMAND_ACK' &&
              (acknowledged['result'] as Json)['appliedVersion'] == 2,
          'New Game socket could not submit the next action',
        );
        await newSocket.close();
        await newEvents.cancel();
        final retry = await request(
          client,
          first,
          '/api/matches/$matchId/commands',
          method: 'POST',
          token: actorToken,
          body: action,
        );
        require(
          retry.status == 200 && retry.body['duplicate'] == true,
          'HTTP retry did not recover the committed action after socket close',
        );
      } finally {
        await oldSocket.close();
        await newSocket.close();
        await oldEvents.cancel();
        await newEvents.cancel();
      }
    }
    final departed = await request(
      client,
      gateway,
      '/api/matches/$matchId/leave',
      method: 'POST',
      token: guest,
    );
    require(
      departed.status == 200 && departed.body['status'] == 'INTERRUPTED',
      'Multi-instance smoke match did not interrupt on deliberate departure',
    );
    print(
      env['SMOKE_GAME_CRASH'] == 'true'
          ? 'PASS: Game A crash preserves private state; Game B reclaims, applies and deduplicates the action.'
          : 'PASS: two Game containers share chat, enforce takeover, gate HTTP, and preserve command retry.',
    );
  } finally {
    client.close(force: true);
  }
}
