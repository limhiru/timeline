import Foundation

func point(_ longitude: Double, segment: Int = 0) -> TrackPoint {
    TrackPoint(latitude: 37, longitude: longitude, timestamp: Date(), accuracy: 5, segment: segment)
}
var track = Track()
track.points = [point(127), point(127.001), point(127.01, segment: 1), point(127.011, segment: 1)]
let expected = track.points[0].location.distance(from: track.points[1].location) + track.points[2].location.distance(from: track.points[3].location)
precondition(abs(track.distance - expected) < 0.01, "Paused movement must not count")
precondition(track.distance > 170 && track.distance < 190, "Distance must use geographic coordinates")
let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let store = RouteStore(url: directory.appendingPathComponent("tracks.json"))
let empty = try store.load()
precondition(empty.isEmpty)
track.elapsed = 125
try store.save([track])
let loaded = try store.load()
precondition(loaded.count == 1 && loaded[0].id == track.id)
precondition(loaded[0].points.map(\.id) == track.points.map(\.id))
precondition(loaded[0].elapsed == 125 && abs(loaded[0].distance - expected) < 0.01)
try Data("broken".utf8).write(to: directory.appendingPathComponent("tracks.json"))
do { _ = try store.load(); fatalError("Corrupt data must be reported") } catch { }
print("PASS: geographic distance, pause gaps, persistence round trip, corrupt data")
