import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sygic_remote_api/sygic_remote_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('sygic_remote_api/methods');

  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
      switch (call.method) {
        case 'isInstalled':
          return true;
        case 'connect':
          return {
            'connected': true,
            'installed': true,
            'connectionState': 'connected',
            'packageName': call.arguments['packageName'],
          };
        case 'setTruckProfile':
          return true;
        case 'startGuidedRoute':
          return {
            'ok': true,
            'mode': 'guided_sif',
            'message': 'Guided route loaded in Sygic Fleet',
          };
        case 'getRouteStatus':
          return {
            'connected': true,
            'navigating': true,
            'etaMinutes': 42,
            'remainingMeters': 51200,
            'currentLat': 42.6977,
            'currentLng': 23.3219,
            'nextWaypointIndex': 1,
            'rawStatusJson': '{}',
          };
        case 'showSygic':
        case 'stopNavigation':
        case 'skipNextWaypoint':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('connects and reads live route status on Android', () async {
    expect(await SygicRemoteApi.isInstalled(), isTrue);

    final conn = await SygicRemoteApi.connect(packageName: 'com.sygic.fleet');
    expect(conn.ipcAvailable, isTrue);
    expect(conn.connected, isTrue);
    expect(conn.connectionState, 'connected');

    final synced = await SygicRemoteApi.setTruckProfile(
      const SygicTruckOptions(
        grossWeight: 40000,
        heightCm: 400,
        widthCm: 255,
        lengthCm: 1650,
        emissionClass: 'EURO_6',
        tunnelCategory: 'E',
      ),
    );
    expect(synced, isTrue);

    final launch = await SygicRemoteApi.startGuidedRoute(
      sifJson: '{"version":"1.1"}',
      stops: const [SygicStop(lat: 42.6977, lng: 23.3219, label: 'Sofia')],
      activeLat: 42.6977,
      activeLng: 23.3219,
    );
    expect(launch.ok, isTrue);
    expect(launch.mode, 'guided_sif');

    final status = await SygicRemoteApi.getRouteStatus();
    expect(status.connected, isTrue);
    expect(status.navigating, isTrue);
    expect(status.etaMinutes, 42);
    expect(status.remainingMeters, 51200);
    expect(status.nextWaypointIndex, 1);
  });

  test('returns graceful no-op results on iOS', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

    final conn = await SygicRemoteApi.connect();
    expect(conn.ipcAvailable, isFalse);
    expect(conn.connected, isFalse);
    expect(conn.connectionState, 'unavailable_platform');

    final status = await SygicRemoteApi.getRouteStatus();
    expect(status.ipcAvailable, isFalse);
    expect(status.navigating, isFalse);
  });
}
