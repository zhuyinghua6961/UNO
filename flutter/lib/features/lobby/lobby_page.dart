import 'package:flutter/material.dart';

import '../../shared/game_mode.dart';

class LobbyPage extends StatelessWidget {
  const LobbyPage({
    super.key,
    required this.mode,
    required this.onModeChanged,
    required this.onPreview,
    this.roomEntry,
  });

  final GameMode mode;
  final ValueChanged<GameMode> onModeChanged;
  final VoidCallback onPreview;
  final Widget? roomEntry;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Container(
          padding: const EdgeInsets.all(25),
          decoration: BoxDecoration(
            color: const Color(0xffeaeedc),
            borderRadius: BorderRadius.circular(22),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'GOOD CARDS. GREAT COMPANY.',
                style: TextStyle(fontSize: 10, letterSpacing: 1.5),
              ),
              const SizedBox(height: 16),
              const Text(
                '好朋友，\n就差你这一张。',
                style: TextStyle(
                  fontSize: 35,
                  height: 1.3,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  for (final card in [
                    'red-2',
                    'yellow-3',
                    'green-reverse',
                    'blue-7',
                  ])
                    Flexible(
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Image.asset(
                          'assets/cards/$card.png',
                          height: 110,
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 20),
              FilledButton(onPressed: onPreview, child: const Text('看看牌桌')),
              const SizedBox(height: 8),
              const Text('交互演示，不会创建真实房间', style: TextStyle(fontSize: 11)),
            ],
          ),
        ),
        const SizedBox(height: 28),
        Text('今天，怎么玩？', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 14),
        for (final option in GameMode.values)
          Card(
            color: mode == option ? const Color(0xffe8eddd) : Colors.white,
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 10,
              ),
              leading: Icon(
                option == GameMode.classic
                    ? Icons.style_outlined
                    : Icons.group_outlined,
              ),
              title: Text(option.label),
              subtitle: Text(
                option == GameMode.classic
                    ? '2–6 人 · 真实经典对局'
                    : '4 人 2v2 · 实时对局与队伍文字',
              ),
              trailing: Icon(
                mode == option
                    ? Icons.radio_button_checked
                    : Icons.radio_button_off,
              ),
              onTap: () => onModeChanged(option),
            ),
          ),
        const SizedBox(height: 16),
        ?roomEntry,
        const Text(
          '经典局与 2v2 都可开桌，房间/队伍文字可用。牌桌预览仅供体验；队友语音仍在建设中。',
          style: TextStyle(fontSize: 12, color: Colors.black54),
        ),
      ],
    );
  }
}
