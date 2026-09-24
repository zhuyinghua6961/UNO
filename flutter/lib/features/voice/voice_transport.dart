import 'voice_api.dart';

enum VoiceEventKind {
  reconnecting,
  reconnected,
  disconnected,
  roomDeleted,
  speaking,
  playbackBlocked,
}

class VoiceEvent {
  const VoiceEvent(this.kind, [this.detail = '']);
  final VoiceEventKind kind;
  final String detail;
}

abstract interface class VoiceTransport {
  Stream<VoiceEvent> get events;
  bool get microphoneEnabled;
  Future<void> join(VoiceGrant grant, {bool microphoneEnabled = true});
  Future<void> microphone(bool enabled);
  Future<void> resumeAudio();
  Future<void> leave();
  Future<void> dispose();
}
