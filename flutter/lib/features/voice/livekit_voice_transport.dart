import 'dart:async';

import 'package:livekit_client/livekit_client.dart' as lk;

import 'voice_api.dart';
import 'voice_transport.dart';

class LiveKitVoiceTransport implements VoiceTransport {
  final _events = StreamController<VoiceEvent>.broadcast();
  lk.Room? _room;
  lk.EventsListener<lk.RoomEvent>? _listener;
  final Map<lk.Room, Future<void>> _releases = {};
  final Set<Future<void>> _joins = {};
  bool _disposed = false;

  @override
  Stream<VoiceEvent> get events => _events.stream;

  @override
  bool get microphoneEnabled =>
      _room?.localParticipant?.isMicrophoneEnabled() ?? false;

  @override
  Future<void> join(VoiceGrant grant, {bool microphoneEnabled = true}) {
    final pending = _join(grant, microphoneEnabled: microphoneEnabled);
    _joins.add(pending);
    pending.then(
      (_) => _joins.remove(pending),
      onError: (Object _) => _joins.remove(pending),
    );
    return pending;
  }

  Future<void> _join(
    VoiceGrant grant, {
    required bool microphoneEnabled,
  }) async {
    await leave();
    if (_disposed) throw StateError('语音连接已关闭。');
    final room = lk.Room();
    final listener = room.createListener();
    _room = room;
    _listener = listener;
    listener
      ..on<lk.RoomReconnectingEvent>((_) {
        if (_room == room) {
          _events.add(const VoiceEvent(VoiceEventKind.reconnecting));
        }
      })
      ..on<lk.RoomReconnectedEvent>((_) {
        if (_room == room) {
          _events.add(const VoiceEvent(VoiceEventKind.reconnected));
        }
      })
      ..on<lk.RoomDisconnectedEvent>((event) {
        if (_room == room) {
          _events.add(
            VoiceEvent(
              event.reason == lk.DisconnectReason.roomDeleted
                  ? VoiceEventKind.roomDeleted
                  : VoiceEventKind.disconnected,
            ),
          );
        }
      })
      ..on<lk.ActiveSpeakersChangedEvent>((event) {
        if (_room == room) {
          final names = event.speakers
              .where(
                (speaker) =>
                    speaker.identity != room.localParticipant?.identity,
              )
              .map((speaker) => speaker.name.isEmpty ? '队友' : speaker.name)
              .join('、');
          _events.add(VoiceEvent(VoiceEventKind.speaking, names));
        }
      })
      ..on<lk.AudioPlaybackStatusChanged>((_) {
        if (_room == room && !room.canPlaybackAudio) {
          _events.add(const VoiceEvent(VoiceEventKind.playbackBlocked));
        }
      })
      ..on<lk.TrackSubscribedEvent>((event) {
        if (_room == room && event.track is lk.RemoteAudioTrack) {
          _events.add(const VoiceEvent(VoiceEventKind.remoteAudioSubscribed));
        }
      })
      ..on<lk.TrackUnsubscribedEvent>((event) {
        if (_room == room && event.track is lk.RemoteAudioTrack) {
          _events.add(const VoiceEvent(VoiceEventKind.remoteAudioUnsubscribed));
        }
      });
    try {
      await room.connect(grant.url, grant.token);
      if (_room != room || _disposed) throw StateError('语音连接已取消。');
      final local = room.localParticipant;
      if (local == null) throw StateError('语音身份没有连接成功。');
      if (microphoneEnabled) await local.setMicrophoneEnabled(true);
      if (_room != room ||
          _disposed ||
          local.isMicrophoneEnabled() != microphoneEnabled) {
        throw StateError('麦克风未能开启。');
      }
    } catch (_) {
      if (_room == room) {
        _room = null;
        _listener = null;
      }
      await _release(room, listener);
      rethrow;
    }
  }

  @override
  Future<void> microphone(bool enabled) async {
    final local = _room?.localParticipant;
    if (local == null) throw StateError('语音尚未连接。');
    await local.setMicrophoneEnabled(enabled);
    if (local.isMicrophoneEnabled() != enabled) throw StateError('麦克风状态未生效。');
  }

  @override
  Future<void> resumeAudio() async {
    final room = _room;
    if (room == null) throw StateError('语音尚未连接。');
    await room.startAudio();
  }

  @override
  Future<void> leave() async {
    final listener = _listener;
    final room = _room;
    _listener = null;
    _room = null;
    if (room == null) return;
    await _release(room, listener);
  }

  Future<void> _release(
    lk.Room room,
    lk.EventsListener<lk.RoomEvent>? listener,
  ) {
    final pending = _releases[room];
    if (pending != null) return pending;
    final future = () async {
      try {
        if (listener != null) await listener.dispose();
      } catch (_) {
        /* release capture despite listener failure */
      }
      try {
        await room.localParticipant?.setMicrophoneEnabled(false);
      } catch (_) {
        /* disconnect and dispose still release capture */
      }
      try {
        await room.disconnect();
      } catch (_) {
        /* dispose still releases capture */
      }
      try {
        await room.dispose();
      } catch (_) {
        /* the room is already disconnected */
      }
    }();
    _releases[room] = future;
    future.then((_) => _releases.remove(room));
    return future;
  }

  @override
  Future<void> dispose() async {
    _disposed = true;
    await leave();
    if (_joins.isNotEmpty) {
      await Future.wait(
        _joins.toList().map((future) => future.catchError((Object _) {})),
      );
    }
    await Future.wait(_releases.values.toList());
    await _events.close();
  }
}
