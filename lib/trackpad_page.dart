import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

class TrackPadPage extends StatefulWidget {
  const TrackPadPage({super.key});

  @override
  TrackPadPageState createState() => TrackPadPageState();
}

class TrackPadPageState extends State<TrackPadPage>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _enableWakelock();
  }

  @override
  void dispose() {
    _disableWakelock();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _enableWakelock() async {
    await WakelockPlus.enable();
  }

  Future<void> _disableWakelock() async {
    await WakelockPlus.disable();
  }

  @override
  Widget build(BuildContext context) {
    return const CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: Text('Trackpad'),
      ),
      child: SafeArea(
        child: Center(
          child: Icon(CupertinoIcons.gamecontroller),
        ),
      ),
    );
  }
}
