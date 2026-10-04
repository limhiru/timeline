import Foundation
import CoreLocation

struct TrackPoint: Codable, Identifiable {
    var id = UUID()
    let latitude: Double
    let longitude: Double
    let timestamp: Date
    let accuracy: Double
    let segment: Int
    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
    var location: CLLocation { .init(latitude: latitude, longitude: longitude) }
}
struct Track: Codable, Identifiable {
    var id = UUID()
    var started = Date()
    var points: [TrackPoint] = []
    var elapsed: TimeInterval = 0
    var distance: Double {
        zip(points, points.dropFirst()).reduce(0) { sum, pair in
            sum + (pair.0.segment == pair.1.segment ? pair.0.location.distance(from: pair.1.location) : 0)
        }
    }
}
final class RouteStore {
    private let url: URL
    init(url: URL? = nil) {
        self.url = url ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("tracks.json")
    }
    func load() throws -> [Track] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        return try JSONDecoder().decode([Track].self, from: Data(contentsOf: url))
    }
    func save(_ tracks: [Track]) throws {
        try JSONEncoder().encode(tracks).write(to: url, options: .atomic)
    }
}
