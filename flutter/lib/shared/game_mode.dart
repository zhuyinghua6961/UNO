enum GameMode {
  classic('CLASSIC', '经典自由局'),
  team2v2('TEAM_2V2', '默契双人组');

  const GameMode(this.protocolValue, this.label);
  final String protocolValue;
  final String label;
}
