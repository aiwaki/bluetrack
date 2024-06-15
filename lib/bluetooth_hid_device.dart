import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

import 'dart:async';

class ConnectionStatus {
  ValueNotifier<bool> isConnected;
  String deviceAddress;

  ConnectionStatus({required this.isConnected, required this.deviceAddress});
}

class BluetoothHidDevice {
  static const _channelName = 'MainActivity/channel';
  static const MethodChannel _methodChannel = MethodChannel(_channelName);
  final ConnectionStatus _connectionStatus =
      ConnectionStatus(isConnected: ValueNotifier(false), deviceAddress: '');

  BluetoothHidDevice() {
    _methodChannel.setMethodCallHandler((MethodCall call) async {
      switch (call.method) {
        case 'onConnected':
          _connectionStatus
            ..deviceAddress = call.arguments.toString()
            ..isConnected.value = true;
          break;
        case 'onDisconnected':
          _connectionStatus
            ..deviceAddress = ''
            ..isConnected.value = false;
          break;
      }
    });
  }

  Future<void> dispose() async => await _isAvailable()
      ? await FlutterBluetoothSerial.instance.isDiscovering
          .then((value) => FlutterBluetoothSerial.instance.cancelDiscovery())
      : null;

  Future<bool> _isAvailable() async {
    bool? isAvailable = await FlutterBluetoothSerial.instance.isAvailable;
    if (!isAvailable!) {
      return false;
    }
    bool? isEnabled = await FlutterBluetoothSerial.instance.isEnabled;
    if (!isEnabled!) {
      await FlutterBluetoothSerial.instance.requestEnable();
      return false;
    }
    return true;
  }

  ConnectionStatus get connectionStatus => _connectionStatus;

  Future<void> openSettings() async => await _isAvailable()
      ? await FlutterBluetoothSerial.instance.openSettings()
      : null;

  Future<Stream<BluetoothDiscoveryResult>?> getDevicesStream() async =>
      await _isAvailable()
          ? FlutterBluetoothSerial.instance.startDiscovery().asBroadcastStream()
          : null;

  Future<List<BluetoothDevice>?> getBondedDevices() async =>
      await _isAvailable()
          ? await FlutterBluetoothSerial.instance.getBondedDevices()
          : null;

  Future<void> removeDeviceBond(BluetoothDevice device) async =>
      await _isAvailable()
          ? await FlutterBluetoothSerial.instance
              .removeDeviceBondWithAddress(device.address)
          : null;

  Future<void> connect(BluetoothDevice device) async => await _isAvailable()
      ? await _methodChannel
          .invokeMethod('connect', <String, String>{'address': device.address})
      : null;

  Future<void> disconnect(BluetoothDevice device) async => await _isAvailable()
      ? await _methodChannel.invokeMethod(
          'disconnect', <String, String>{'address': device.address})
      : null;
}
