import 'package:flutter/material.dart';

import '../../shared/game_mode.dart';

class RoomPreviewPage extends StatefulWidget {
  const RoomPreviewPage({super.key, required this.mode});
  final GameMode mode;

  @override
  State<RoomPreviewPage> createState() => _RoomPreviewPageState();
}

class _RoomPreviewPageState extends State<RoomPreviewPage> {
  bool teamChannel = false;

  @override
  Widget build(BuildContext context) {
    final isTeam = widget.mode == GameMode.team2v2;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Text(
          '${widget.mode.label} · 布局预览',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const Text('示例手牌，不是实时对局', style: TextStyle(color: Colors.black54)),
        const SizedBox(height: 20),
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: const Color(0xff416453),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Column(
            children: [
              Text(
                isTeam ? '对手 B    队友 C    对手 D' : '玩家 B    玩家 C    玩家 D',
                style: const TextStyle(color: Colors.white),
              ),
              const SizedBox(height: 30),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Image.asset('assets/cards/back.png', height: 110),
                  const SizedBox(width: 22),
                  Image.asset('assets/cards/red-5.png', height: 110),
                ],
              ),
              const SizedBox(height: 30),
              const Text(
                '发牌、出牌和回合同步待接入',
                style: TextStyle(color: Colors.white70, fontSize: 12),
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        Text('牌桌聊天', style: Theme.of(context).textTheme.titleMedium),
        Wrap(
          spacing: 12,
          children: [
            ChoiceChip(
              label: const Text('房间'),
              selected: !teamChannel,
              onSelected: (_) => setState(() => teamChannel = false),
            ),
            if (isTeam)
              ChoiceChip(
                label: const Text('队伍'),
                selected: teamChannel,
                onSelected: (_) => setState(() => teamChannel = true),
              ),
          ],
        ),
        const SizedBox(height: 10),
        Text(teamChannel && isTeam ? '只和队友交流，消息功能尚未接入。' : '和同桌的人聊聊，消息功能尚未接入。'),
        const SizedBox(height: 12),
        const TextField(
          enabled: false,
          decoration: InputDecoration(
            border: OutlineInputBorder(),
            hintText: '消息功能建设中',
          ),
        ),
        const SizedBox(height: 22),
        const Text('队友语音', style: TextStyle(fontWeight: FontWeight.bold)),
        Text(isTeam ? '仅队友可听见 · 不自动打开麦克风' : '仅在四人 2v2 模式中开放'),
        const SizedBox(height: 10),
        FilledButton.icon(
          onPressed: null,
          icon: const Icon(Icons.mic_off_outlined),
          label: Text(isTeam ? '语音接入中 · 麦克风关闭' : '当前模式不支持队友语音'),
        ),
      ],
    );
  }
}
