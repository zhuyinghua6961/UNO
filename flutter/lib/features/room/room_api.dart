import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';
import '../auth/auth_api.dart';
import '../auth/auth_session.dart';

class RoomMember {
  RoomMember(this.userId, this.nickname, this.seat, this.team, this.ready);
  final String userId;
  final String nickname;
  final int seat;
  final String? team;
  final bool ready;
}

class WaitingRoom {
  WaitingRoom(
    this.id,
    this.code,
    this.mode,
    this.maxPlayers,
    this.hostUserId,
    this.state,
    this.version,
    this.expiresAt,
    this.canStart,
    this.members,
  );
  final String id;
  final String code;
  final String mode;
  final int maxPlayers;
  final String hostUserId;
  final String state;
  final int version;
  final DateTime expiresAt;
  final bool canStart;
  final List<RoomMember> members;

  static WaitingRoom parse(Object? value) {
    if (value
        case {
          'id': String id,
          'code': String code,
          'mode': String mode,
          'maxPlayers': int maxPlayers,
          'hostUserId': String hostUserId,
          'state': String state,
          'version': int version,
          'expiresAt': String expiry,
          'canStart': bool canStart,
          'members': List<dynamic> rawMembers,
        }
        when RegExp(r'^[0-9a-f-]{36}$', caseSensitive: false).hasMatch(id) &&
            RegExp(r'^[A-HJ-NP-Z2-9]{10}$').hasMatch(code) &&
            (mode == 'CLASSIC' || mode == 'TEAM_2V2') &&
            maxPlayers >= 2 &&
            maxPlayers <= 6 &&
            version >= 1) {
      final expiresAt = DateTime.tryParse(expiry);
      if (expiresAt == null) {
        throw const AuthFailure(502, 'INVALID_RESPONSE', '房间数据异常，请稍后重试。');
      }
      final members = <RoomMember>[];
      for (final raw in rawMembers) {
        if (raw
            case {
              'userId': String userId,
              'nickname': String nickname,
              'seat': int seat,
              'team': final Object? team,
              'ready': bool ready,
            }
            when (team == null || team == 'A' || team == 'B') &&
                seat >= 0 &&
                seat < maxPlayers) {
          members.add(
            RoomMember(userId, nickname, seat, team as String?, ready),
          );
        } else {
          throw const AuthFailure(502, 'INVALID_RESPONSE', '房间成员信息异常，请稍后重试。');
        }
      }
      return WaitingRoom(
        id,
        code,
        mode,
        maxPlayers,
        hostUserId,
        state,
        version,
        expiresAt,
        canStart,
        members,
      );
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '房间数据异常，请稍后重试。');
  }
}

class RoomChatMessage {
  RoomChatMessage(
    this.id,
    this.roomId,
    this.channel,
    this.sequence,
    this.senderUserId,
    this.senderNickname,
    this.clientMessageId,
    this.content,
    this.createdAt,
  );
  final String id;
  final String roomId;
  final String channel;
  final int sequence;
  final String senderUserId;
  final String senderNickname;
  final String clientMessageId;
  final String content;
  final DateTime createdAt;

  static RoomChatMessage parse(Object? value) {
    if (value
        case {
          'id': String id,
          'roomId': String roomId,
          'channel': String channel,
          'sequence': int sequence,
          'senderUserId': String senderUserId,
          'senderNickname': String senderNickname,
          'clientMessageId': String clientMessageId,
          'content': String content,
          'createdAt': String timestamp,
        }
        when sequence > 0 &&
            const ['ROOM', 'TEAM_A', 'TEAM_B'].contains(channel)) {
      final createdAt = DateTime.tryParse(timestamp);
      if (createdAt != null) {
        return RoomChatMessage(
          id,
          roomId,
          channel,
          sequence,
          senderUserId,
          senderNickname,
          clientMessageId,
          content,
          createdAt,
        );
      }
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '消息数据异常，请稍后重试。');
  }
}

class RoomChatPage {
  RoomChatPage(this.items, this.nextSequence, this.hasMore);
  final List<RoomChatMessage> items;
  final int nextSequence;
  final bool hasMore;

  static RoomChatPage parse(Object? value) {
    if (value
        case {
          'items': List<dynamic> raw,
          'nextSequence': int nextSequence,
          'hasMore': bool hasMore,
        }
        when nextSequence >= 0) {
      final items = raw.map(RoomChatMessage.parse).toList();
      for (var index = 1; index < items.length; index++) {
        if (items[index].sequence <= items[index - 1].sequence) {
          throw const AuthFailure(502, 'INVALID_RESPONSE', '消息顺序异常，请稍后重试。');
        }
      }
      return RoomChatPage(items, nextSequence, hasMore);
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '消息数据异常，请稍后重试。');
  }
}

class RoomApi {
  RoomApi({required this.session, http.Client? client, String? baseUrl})
    : _client = client ?? http.Client(),
      _baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final AuthSession session;
  final http.Client _client;
  final String _baseUrl;

  Uri get chatSocketUri {
    final base = Uri.parse(_baseUrl);
    return base.replace(
      scheme: base.scheme == 'https' ? 'wss' : 'ws',
      path: '/ws/chat',
      query: null,
      fragment: null,
    );
  }

  Future<Object?> _request(String path, {Object? body, bool post = false}) =>
      session.withAccess((token) async {
        late http.Response response;
        try {
          final request =
              http.Request(post ? 'POST' : 'GET', Uri.parse('$_baseUrl$path'))
                ..followRedirects = false
                ..headers.addAll({
                  'X-UNO-Client': 'APP',
                  'Authorization': 'Bearer $token',
                  'Accept': 'application/json',
                  if (post) 'Content-Type': 'application/json',
                });
          if (post) request.body = jsonEncode(body ?? <String, Object>{});
          final streamed = await _client
              .send(request)
              .timeout(const Duration(seconds: 12));
          response = await http.Response.fromStream(streamed);
        } on SocketException catch (_) {
          throw const AuthFailure(0, 'NETWORK_ERROR', '房间连接失败，请检查网络后重试。');
        } on HandshakeException catch (_) {
          throw const AuthFailure(0, 'NETWORK_ERROR', '安全连接失败，请检查服务器证书。');
        } catch (_) {
          throw const AuthFailure(
            0,
            'NETWORK_ERROR',
            '房间连接失败或超时；提交的操作可能已生效，请刷新状态。',
          );
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
            /* use generic error */
          }
          final message = switch (response.statusCode) {
            401 => '登录已失效，请重新登录。',
            403 when code == 'CHAT_MUTED' => '当前账号暂不能发送文字消息。',
            403 when code != 'ROOM_FORBIDDEN' => '安全校验未通过，请重试。',
            >= 500 => '房间服务暂不可用，请稍后重试。',
            _ => detail ?? '房间操作失败，请刷新后重试。',
          };
          throw AuthFailure(response.statusCode, code, message);
        }
        if (response.statusCode == 204) return null;
        try {
          return jsonDecode(response.body);
        } catch (_) {
          throw const AuthFailure(502, 'INVALID_RESPONSE', '房间数据异常，请稍后重试。');
        }
      });

  Future<WaitingRoom?> current() async {
    final value = await _request('/api/rooms/current');
    return value == null ? null : WaitingRoom.parse(value);
  }

  Future<WaitingRoom> get(String id) async =>
      WaitingRoom.parse(await _request('/api/rooms/$id'));
  Future<WaitingRoom> create(String mode, int maxPlayers) async =>
      WaitingRoom.parse(
        await _request(
          '/api/rooms',
          post: true,
          body: {'mode': mode, 'maxPlayers': maxPlayers},
        ),
      );
  Future<WaitingRoom> join(String code) async => WaitingRoom.parse(
    await _request('/api/rooms/join', post: true, body: {'code': code}),
  );
  Future<void> leave(String id) async {
    await _request('/api/rooms/$id/leave', post: true);
  }

  Future<WaitingRoom> ready(WaitingRoom room, bool ready) async =>
      WaitingRoom.parse(
        await _request(
          '/api/rooms/${room.id}/ready',
          post: true,
          body: {'ready': ready, 'expectedVersion': room.version},
        ),
      );
  Future<WaitingRoom> team(WaitingRoom room, String team) async =>
      WaitingRoom.parse(
        await _request(
          '/api/rooms/${room.id}/team',
          post: true,
          body: {'team': team, 'expectedVersion': room.version},
        ),
      );
  Future<WaitingRoom> settings(WaitingRoom room, int maxPlayers) async =>
      WaitingRoom.parse(
        await _request(
          '/api/rooms/${room.id}/settings',
          post: true,
          body: {'maxPlayers': maxPlayers, 'expectedVersion': room.version},
        ),
      );

  Future<RoomChatPage> messages(
    String roomId, {
    int after = 0,
    bool latest = false,
    String scope = 'ROOM',
  }) async {
    final page = RoomChatPage.parse(
      await _request(
        '/api/rooms/$roomId/messages?after=$after&limit=50&latest=$latest${scope == 'TEAM' ? '&channel=TEAM' : ''}',
      ),
    );
    if (page.items.any(
      (message) => scope == 'ROOM'
          ? message.channel != 'ROOM'
          : message.channel == 'ROOM',
    )) {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '消息频道异常，请稍后重试。');
    }
    return page;
  }

  Future<RoomChatMessage> sendMessage(
    String roomId,
    String clientMessageId,
    String content, {
    String scope = 'ROOM',
  }) async {
    final message = RoomChatMessage.parse(
      await _request(
        '/api/rooms/$roomId/messages',
        post: true,
        body: {
          'clientMessageId': clientMessageId,
          'content': content,
          if (scope == 'TEAM') 'channel': 'TEAM',
        },
      ),
    );
    if (scope == 'ROOM'
        ? message.channel != 'ROOM'
        : message.channel == 'ROOM') {
      throw const AuthFailure(502, 'INVALID_RESPONSE', '消息频道异常，请稍后重试。');
    }
    return message;
  }

  Future<void> reportMessage(
    String roomId,
    String messageId,
    String reason,
  ) async {
    final receipt = await _request(
      '/api/rooms/$roomId/messages/$messageId/reports',
      post: true,
      body: {'reason': reason},
    );
    if (receipt case {'id': String id, 'status': String status}
        when id.isNotEmpty && (status == 'OPEN' || status == 'RESOLVED')) {
      return;
    }
    throw const AuthFailure(502, 'INVALID_RESPONSE', '举报结果异常，请稍后重试。');
  }

  void close() => _client.close();
}
