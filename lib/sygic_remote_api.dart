import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Connection result returned by [SygicRemoteApi.connect].
class SygicConnectionResult {
  const SygicConnectionResult({
    required this.ipcAvailable,
    required this.connected,
    required this.installed,
    required this.connectionState,
    required this.packageName,
  });

  final bool ipcAvailable;
  final bool connected;
  final bool installed;
  final String connectionState;
  final String packageName;
}

/// Truck profile options synced to Sygic via `ApiOptions.setApplicationOptions`.
class SygicTruckOptions {
  const SygicTruckOptions({
    this.grossWeight = 0,
    this.currentWeight = 0,
    this.weightPerAxle = 0,
    this.heightCm = 0,
    this.widthCm = 0,
    this.lengthCm = 0,
    this.emissionClass = '',
    this.tunnelCategory = 'None',
    this.hazardousGoods = false,
  });

  final int grossWeight;
  final int currentWeight;
  final int weightPerAxle;
  final int heightCm;
  final int widthCm;
  final int lengthCm;
  final String emissionClass;
  final String tunnelCategory;
  final bool hazardousGoods;

  Map<String, dynamic> toMap() => {
        'grossWeight': grossWeight,
        'currentWeight': currentWeight,
        'weightPerAxle': weightPerAxle,
        'height': heightCm,
        'width': widthCm,
        'length': lengthCm,
        'emissionClass': emissionClass,
        'tunnelCategory': tunnelCategory,
        'hazardousGoods': hazardousGoods,
      };
}

/// Waypoint sent for multi-stop itinerary fallback when SIF precomputed routes are unavailable.
class SygicStop {
  const SygicStop({
    required this.lat,
    required this.lng,
    this.label = '',
    this.passThrough = false,
  });

  final double lat;
  final double lng;
  final String label;
  final bool passThrough;

  Map<String, dynamic> toMap() => {
        'lat': lat,
        'lng': lng,
        'label': label,
        'passThrough': passThrough,
      };
}

/// Result returned when starting a guided route or single-stop navigation over IPC.
class SygicLaunchResult {
  const SygicLaunchResult({
    required this.ok,
    required this.mode,
    required this.message,
  });

  final bool ok;
  final String mode;
  final String message;
}

/// Live route & GPS telemetry fetched from Sygic over IPC.
class SygicRouteStatus {
  const SygicRouteStatus({
    required this.ipcAvailable,
    required this.connected,
    required this.navigating,
    required this.etaMinutes,
    required this.remainingMeters,
    required this.currentLat,
    required this.currentLng,
    required this.nextWaypointIndex,
    this.rawStatusJson = '',
  });

  final bool ipcAvailable;
  final bool connected;
  final bool navigating;
  final int etaMinutes;
  final int remainingMeters;
  final double currentLat;
  final double currentLng;
  final int nextWaypointIndex;
  final String rawStatusJson;
}

/// Asynchronous event emitted by Sygic `ApiCallback.onEvent`.
class SygicEvent {
  const SygicEvent({
    required this.type,
    required this.eventId,
    required this.data,
  });

  final String type;
  final int eventId;
  final String data;
}

/// Flutter bridge for Sygic Professional / Fleet Navigation Stand-alone Android IPC.
///
/// Safe to import and invoke on iOS and Web: non-Android platforms return graceful
/// `unavailable_platform` results without throwing `MissingPluginException`.
class SygicRemoteApi {
  SygicRemoteApi._();

  static const MethodChannel _methods = MethodChannel('sygic_remote_api/methods');
  static const EventChannel _events = EventChannel('sygic_remote_api/events');

  static Stream<SygicEvent>? _eventStream;

  /// True only on Android (Sygic Stand-alone IPC is Android-only).
  static bool get isSupportedPlatform =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static Future<bool> isInstalled({
    String packageName = 'com.sygic.fleet',
  }) async {
    if (!isSupportedPlatform) {
      return false;
    }
    try {
      final installed = await _methods.invokeMethod<bool>(
        'isInstalled',
        {'packageName': packageName},
      );
      return installed ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<SygicConnectionResult> connect({
    String packageName = 'com.sygic.fleet',
  }) async {
    if (!isSupportedPlatform) {
      return SygicConnectionResult(
        ipcAvailable: false,
        connected: false,
        installed: false,
        connectionState: 'unavailable_platform',
        packageName: packageName,
      );
    }

    try {
      final raw = await _methods.invokeMapMethod<String, dynamic>(
        'connect',
        {'packageName': packageName},
      );
      if (raw == null) {
        return SygicConnectionResult(
          ipcAvailable: true,
          connected: false,
          installed: false,
          connectionState: 'error',
          packageName: packageName,
        );
      }
      return SygicConnectionResult(
        ipcAvailable: true,
        connected: raw['connected'] == true,
        installed: raw['installed'] == true,
        connectionState: (raw['connectionState'] ?? 'disconnected').toString(),
        packageName: (raw['packageName'] ?? packageName).toString(),
      );
    } catch (_) {
      return SygicConnectionResult(
        ipcAvailable: true,
        connected: false,
        installed: false,
        connectionState: 'error',
        packageName: packageName,
      );
    }
  }

  static Future<void> disconnect() async {
    if (!isSupportedPlatform) {
      return;
    }
    try {
      await _methods.invokeMethod<bool>('disconnect');
    } catch (_) {}
  }

  static Future<bool> setTruckProfile(SygicTruckOptions profile) async {
    if (!isSupportedPlatform) {
      return false;
    }
    try {
      final ok = await _methods.invokeMethod<bool>(
        'setTruckProfile',
        {'profile': profile.toMap()},
      );
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<SygicLaunchResult> startGuidedRoute({
    String sifJson = '',
    List<SygicStop> stops = const [],
    double activeLat = 0.0,
    double activeLng = 0.0,
    String activeLabel = '',
    bool showSygic = true,
  }) async {
    if (!isSupportedPlatform) {
      return const SygicLaunchResult(
        ok: false,
        mode: 'unavailable_platform',
        message: 'Android IPC is not available on this platform.',
      );
    }
    try {
      final raw = await _methods.invokeMapMethod<String, dynamic>(
        'startGuidedRoute',
        {
          'sifJson': sifJson,
          'stops': stops.map((s) => s.toMap()).toList(),
          'activeLat': activeLat,
          'activeLng': activeLng,
          'activeLabel': activeLabel,
          'showSygic': showSygic,
        },
      );
      return SygicLaunchResult(
        ok: raw?['ok'] == true,
        mode: (raw?['mode'] ?? 'unknown').toString(),
        message: (raw?['message'] ?? '').toString(),
      );
    } on PlatformException catch (e) {
      return SygicLaunchResult(
        ok: false,
        mode: e.code,
        message: e.message ?? 'Could not start Sygic navigation.',
      );
    } catch (e) {
      return SygicLaunchResult(
        ok: false,
        mode: 'error',
        message: e.toString(),
      );
    }
  }

  static Future<SygicLaunchResult> startSingleStop({
    required double lat,
    required double lng,
    String label = '',
    bool showSygic = true,
  }) async {
    if (!isSupportedPlatform) {
      return const SygicLaunchResult(
        ok: false,
        mode: 'unavailable_platform',
        message: 'Android IPC is not available on this platform.',
      );
    }
    try {
      final raw = await _methods.invokeMapMethod<String, dynamic>(
        'startSingleStop',
        {
          'lat': lat,
          'lng': lng,
          'label': label,
          'showSygic': showSygic,
        },
      );
      return SygicLaunchResult(
        ok: raw?['ok'] == true,
        mode: (raw?['mode'] ?? 'single_stop').toString(),
        message: (raw?['message'] ?? '').toString(),
      );
    } on PlatformException catch (e) {
      return SygicLaunchResult(
        ok: false,
        mode: e.code,
        message: e.message ?? 'Could not start Sygic navigation.',
      );
    } catch (e) {
      return SygicLaunchResult(
        ok: false,
        mode: 'error',
        message: e.toString(),
      );
    }
  }

  static Future<SygicRouteStatus> getRouteStatus() async {
    if (!isSupportedPlatform) {
      return const SygicRouteStatus(
        ipcAvailable: false,
        connected: false,
        navigating: false,
        etaMinutes: 0,
        remainingMeters: 0,
        currentLat: 0.0,
        currentLng: 0.0,
        nextWaypointIndex: 0,
      );
    }
    try {
      final raw = await _methods.invokeMapMethod<String, dynamic>('getRouteStatus');
      return SygicRouteStatus(
        ipcAvailable: true,
        connected: raw?['connected'] == true,
        navigating: raw?['navigating'] == true,
        etaMinutes: (raw?['etaMinutes'] as num?)?.toInt() ?? 0,
        remainingMeters: (raw?['remainingMeters'] as num?)?.toInt() ?? 0,
        currentLat: (raw?['currentLat'] as num?)?.toDouble() ?? 0.0,
        currentLng: (raw?['currentLng'] as num?)?.toDouble() ?? 0.0,
        nextWaypointIndex: (raw?['nextWaypointIndex'] as num?)?.toInt() ?? 0,
        rawStatusJson: (raw?['rawStatusJson'] ?? '').toString(),
      );
    } catch (_) {
      return const SygicRouteStatus(
        ipcAvailable: true,
        connected: false,
        navigating: false,
        etaMinutes: 0,
        remainingMeters: 0,
        currentLat: 0.0,
        currentLng: 0.0,
        nextWaypointIndex: 0,
      );
    }
  }

  static Future<bool> showSygic() => _invokeBool('showSygic');

  static Future<bool> hideSygic() => _invokeBool('hideSygic');

  static Future<bool> stopNavigation() => _invokeBool('stopNavigation');

  static Future<bool> skipNextWaypoint() => _invokeBool('skipNextWaypoint');

  static Future<bool> _invokeBool(String method) async {
    if (!isSupportedPlatform) {
      return false;
    }
    try {
      return (await _methods.invokeMethod<bool>(method)) ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Broadcast stream of asynchronous navigation events from Sygic IPC.
  /// Emits nothing on non-Android platforms.
  static Stream<SygicEvent> get events {
    if (!isSupportedPlatform) {
      return const Stream<SygicEvent>.empty();
    }
    _eventStream ??= _events.receiveBroadcastStream().map((dynamic raw) {
      if (raw is Map) {
        return SygicEvent(
          type: (raw['type'] ?? 'raw').toString(),
          eventId: (raw['eventId'] as num?)?.toInt() ?? 0,
          data: (raw['data'] ?? '').toString(),
        );
      }
      return const SygicEvent(type: 'raw', eventId: 0, data: '');
    });
    return _eventStream!;
  }
}
