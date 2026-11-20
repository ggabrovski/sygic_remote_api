# sygic_remote_api

Lightweight Flutter plugin wrapping the **Sygic Professional / Fleet Navigation Stand-alone Android Remote API** (`com.sygic.fleet:remote-api:6.4.1`) with graceful no-op fallbacks on iOS and Web.

## Features

- Android two-way IPC (`MethodChannel` + `EventChannel`) with `com.sygic.fleet` (configurable package name)
- Truck profile sync via `ApiOptions.setApplicationOptions`
- Guided precomputed SIF 1.1 route loading via `Api.importFile` + `ApiNavigation.loadComputedRoute`, with automatic fallback to multi-stop `ApiItinerary` and single-stop `ApiNavigation.startNavigation`
- Live ETA, remaining distance, and GPS coordinates via `ApiNavigation.getRouteInfo` + `ApiNavigation.getActualGpsPosition`
- Asynchronous navigation event stream (`connected`, `disconnected`, `waypoint_visited`, `route_finish`, `route_computed`, `off_route`)
- Built-in Android `<queries>` manifest declarations and `SygicRouteContentProvider` for Android 11+ Scoped Storage

## Android Gradle Repository

Ensure the host app's `repositories` includes Sygic's public Maven repository:

```groovy
maven { url "https://public.repo.sygic.com/repository/maven-sygic-releases/" }
```
