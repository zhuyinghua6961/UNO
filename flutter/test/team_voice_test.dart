import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/voice/team_voice_panel.dart';
import 'package:uno_app/features/voice/voice_api.dart';
import 'package:uno_app/features/voice/voice_device_permission.dart';
import 'package:uno_app/features/voice/voice_transport.dart';

class _TokenStore implements TokenStore {
  @override
  Future<StoredTokens?> read() async => StoredTokens(
    'access',
    'refresh',
    DateTime.now().add(const Duration(hours: 1)),
    DateTime.now().add(const Duration(days: 1)),
  );
  @override
  Future<void> write(StoredTokens tokens) async {}
  @override
  Future<void> clear() async {}
}

AuthSession _session() {
  final session = AuthSession(
    api: AuthApi(client: MockClient((_) async => http.Response('', 404))),
    store: _TokenStore(),
  );
  session.state = SessionState.authenticated;
  session.user = const AuthUser('user-1', 'a@example.com', 'Alice');
  return session;
}

class _FakeVoiceApi extends VoiceApi {
  _FakeVoiceApi(AuthSession session) : super(session: session);
  int tokenCalls = 0;
  @override
  Future<bool> available() async => true;
  @override
  Future<VoiceGrant> token(String matchId) async {
    tokenCalls++;
    return VoiceGrant(
      'ws://localhost:7880',
      'a.b.c',
      DateTime.now().add(const Duration(minutes: 1)),
    );
  }
}

class _FakeTransport implements VoiceTransport {
  final controller = StreamController<VoiceEvent>.broadcast();
  final List<bool> micCalls = [];
  int joins = 0;
  int leaves = 0;
  final List<bool> joinMicrophones = [];
  bool disposed = false;
  bool mic = false;
  Completer<void>? joinGate;

  @override
  Stream<VoiceEvent> get events => controller.stream;
  @override
  bool get microphoneEnabled => mic;
  @override
  Future<void> join(VoiceGrant grant, {bool microphoneEnabled = true}) async {
    joins++;
    joinMicrophones.add(microphoneEnabled);
    await joinGate?.future;
    mic = microphoneEnabled;
  }

  @override
  Future<void> microphone(bool enabled) async {
    micCalls.add(enabled);
    mic = enabled;
  }

  @override
  Future<void> resumeAudio() async {}
  @override
  Future<void> leave() async {
    leaves++;
    mic = false;
  }

  @override
  Future<void> dispose() async {
    disposed = true;
    await leave();
    await controller.close();
  }

  void emit(VoiceEventKind kind) => controller.add(VoiceEvent(kind));
}

class _FakeDevicePermission implements VoiceDevicePermission {
  _FakeDevicePermission(this.allowed);
  final bool allowed;
  int calls = 0;
  @override
  Future<bool> prepareBluetooth() async {
    calls++;
    return allowed;
  }
}

void main() {
  test(
    'voice API uses APP bearer identity and only matchId in the grant request',
    () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        if (request.url.path == '/api/auth/status') {
          return http.Response(
            jsonEncode({
              'service': 'identity-service',
              'loginAvailable': true,
              'registrationAvailable': true,
            }),
            200,
          );
        }
        if (request.url.path == '/api/users/me') {
          return http.Response(
            jsonEncode({
              'id': 'user-1',
              'email': 'a@example.com',
              'nickname': 'Alice',
            }),
            200,
          );
        }
        if (request.url.path == '/api/system/bootstrap') {
          return http.Response(
            jsonEncode({
              'features': {'teamVoice': true},
            }),
            200,
          );
        }
        return http.Response(
          jsonEncode({
            'url': 'wss://voice.example.test',
            'token': 'a.b.c',
            'expiresAt': DateTime.now()
                .add(const Duration(seconds: 60))
                .toUtc()
                .toIso8601String(),
          }),
          200,
        );
      });
      final session = AuthSession(
        api: AuthApi(client: client, baseUrl: 'https://uno.example.test'),
        store: _TokenStore(),
      );
      await session.initialize();
      final api = VoiceApi(
        session: session,
        client: client,
        baseUrl: 'https://uno.example.test',
      );
      expect(await api.available(), true);
      expect((await api.token('match-1')).url, 'wss://voice.example.test');
      final grantRequest = requests.singleWhere(
        (request) => request.url.path == '/api/voice/token',
      );
      expect(grantRequest.method, 'POST');
      expect(grantRequest.headers['authorization'], 'Bearer access');
      expect(grantRequest.headers['x-uno-client'], 'APP');
      expect(jsonDecode(grantRequest.body), {'matchId': 'match-1'});
      expect(grantRequest.headers.containsKey('cookie'), false);
      api.close();
      session.dispose();
    },
  );

  testWidgets(
    'listen-only joins without microphone capture and opens it on demand',
    (tester) async {
      final session = _session();
      final api = _FakeVoiceApi(session);
      final transport = _FakeTransport();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TeamVoicePanel(
              matchId: 'match-1',
              session: session,
              api: api,
              transportFactory: () => transport,
              devicePermission: _FakeDevicePermission(true),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.text('仅收听'));
      await tester.pump();
      expect(transport.joinMicrophones, [false]);
      expect(transport.mic, false);
      expect(find.text('已加入 · 麦克风关闭'), findsOneWidget);
      await tester.tap(find.text('打开麦克风'));
      await tester.pump();
      expect(transport.micCalls, [true]);
      expect(find.text('已加入 · 麦克风开启'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      api.close();
      session.dispose();
    },
  );

  testWidgets(
    'voice opens only on click, mute survives reconnect, background and disposal release it',
    (tester) async {
      final session = _session();
      final api = _FakeVoiceApi(session);
      final transport = _FakeTransport();
      final permission = _FakeDevicePermission(false);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TeamVoicePanel(
              matchId: 'match-1',
              session: session,
              api: api,
              transportFactory: () => transport,
              devicePermission: permission,
            ),
          ),
        ),
      );
      await tester.pump();
      expect(api.tokenCalls, 0);
      expect(transport.joins, 0);
      expect(transport.mic, false);
      expect(permission.calls, 0);
      await tester.tap(find.text('加入队友语音'));
      await tester.pump();
      expect(api.tokenCalls, 1);
      expect(transport.joins, 1);
      expect(transport.mic, true);
      expect(permission.calls, 1);
      expect(find.text('蓝牙权限未开放；可继续尝试使用手机扬声器。'), findsOneWidget);
      await tester.tap(find.text('关闭麦克风'));
      await tester.pump();
      expect(transport.micCalls, [false]);
      expect(transport.mic, false);
      transport.emit(VoiceEventKind.reconnecting);
      await tester.pump();
      transport.emit(VoiceEventKind.reconnected);
      await tester.pump();
      expect(find.text('已加入 · 麦克风关闭'), findsOneWidget);
      await tester.tap(find.text('打开麦克风'));
      await tester.pump();
      expect(transport.micCalls, [false, true]);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(transport.mic, false);
      expect(find.text('麦克风关闭'), findsOneWidget);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.tap(find.text('加入队友语音'));
      await tester.pump();
      expect(transport.mic, true);
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      expect(transport.disposed, true);
      expect(transport.mic, false);
      api.close();
      session.dispose();
    },
  );

  testWidgets(
    'room deletion gets a fresh grant and preserves an explicitly muted microphone',
    (tester) async {
      final session = _session();
      final api = _FakeVoiceApi(session);
      final transport = _FakeTransport();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TeamVoicePanel(
              matchId: 'match-1',
              session: session,
              api: api,
              transportFactory: () => transport,
              devicePermission: _FakeDevicePermission(true),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.text('加入队友语音'));
      await tester.pump();
      await tester.tap(find.text('关闭麦克风'));
      await tester.pump();
      transport.emit(VoiceEventKind.roomDeleted);
      await tester.pump();
      expect(transport.mic, false);
      expect(api.tokenCalls, 1);
      await tester.pump(const Duration(milliseconds: 3400));
      await tester.pump();
      expect(api.tokenCalls, 2);
      expect(transport.joinMicrophones, [true, false]);
      expect(transport.mic, false);
      expect(find.text('已加入 · 麦克风关闭'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      api.close();
      session.dispose();
    },
  );

  testWidgets('leaving during room replacement cancels the automatic rejoin', (
    tester,
  ) async {
    final session = _session();
    final api = _FakeVoiceApi(session);
    final transport = _FakeTransport();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: TeamVoicePanel(
            matchId: 'match-1',
            session: session,
            api: api,
            transportFactory: () => transport,
            devicePermission: _FakeDevicePermission(true),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.text('加入队友语音'));
    await tester.pump();
    transport.emit(VoiceEventKind.roomDeleted);
    await tester.pump();
    await tester.tap(find.text('退出语音'));
    await tester.pump(const Duration(milliseconds: 3400));
    expect(api.tokenCalls, 1);
    expect(transport.mic, false);
    await tester.pumpWidget(const SizedBox());
    api.close();
    session.dispose();
  });

  testWidgets(
    'leaving while joining does not let a stale request keep the mic open',
    (tester) async {
      final session = _session();
      final api = _FakeVoiceApi(session);
      final transport = _FakeTransport()..joinGate = Completer<void>();
      final permission = _FakeDevicePermission(true);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TeamVoicePanel(
              matchId: 'match-1',
              session: session,
              api: api,
              transportFactory: () => transport,
              devicePermission: permission,
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.text('加入队友语音'));
      await tester.pump();
      expect(transport.joins, 1);
      await tester.tap(find.text('退出语音'));
      await tester.pump();
      transport.joinGate!.complete();
      await tester.pump();
      expect(transport.mic, false);
      expect(find.text('麦克风关闭'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      api.close();
      session.dispose();
    },
  );
}
