# MyTube 안드로이드 앱

유튜브 영상을 재생하고, MP3 · WAV · MP4로 폰의 `Download/MyTube` 폴더에 저장하는 개인용 앱입니다.
APK는 GitHub Actions가 자동으로 빌드합니다.

## 폴더 구조

```
.github/workflows/build-apk.yml   ← APK 자동 빌드
www/index.html                    ← 앱 화면 (HTML/JS)
native/android/                   ← 직접 만든 안드로이드 파일 (빌드할 때 표준 프로젝트 위에 덮어씀)
  ├─ build.gradle                 ← Kotlin 플러그인 추가
  ├─ gradle.properties            ← 빌드 메모리 4GB
  └─ app/
     ├─ build.gradle              ← 다운로드 라이브러리, 고정 서명, CPU별 APK
     └─ src/main/
        ├─ AndroidManifest.xml
        └─ java/com/mytube/app/
           ├─ MainActivity.java
           └─ MediaDownloaderPlugin.kt   ← 다운로드 엔진 (yt-dlp + FFmpeg)
capacitor.config.json · package.json · package-lock.json
```

`android/` 폴더는 저장소에 두지 않습니다. 빌드할 때마다 `npx cap add android`로 공식 템플릿(공식 gradlew 포함)을 새로 만들고, 그 위에 `native/android/`를 덮어씁니다.

## 처음 한 번 설정

1. **기존 저장소 정리**: 기존 `android/` 폴더를 통째로 지우고 이 파일들로 교체합니다.
   예전의 `gradlew`, `gradle-wrapper.properties`, `settings.gradle`, `strings.xml`, `proguard-rules.pro`는 더 이상 필요 없습니다(템플릿이 자동 생성).
2. **서명 키 등록**: `signing-secrets.txt`를 열어 `KEYSTORE_PASSWORD`, `KEYSTORE_BASE64` 두 값을
   GitHub 저장소 → Settings → Secrets and variables → Actions에 등록합니다.
   → 이후 새 APK를 기존 앱 위에 바로 업데이트 설치할 수 있습니다(저장함·최근 기록 유지).
3. `main` 브랜치에 푸시하면 Actions 탭에서 빌드가 시작됩니다.

## 설치

Actions → 완료된 빌드 → `MyTube-APK-번호` 다운로드 → 압축을 풀고
**`app-arm64-v8a-debug.apk`** 설치 (2015년 이전의 오래된 폰만 `armeabi-v7a`).

## 추천 목록 바꾸기

`www/index.html`의 `CATEGORIES`에 `{ id, title, channel, dur }`를 추가합니다.
`id`는 유튜브 주소의 `v=` 뒤 11자리입니다. 라이브 방송은 `dur: "LIVE"`로 적으면 저장 버튼이 막힙니다.

## 알아둘 점

- 앱이 하루 한 번 yt-dlp를 자동으로 최신화합니다. 그래도 YouTube 쪽 변경 때문에 일부 영상은 저장이 실패할 수 있습니다.
- 저작권 때문에 본인 영상이나 허락받은 영상만 저장하세요. 이런 다운로드 앱은 Play 스토어에 등록할 수 없으니 APK로 직접 설치해서 쓰세요.
