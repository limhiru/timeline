# Timeline

실제 GPS 이동 경로를 기록하고 저장한 경로를 따라 출발점으로 돌아가는 iPhone 앱입니다. iOS 17 이상, Xcode 16 이상이 필요합니다.

## 실행

1. `Timeline.xcodeproj`를 Xcode에서 엽니다.
2. Signing & Capabilities에서 자신의 개발 Team을 선택합니다.
3. iPhone을 연결하고 Timeline scheme을 실행합니다.
4. 위치 권한을 허용하고 `기록 시작`을 누릅니다. `일시정지`, `재개`, `기록 종료`를 사용할 수 있습니다.
5. `되돌아가기`는 현재 위치에 가장 가까운 저장 지점부터 이전 지점을 순서대로 안내합니다. `저장한 경로`에서 이전 기록도 불러올 수 있습니다.

기록은 앱의 Documents/tracks.json에 원자적으로 저장됩니다. 일시정지 동안 이동한 거리는 합산하지 않습니다. 앱을 종료한 후에는 저장한 경로에서 복원할 수 있지만 진행 중인 기록은 자동 재개하지 않습니다. 위치 기록은 백그라운드 location 모드를 사용합니다.

안내는 저장된 GPS 점을 잇는 방식이며 도로 기반 회전 안내나 장애물 탐지를 제공하지 않습니다. GPS 오차가 30m를 넘는 좌표와 비정상적인 이동 속도는 기록에서 제외합니다. 지도 지명 자동 조회는 구현하지 않았으며 타임라인에는 실제 좌표와 시간이 표시됩니다. 일시정지로 생긴 경로 공백은 지도에서도 연결하지 않습니다.

## 검증

```sh
xcodebuild -project Timeline.xcodeproj -scheme Timeline -sdk iphonesimulator -configuration Debug -derivedDataPath /tmp/timeline-build CODE_SIGNING_ALLOWED=NO build
swiftc src/RouteStore.swift tests/main.swift -o /tmp/timeline-core-tests
/tmp/timeline-core-tests
```

실기기에서 화면 잠금 중 기록, 권한 거부, 일시정지 이동 후 재개, 앱 재실행 후 경로 복원, 출발점 도착을 확인해 주세요. 시뮬레이터에는 실제 나침반 센서가 없습니다.

Android 구현은 [`android/README.md`](android/README.md)를 참고하세요. Android Studio에서 `android` 폴더를 열거나 `cd android && ./gradlew :app:assembleDebug`로 APK를 생성할 수 있습니다. 실제 GPS 기록, 저장·복원, 역순 안내, 오프라인 경로 화면을 제공합니다.

갤럭시 워치4 이상 Wear OS 버전: [`android/wear/README.md`](android/wear/README.md).

Android 폰과 워치에 같은 릴리즈의 APK를 설치하고 폰에서 `워치에 위치 공유`를 켜면, 직접 연결된 워치가 폰의 새 GPS·정지 감지 값을 우선 사용합니다. 공유 중지·연결 끊김·오래된 값은 워치 GPS로 자동 전환하며 나침반 방향은 워치 센서를 유지합니다. 설정과 제약은 [Android 안내](android/README.md#연결된-워치에-폰-위치-제공)를 참고하세요.

폰 Preview 4는 Clear/Tinted 선택 화면과 홈 아이콘의 일별 타임라인을 제공합니다. 자동 기록을 직접 켜면 기본 30분, 이동 감지 시 10초 주기로 위치를 확인하며 운동·복귀 안내는 별도로 유지합니다. 절전 중 주기는 지연될 수 있습니다. [사용법과 기록 범위](android/README.md#폰-선택-화면과-자동-타임라인-preview-4)를 참고하세요.
