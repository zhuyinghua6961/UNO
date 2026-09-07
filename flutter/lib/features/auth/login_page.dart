import 'package:flutter/material.dart';

class LoginPage extends StatelessWidget {
  const LoginPage({super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('先认识你，再一起玩。', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 16),
        const Text('账号接口尚未接入，此页面不会收集或保存账号密码。'),
        const SizedBox(height: 28),
        const TextField(
          enabled: false,
          decoration: InputDecoration(
            labelText: '邮箱',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 18),
        const TextField(
          enabled: false,
          obscureText: true,
          decoration: InputDecoration(
            labelText: '密码',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 24),
        const FilledButton(onPressed: null, child: Text('登录接口建设中')),
      ],
    );
  }
}
