import 'package:flutter/cupertino.dart';
import 'package:bluetrack/trackpad_page.dart';
import 'package:bluetrack/bluetooth_page.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const CupertinoApp(
      title: 'Bluetrack',
      theme: CupertinoThemeData(
        barBackgroundColor: CupertinoColors.systemBackground,
        primaryColor: CupertinoDynamicColor.withBrightness(
          color: CupertinoColors.black,
          darkColor: CupertinoColors.white,
        ),
      ),
      home: HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Bluetrack'),
      ),
      child: SafeArea(
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              _buildNavigationButton(
                context,
                icon: CupertinoIcons.bluetooth,
                text: 'Bluetooth',
                destination: const BluetoothPage(),
              ),
              _buildNavigationButton(
                context,
                icon: CupertinoIcons.cursor_rays,
                text: 'Trackpad',
                destination: const TrackPadPage(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildNavigationButton(BuildContext context,
      {required IconData icon,
      required String text,
      required Widget destination}) {
    return CupertinoButton(
      onPressed: () {
        Navigator.push(
          context,
          CupertinoPageRoute(builder: (context) => destination),
        );
      },
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          Icon(icon),
          Padding(
            padding: const EdgeInsets.only(left: 8.0),
            child: Text(text),
          ),
          const Expanded(child: SizedBox()),
          const Icon(CupertinoIcons.right_chevron),
        ],
      ),
    );
  }
}
