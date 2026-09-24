import 'dart:async';

import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import 'match_api.dart';
import 'match_history_models.dart';

class MatchHistoryPanel extends StatefulWidget {
  const MatchHistoryPanel({super.key, required this.session});
  final AuthSession session;

  @override
  State<MatchHistoryPanel> createState() => _MatchHistoryPanelState();
}

class _MatchHistoryPanelState extends State<MatchHistoryPanel> {
  late final MatchApi api;
  List<MatchHistoryItem> items = const [];
  String? cursor;
  bool loading = false;
  bool loaded = false;
  String error = '';
  MatchStats? stats;
  String statsError = '';
  bool statsLoading = false;
  int revision = 0;
  int statsRevision = 0;

  @override
  void initState() {
    super.initState();
    api = MatchApi(session: widget.session);
    unawaited(_load());
    unawaited(_loadStats());
  }

  @override
  void dispose() {
    revision++;
    statsRevision++;
    api.close();
    super.dispose();
  }

  Future<void> _load({bool refresh = false}) async {
    if (loading) return;
    final current = ++revision;
    setState(() {
      if (refresh) {
        items = const [];
        cursor = null;
        loaded = false;
      }
      loading = true;
      error = '';
    });
    try {
      final page = await api.history(cursor);
      if (!mounted || current != revision) return;
      setState(() {
        items = [...items, ...page.items];
        cursor = page.nextCursor;
        loaded = true;
      });
    } catch (failure) {
      if (mounted && current == revision) setState(() => error = '$failure');
    } finally {
      if (mounted && current == revision) setState(() => loading = false);
    }
  }

  Future<void> _loadStats() async {
    final current = ++statsRevision;
    setState(() {
      stats = null;
      statsLoading = true;
      statsError = '';
    });
    try {
      final result = await api.stats();
      if (mounted && current == statsRevision) setState(() => stats = result);
    } catch (failure) {
      if (mounted && current == statsRevision) {
        setState(() => statsError = '$failure');
      }
    } finally {
      if (mounted && current == statsRevision) {
        setState(() => statsLoading = false);
      }
    }
  }

  void _refreshAll() {
    unawaited(_load(refresh: true));
    unawaited(_loadStats());
  }

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '对局战绩',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              TextButton(
                onPressed: loading || statsLoading ? null : _refreshAll,
                child: const Text('刷新'),
              ),
            ],
          ),
          if (stats != null) ...[
            Text('经典 · ${stats!.classic.summary}'),
            Text('2v2 · ${stats!.team2v2.summary}'),
            const Text('中断场次不计入完赛胜率。'),
          ],
          if (statsError.isNotEmpty) ...[
            Text(
              '统计读取失败：$statsError',
              style: const TextStyle(color: Colors.red),
            ),
            TextButton(
              onPressed: statsLoading ? null : _loadStats,
              child: const Text('重试统计'),
            ),
          ],
          if (error.isNotEmpty) ...[
            Text(error, style: const TextStyle(color: Colors.red)),
            TextButton(
              onPressed: loading ? null : _load,
              child: const Text('重试'),
            ),
          ],
          if (loaded && items.isEmpty) const Text('还没有已完成的对局。'),
          for (final item in items)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                '${item.mode == 'TEAM_2V2' ? '2v2' : '经典'} · ${item.result == 'WIN'
                    ? '胜利'
                    : item.result == 'INTERRUPTED'
                    ? '中断 · 不计胜负'
                    : '未获胜'}',
              ),
              subtitle: Text(
                '${item.endedAt.toString().substring(0, 16)} · ${item.rounds} 轮\n'
                '${item.players.map((player) => '${player.nickname ?? '玩家 ${player.userId.substring(0, 6)}'} ${player.score} 分').join(' · ')}',
              ),
              isThreeLine: true,
            ),
          if (cursor != null)
            OutlinedButton(
              onPressed: loading ? null : _load,
              child: Text(loading ? '读取中…' : '加载更多'),
            ),
          if (loading && items.isEmpty) const CircularProgressIndicator(),
        ],
      ),
    ),
  );
}
