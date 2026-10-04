import Foundation
import CoreLocation
import Combine

final class Tracker: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var track = Track()
    @Published var history: [Track] = []
    @Published var recording = false
    @Published var paused = false
    @Published var returning = false
    @Published var position: CLLocation?
    @Published var heading: Double?
    @Published var message: String?
    @Published var targetIndex: Int?
    @Published var elapsed: TimeInterval = 0
    private let manager = CLLocationManager()
    private let store = RouteStore()
    private var timer: AnyCancellable?
    private var anchor: Date?
    private var baseElapsed: TimeInterval = 0
    private var segment = 0
    private var lastCheckpoint = Date.distantPast

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 3
        manager.activityType = .fitness
        manager.headingFilter = 3
        do { history = try store.load().sorted { $0.started > $1.started } }
        catch { message = "기록을 읽지 못했습니다: \(error.localizedDescription)" }
        timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self, self.recording, !self.paused, let anchor = self.anchor else { return }
            self.elapsed = self.baseElapsed + Date().timeIntervalSince(anchor)
            self.track.elapsed = self.elapsed
            if Date().timeIntervalSince(self.lastCheckpoint) >= 10 { self.persist() }
        }
    }
    func enableLocation() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
        default: message = "설정에서 Timeline의 위치 권한을 허용해 주세요."
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { enableLocation() }
    func start() {
        guard manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse else { enableLocation(); return }
        track = Track(); elapsed = 0; baseElapsed = 0; segment = 0
        targetIndex = nil; returning = false; paused = false; recording = true; anchor = Date()
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        enableLocation()
    }
    func togglePause() {
        guard recording else { return }
        if paused { segment += 1; anchor = Date(); paused = false }
        else { baseElapsed = elapsed; anchor = nil; paused = true; persist() }
    }
    func stop() {
        guard recording else { return }
        if let anchor, !paused { elapsed = baseElapsed + Date().timeIntervalSince(anchor) }
        track.elapsed = elapsed; recording = false; paused = false; anchor = nil
        manager.allowsBackgroundLocationUpdates = false
        persist()
    }
    func persist() {
        guard !track.points.isEmpty else { return }
        var updated = history.filter { $0.id != track.id }; updated.insert(track, at: 0)
        do { try store.save(updated); history = updated; lastCheckpoint = Date() }
        catch { message = "저장하지 못했습니다: \(error.localizedDescription)" }
    }
    func select(_ saved: Track) {
        guard !recording else { return }
        track = saved; elapsed = saved.elapsed; returning = false; targetIndex = nil
        enableLocation()
    }
    func beginReturn() {
        if returning { returning = false; targetIndex = nil; manager.allowsBackgroundLocationUpdates = recording; return }
        guard track.points.count >= 2 else { message = "되돌아가려면 먼저 경로를 기록해 주세요."; return }
        guard let position, position.horizontalAccuracy >= 0, position.horizontalAccuracy <= 30,
              abs(position.timestamp.timeIntervalSinceNow) < 20 else { message = "정확한 현재 위치를 기다려 주세요."; enableLocation(); return }
        stop()
        // Start at the closest recorded point, then follow preceding points in order.
        targetIndex = track.points.indices.min { track.points[$0].location.distance(from: position) < track.points[$1].location.distance(from: position) }
        returning = true; manager.allowsBackgroundLocationUpdates = true
        advance(position)
    }
    private func advance(_ location: CLLocation) {
        guard returning, var index = targetIndex else { return }
        let threshold = max(8, min(15, location.horizontalAccuracy))
        while track.points[index].location.distance(from: location) <= threshold {
            if index == 0 {
                returning = false; targetIndex = nil; manager.allowsBackgroundLocationUpdates = false
                message = "출발점에 도착했습니다."; return
            }
            index -= 1
        }
        targetIndex = index
    }
    var targetDistance: Double? {
        guard let index = targetIndex, let position else { return nil }
        return position.distance(from: track.points[index].location)
    }
    var targetAngle: Double? {
        guard let index = targetIndex, let position, let heading else { return nil }
        let a = position.coordinate, b = track.points[index].coordinate
        let p1 = a.latitude * .pi / 180, p2 = b.latitude * .pi / 180
        let delta = (b.longitude - a.longitude) * .pi / 180
        let bearing = atan2(sin(delta) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(delta)) * 180 / .pi
        return (bearing - heading + 540).truncatingRemainder(dividingBy: 360) - 180
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations {
            guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 30,
                  abs(location.timestamp.timeIntervalSinceNow) < 20 else { continue }
            position = location
            if returning { advance(location) }
            guard recording, !paused else { continue }
            if let last = track.points.last, last.segment == segment {
                let dt = location.timestamp.timeIntervalSince(last.timestamp)
                let distance = last.location.distance(from: location)
                guard dt > 0, distance >= 3, distance / dt <= 12 else { continue }
            }
            track.points.append(TrackPoint(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                                          timestamp: location.timestamp, accuracy: location.horizontalAccuracy, segment: segment))
            persist()
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 else { return }
        heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let error = error as? CLError, error.code == .locationUnknown { return }
        message = "위치를 가져오지 못했습니다: \(error.localizedDescription)"
    }
}
