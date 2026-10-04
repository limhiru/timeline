import SwiftUI
import MapKit

struct CompassRouteView: View {
    @StateObject private var tracker = Tracker()
    @State private var showHistory = false
    private let panel = Color(white: 0.075)
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("나의 타임라인").font(.title2.bold())
                        Text(tracker.recording ? (tracker.paused ? "기록을 잠시 쉬고 있어요." : "지나온 길을 기록하고 있어요.") : "오늘의 길을 기억하세요.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { showHistory = true } label: { Image(systemName: "clock.arrow.circlepath").font(.title2) }
                        .accessibilityLabel("저장한 경로")
                }
                Text(tracker.track.started.formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary)
                routeMap.frame(height: 260).clipShape(RoundedRectangle(cornerRadius: 26))
                if tracker.returning {
                    HStack(spacing: 18) {
                        Image(systemName: "arrow.up").font(.largeTitle.bold()).rotationEffect(.degrees(tracker.targetAngle ?? 0))
                        VStack(alignment: .leading) {
                            Text(tracker.targetAngle == nil ? "나침반 방향을 확인하는 중" : "이전 경로 지점으로 이동").font(.headline)
                            Text("다음 지점까지 \(Int(tracker.targetDistance ?? 0)) m").foregroundStyle(.secondary)
                            Text("저장된 경로를 따라 출발점으로 돌아갑니다.").font(.caption).foregroundStyle(.secondary)
                        }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(panel, in: RoundedRectangle(cornerRadius: 22))
                }
                VStack(alignment: .leading, spacing: 16) {
                    HStack { Text("오늘 지나온 길").font(.title3.bold()); Spacer(); Text("\(tracker.track.points.count)개 지점").font(.caption).foregroundStyle(.secondary) }
                    Divider()
                    if tracker.track.points.isEmpty {
                        Text("기록 시작을 누르고 걸어 보세요.\n실제 GPS 위치가 여기에 표시됩니다.")
                            .foregroundStyle(.secondary).padding(.vertical, 20)
                    } else {
                        ForEach(Array(timelinePoints.reversed())) { point in
                            HStack(alignment: .top, spacing: 16) {
                                Circle().fill(point.id == tracker.track.points.last?.id ? Color.blue : Color.gray).frame(width: 12, height: 12).padding(.top, 5)
                                Text(point.timestamp.formatted(date: .omitted, time: .shortened)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(point.id == tracker.track.points.first?.id ? "출발" : point.id == tracker.track.points.last?.id ? "마지막 기록 위치" : "경유 지점").font(.headline)
                                    Text(String(format: "%.5f, %.5f", point.latitude, point.longitude)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }.padding(20).background(panel, in: RoundedRectangle(cornerRadius: 26))
                HStack {
                    metric("이동 거리", String(format: "%.2f", tracker.track.distance / 1000), "km")
                    Divider()
                    metric("이동 시간", String(format: "%02d:%02d", Int(tracker.elapsed) / 60, Int(tracker.elapsed) % 60), "분:초")
                    Divider()
                    let pace = tracker.track.distance > 0 ? tracker.elapsed / (tracker.track.distance / 1000) : 0
                    metric("평균 페이스", pace > 0 ? String(format: "%d′%02d″", Int(pace) / 60, Int(pace) % 60) : "—", "/km")
                }.frame(height: 100).padding(16).background(panel, in: RoundedRectangle(cornerRadius: 26))
                HStack {
                    if tracker.recording {
                        Button(tracker.paused ? "재개" : "일시정지") { tracker.togglePause() }.buttonStyle(.bordered)
                        Button("기록 종료") { tracker.stop() }.buttonStyle(.bordered)
                    } else {
                        Button("기록 시작") { tracker.start() }.buttonStyle(.borderedProminent).disabled(tracker.returning)
                    }
                    Spacer()
                    Button(tracker.returning ? "안내 종료" : "되돌아가기") { tracker.beginReturn() }.buttonStyle(.bordered).disabled(tracker.track.points.count < 2)
                }
            }.padding(20)
        }
        .background(Color.black).preferredColorScheme(.dark)
        .onAppear { tracker.enableLocation() }
        .alert("Timeline", isPresented: Binding(get: { tracker.message != nil }, set: { if !$0 { tracker.message = nil } })) {
            Button("확인") { tracker.message = nil }
        } message: { Text(tracker.message ?? "") }
        .sheet(isPresented: $showHistory) {
            NavigationStack {
                List(tracker.history) { track in
                    Button {
                        tracker.select(track); showHistory = false
                    } label: {
                        VStack(alignment: .leading) {
                            Text(track.started.formatted(date: .abbreviated, time: .shortened))
                            Text(String(format: "%.2f km · %d분", track.distance / 1000, Int(track.elapsed) / 60)).font(.caption).foregroundStyle(.secondary)
                        }
                    }.disabled(tracker.recording)
                }.overlay { if tracker.history.isEmpty { ContentUnavailableView("저장된 경로가 없습니다", systemImage: "map") } }
                    .navigationTitle("저장한 경로")
                    .toolbar { Button("닫기") { showHistory = false } }
            }.preferredColorScheme(.dark)
        }
    }
    private var timelinePoints: [TrackPoint] {
        let points = tracker.track.points
        guard points.count > 6 else { return points }
        return (0..<6).map { points[$0 * (points.count - 1) / 5] }
    }
    private var segments: [[TrackPoint]] {
        Dictionary(grouping: tracker.track.points, by: \.segment).sorted { $0.key < $1.key }.map(\.value)
    }
    private var routeMap: some View {
        Map {
            ForEach(Array(segments.enumerated()), id: \.offset) { _, points in
                MapPolyline(coordinates: points.map(\.coordinate)).stroke(.blue, lineWidth: 5)
            }
            if let start = tracker.track.points.first { Annotation("출발", coordinate: start.coordinate) { Image(systemName: "house.fill").padding(8).background(.black, in: Circle()) } }
            if let index = tracker.targetIndex { Annotation("다음 지점", coordinate: tracker.track.points[index].coordinate) { Image(systemName: "flag.fill").foregroundStyle(.orange) } }
            UserAnnotation()
        }.mapStyle(.standard(elevation: .flat)).mapControls { MapCompass(); MapUserLocationButton() }
    }
    private func metric(_ title: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title2.bold().monospacedDigit()).minimumScaleFactor(0.6).lineLimit(1)
            Text(unit).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity)
    }
}
