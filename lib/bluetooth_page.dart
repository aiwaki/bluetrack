import 'package:flutter/cupertino.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';
import 'package:bluetrack/bluetooth_hid_device.dart';
import 'package:permission_handler/permission_handler.dart';

class BluetoothPage extends StatefulWidget {
  const BluetoothPage({super.key});

  @override
  BluetoothPageState createState() => BluetoothPageState();
}

class BluetoothPageState extends State<BluetoothPage> {
  BluetoothHidDevice? _bluetoothHidDevice;
  final List<BluetoothDevice> _devices = [];
  final ValueNotifier<bool> _isGettingDevices = ValueNotifier<bool>(false);

  @override
  void initState() {
    super.initState();
    _init();
  }

  @override
  void dispose() {
    _dispose();
    super.dispose();
  }

  Future<void> _init() async {
    if (!await _checkPermissions()) {
      return;
    }

    _bluetoothHidDevice = BluetoothHidDevice();
    await _getDevices();
  }

  Future<void> _dispose() async {
    await _bluetoothHidDevice?.dispose();
  }

  Future<bool> _checkPermissions() async {
    List<Permission> permissions = [
      Permission.bluetooth,
      Permission.bluetoothScan,
      Permission.bluetoothConnect
    ];
    Map<Permission, PermissionStatus> statuses = await permissions.request();

    bool allGranted = statuses.values.every((status) => status.isGranted);
    if (!allGranted) {
      if (statuses.values.any((status) => status.isPermanentlyDenied)) {
        await openAppSettings();
      }
      return false;
    }
    return true;
  }

  Future<void> _getDevices() async {
    setState(() {
      _devices.clear();
    });
    _isGettingDevices.value = true;

    try {
      var bondedDevices = await _bluetoothHidDevice?.getBondedDevices();
      if (bondedDevices != null) {
        setState(() {
          _devices.addAll(bondedDevices);
        });
      }

      var stream = await _bluetoothHidDevice?.getDevicesStream();
      stream?.listen((result) {
        setState(() {
          if (!_devices.contains(result.device)) {
            _devices.add(result.device);
          }
        });
      }).onDone(() {
        _isGettingDevices.value = false;
      });

      setState(() {
        _devices.sort((a, b) => a.isConnected ? (b.isConnected ? 0 : -1) : 1);
      });
    } catch (e) {
      _isGettingDevices.value = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Bluetooth'),
      ),
      child: CustomScrollView(
        slivers: [
          SliverList(
            delegate: SliverChildListDelegate(
              [
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Padding(
                        padding: EdgeInsets.only(left: 16.0),
                        child: Text('Available devices'),
                      ),
                      const Spacer(),
                      CupertinoButton(
                        child: const Icon(CupertinoIcons.settings),
                        onPressed: () async {
                          await _bluetoothHidDevice?.openSettings();
                        },
                      ),
                      ValueListenableBuilder<bool>(
                        valueListenable: _isGettingDevices,
                        builder: (context, isGetting, _) {
                          return CupertinoButton(
                            onPressed: isGetting
                                ? null
                                : () async {
                                    await _dispose();
                                    await _init();
                                  },
                            child: Icon(isGetting
                                ? CupertinoIcons.hourglass
                                : CupertinoIcons
                                    .arrow_2_circlepath_circle_fill),
                          );
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                var device = _devices[index];
                return Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: CupertinoButton.filled(
                    padding: const EdgeInsets.all(16.0),
                    onPressed: () async {
                      await _bluetoothHidDevice?.connect(device);
                    },
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceAround,
                      children: [
                        ValueListenableBuilder(
                          valueListenable: _bluetoothHidDevice
                                  ?.connectionStatus.isConnected ??
                              ValueNotifier(false),
                          builder: (context, isConnected, _) {
                            bool isDeviceConnected =
                                (isConnected || device.isConnected) &&
                                    device.address.contains(_bluetoothHidDevice
                                            ?.connectionStatus.deviceAddress ??
                                        "");
                            return Icon(isDeviceConnected
                                ? CupertinoIcons.device_laptop
                                : CupertinoIcons.bluetooth);
                          },
                        ),
                        Expanded(
                          child: Padding(
                            padding: const EdgeInsets.only(left: 16.0),
                            child: Text(
                              device.name ?? device.address,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
              childCount: _devices.length,
            ),
          ),
        ],
      ),
    );
  }
}
