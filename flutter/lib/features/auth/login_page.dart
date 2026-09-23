import 'package:flutter/material.dart';

import 'auth_api.dart';
import 'auth_session.dart';
import '../match/match_history_panel.dart';

enum _AuthMode { login, register, verify, resend, forgot, reset }

class LoginPage extends StatefulWidget {
  const LoginPage({super.key, required this.session});
  final AuthSession session;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _nickname = TextEditingController();
  final _token = TextEditingController();
  _AuthMode _mode = _AuthMode.login;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _nickname.dispose();
    _token.dispose();
    super.dispose();
  }

  void _select(_AuthMode mode) {
    _password.clear();
    _token.clear();
    setState(() => _mode = mode);
  }

  String get _title => switch (_mode) {
    _AuthMode.login => '登录',
    _AuthMode.register => '注册',
    _AuthMode.verify => '验证邮箱',
    _AuthMode.resend => '重发验证邮件',
    _AuthMode.forgot => '找回密码',
    _AuthMode.reset => '设置新密码',
  };

  Future<void> _submit() async {
    if (widget.session.busy || !_formKey.currentState!.validate()) return;
    try {
      switch (_mode) {
        case _AuthMode.login:
          await widget.session.login(_email.text.trim(), _password.text);
        case _AuthMode.register:
          await widget.session.register(
            _email.text.trim(),
            _password.text,
            _nickname.text.trim(),
          );
          _select(_AuthMode.verify);
        case _AuthMode.verify:
          await widget.session.verify(_token.text.trim());
          _select(_AuthMode.login);
        case _AuthMode.resend:
          await widget.session.resend(_email.text.trim());
          _select(_AuthMode.verify);
        case _AuthMode.forgot:
          await widget.session.forgot(_email.text.trim());
          _select(_AuthMode.reset);
        case _AuthMode.reset:
          await widget.session.reset(_token.text.trim(), _password.text);
          _select(_AuthMode.login);
      }
      _password.clear();
      _token.clear();
    } on AuthFailure {
      // The session publishes a safe, localized error message.
    }
  }

  String? _emailValidator(String? value) {
    final email = value?.trim() ?? '';
    if (email.isEmpty || !email.contains('@') || email.length > 254) {
      return '请输入有效邮箱。';
    }
    return null;
  }

  String? _passwordValidator(String? value) {
    if (value == null || value.length < 15 || value.length > 128) {
      return '密码需为 15–128 个字符。';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.session,
    builder: (context, _) {
      final session = widget.session;
      if (session.state == SessionState.authenticated && session.user != null) {
        final user = session.user!;
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text('我的账号', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 18),
            Text('昵称：${user.nickname}'),
            Text('邮箱：${user.email}'),
            Text('用户 ID：${user.id}'),
            const SizedBox(height: 18),
            const Text('经典房间、对局和房间文字已接入；2v2 与队伍通信仍在建设中。', style: TextStyle(color: Colors.black54)),
            const SizedBox(height: 18),
            MatchHistoryPanel(key: ValueKey(user.id), session: session),
            const SizedBox(height: 18),
            FilledButton(
              onPressed: session.busy
                  ? null
                  : () async {
                      try {
                        await session.logout();
                      } on AuthFailure {
                        /* Shown below. */
                      }
                    },
              child: const Text('退出登录'),
            ),
            if (session.error.isNotEmpty)
              _feedback(session.error, isError: true),
          ],
        );
      }
      if (session.state == SessionState.unknown) {
        return const Center(child: CircularProgressIndicator());
      }
      final enabled = session.capabilities?.loginAvailable == true;
      final registration = session.capabilities?.registrationAvailable == true;
      return ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text('先认识你，再一起玩。', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 10),
          const Text('账号登录后可在 Web 与 App 使用同一身份。'),
          if (session.state == SessionState.unavailable) ...[
            _feedback('当前无法确认登录状态，请恢复网络后重试。', isError: true),
            TextButton(
              onPressed: session.busy
                  ? null
                  : () async {
                      try {
                        await session.retry();
                      } on AuthFailure {
                        /* Shown below. */
                      }
                    },
              child: const Text('重试连接'),
            ),
          ],
          if (session.error.isNotEmpty) _feedback(session.error, isError: true),
          if (session.notice.isNotEmpty) _feedback(session.notice),
          if (!enabled) ...[
            const SizedBox(height: 16),
            const Text('账号功能尚未启用或服务不可用，请稍后重试。'),
            TextButton(
              onPressed: session.busy
                  ? null
                  : () async {
                      try {
                        await session.retry();
                      } on AuthFailure {
                        /* Shown above. */
                      }
                    },
              child: const Text('重新检查账号服务'),
            ),
          ] else ...[
            const SizedBox(height: 24),
            SegmentedButton<_AuthMode>(
              segments: [
                const ButtonSegment(value: _AuthMode.login, label: Text('登录')),
                ButtonSegment(
                  value: _AuthMode.register,
                  label: const Text('注册'),
                  enabled: registration,
                ),
              ],
              selected: {
                _mode == _AuthMode.register
                    ? _AuthMode.register
                    : _AuthMode.login,
              },
              onSelectionChanged: (value) => _select(value.first),
            ),
            const SizedBox(height: 20),
            Text(_title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            if (_mode == _AuthMode.verify || _mode == _AuthMode.reset)
              const Padding(
                padding: EdgeInsets.only(bottom: 12),
                child: Text('请从最新邮件复制凭证并粘贴到下方。凭证只在本次提交使用。'),
              ),
            Form(
              key: _formKey,
              child: Column(
                children: [
                  if (_mode == _AuthMode.login ||
                      _mode == _AuthMode.register ||
                      _mode == _AuthMode.resend ||
                      _mode == _AuthMode.forgot)
                    TextFormField(
                      controller: _email,
                      enabled: !session.busy,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.email],
                      decoration: const InputDecoration(
                        labelText: '邮箱',
                        border: OutlineInputBorder(),
                      ),
                      validator: _emailValidator,
                    ),
                  if (_mode == _AuthMode.register) ...[
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _nickname,
                      enabled: !session.busy,
                      decoration: const InputDecoration(
                        labelText: '昵称',
                        border: OutlineInputBorder(),
                      ),
                      validator: (value) =>
                          value == null ||
                              value.trim().isEmpty ||
                              value.length > 80
                          ? '请输入 1–80 个字符的昵称。'
                          : null,
                    ),
                  ],
                  if (_mode == _AuthMode.verify ||
                      _mode == _AuthMode.reset) ...[
                    TextFormField(
                      controller: _token,
                      enabled: !session.busy,
                      decoration: const InputDecoration(
                        labelText: '邮件凭证',
                        border: OutlineInputBorder(),
                      ),
                      validator: (value) =>
                          value?.trim().length == 43 ? null : '请输入邮件中的完整凭证。',
                    ),
                  ],
                  if (_mode == _AuthMode.login ||
                      _mode == _AuthMode.register ||
                      _mode == _AuthMode.reset) ...[
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _password,
                      enabled: !session.busy,
                      obscureText: true,
                      autofillHints: _mode == _AuthMode.login
                          ? const [AutofillHints.password]
                          : const [AutofillHints.newPassword],
                      decoration: const InputDecoration(
                        labelText: '密码',
                        border: OutlineInputBorder(),
                      ),
                      validator: _mode == _AuthMode.login
                          ? (value) =>
                                value == null || value.isEmpty ? '请输入密码。' : null
                          : _passwordValidator,
                    ),
                  ],
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed:
                          session.busy ||
                              (_mode == _AuthMode.register && !registration)
                          ? null
                          : _submit,
                      child: Text(session.busy ? '提交中…' : _title),
                    ),
                  ),
                ],
              ),
            ),
            Wrap(
              spacing: 8,
              children: [
                if (_mode != _AuthMode.login)
                  TextButton(
                    onPressed: session.busy
                        ? null
                        : () => _select(_AuthMode.login),
                    child: const Text('返回登录'),
                  ),
                if (_mode == _AuthMode.login || _mode == _AuthMode.register)
                  TextButton(
                    onPressed: session.busy
                        ? null
                        : () => _select(_AuthMode.verify),
                    child: const Text('验证邮箱'),
                  ),
                if (_mode == _AuthMode.verify)
                  TextButton(
                    onPressed: session.busy
                        ? null
                        : () => _select(_AuthMode.resend),
                    child: const Text('重发验证邮件'),
                  ),
                if (_mode == _AuthMode.login)
                  TextButton(
                    onPressed: session.busy
                        ? null
                        : () => _select(_AuthMode.forgot),
                    child: const Text('忘记密码'),
                  ),
                if (_mode == _AuthMode.forgot)
                  TextButton(
                    onPressed: session.busy
                        ? null
                        : () => _select(_AuthMode.reset),
                    child: const Text('已有重置凭证'),
                  ),
              ],
            ),
          ],
        ],
      );
    },
  );

  Widget _feedback(String message, {bool isError = false}) => Padding(
    padding: const EdgeInsets.only(top: 14),
    child: Text(
      message,
      style: TextStyle(
        color: isError ? Colors.red.shade800 : Colors.green.shade800,
      ),
    ),
  );
}
