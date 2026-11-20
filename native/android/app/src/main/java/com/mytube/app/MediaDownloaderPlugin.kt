package com.mytube.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MyTube 네이티브 다운로더 (yt-dlp + FFmpeg)
 *
 * JS API
 *  - download({ url, format: "mp3" | "wav" | "mp4" }) → { ok, format, fileName, folder }
 *  - cancel()                                         → { canceled }
 *  - 이벤트 "downloadProgress"                         → { jobId, progress, stage }
 *
 * 저장 흐름: 앱 캐시 폴더에 받기 → 기기의 Download/MyTube 로 복사 → 캐시 삭제
 *  - Android 10 이상: MediaStore 사용 (권한 필요 없음)
 *  - Android 7~9    : 저장소 쓰기 권한 요청 후 파일 복사
 */
@CapacitorPlugin(
    name = "MediaDownloader",
    permissions = [
        Permission(alias = MediaDownloaderPlugin.STORAGE, strings = [Manifest.permission.WRITE_EXTERNAL_STORAGE])
    ]
)
class MediaDownloaderPlugin : Plugin() {

    companion object {
        const val STORAGE = "storage"
        private const val SAVE_FOLDER = "MyTube"
        private const val PREFS = "media_downloader"
        private const val KEY_LAST_UPDATE = "ytdlp_last_update"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000 // yt-dlp 업데이트 확인 주기: 하루
        private val FORMATS = setOf("mp3", "wav", "mp4")
        private val TEMP_SUFFIXES = listOf(".part", ".ytdl", ".temp")
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    @Volatile private var engineReady = false
    @Volatile private var currentJobId: String? = null
    @Volatile private var canceled = false

    // ───────────────────────── JS에서 호출하는 메서드 ─────────────────────────

    @PluginMethod
    fun download(call: PluginCall) {
        val (url, format) = call.args()
        when {
            url.isEmpty() -> return call.reject("다운로드할 유튜브 URL이 비어 있습니다.")
            format !in FORMATS -> return call.reject("지원하지 않는 형식입니다: $format")
            busy.get() -> return call.reject("다른 다운로드가 진행 중입니다.")
        }
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            getPermissionState(STORAGE) != PermissionState.GRANTED
        if (needsLegacyPermission) {
            requestPermissionForAlias(STORAGE, call, "onStoragePermission")
        } else {
            start(call, url, format)
        }
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val jobId = currentJobId
        if (jobId != null) {
            canceled = true
            YoutubeDL.getInstance().destroyProcessById(jobId)
        }
        call.resolve(JSObject().apply { put("canceled", jobId != null) })
    }

    @PermissionCallback
    private fun onStoragePermission(call: PluginCall) {
        if (getPermissionState(STORAGE) == PermissionState.GRANTED) {
            val (url, format) = call.args()
            start(call, url, format)
        } else {
            call.reject("저장소 권한이 거부되어 저장할 수 없습니다.")
        }
    }

    override fun handleOnDestroy() {
        currentJobId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        executor.shutdownNow()
        super.handleOnDestroy()
    }

    // ───────────────────────── 다운로드 실행 ─────────────────────────

    private fun start(call: PluginCall, url: String, format: String) {
        if (!busy.compareAndSet(false, true)) return call.reject("다른 다운로드가 진행 중입니다.")

        val jobId = UUID.randomUUID().toString()
        currentJobId = jobId
        canceled = false

        executor.execute {
            val workDir = File(context.cacheDir, "downloads/$jobId")
            try {
                prepareEngine(jobId)
                workDir.mkdirs()

                emit(jobId, 5f, "영상 정보 확인 중...")
                YoutubeDL.getInstance().execute(buildRequest(url, format, workDir), jobId) { progress, _, line ->
                    emit(jobId, progress, line?.trim().orEmpty().ifEmpty { "${format.uppercase()} 처리 중" })
                }
                if (canceled) throw IOException("canceled")

                val outputs = workDir.listFiles()
                    ?.filter { file -> file.isFile && TEMP_SUFFIXES.none { file.name.endsWith(it) } }
                    .orEmpty()
                if (outputs.isEmpty()) {
                    throw IOException("저장할 파일이 없습니다. 라이브 방송이거나 재생할 수 없는 영상일 수 있습니다.")
                }

                emit(jobId, 99f, "기기에 저장 중...")
                val savedNames = outputs.map { publish(it) }

                emit(jobId, 100f, "저장 완료")
                call.resolve(JSObject().apply {
                    put("ok", true)
                    put("format", format)
                    put("fileName", savedNames.first())
                    put("folder", "Download/$SAVE_FOLDER")
                })
            } catch (e: Exception) {
                if (canceled) {
                    emit(jobId, 0f, "취소됨")
                    call.reject("다운로드를 취소했습니다.", "CANCELED")
                } else {
                    emit(jobId, 0f, "오류 발생")
                    call.reject("다운로드 실패: ${e.message}", e)
                }
            } finally {
                workDir.deleteRecursively()
                currentJobId = null
                busy.set(false)
            }
        }
    }

    /** 엔진 초기화(앱 실행당 1회) + yt-dlp 자동 업데이트(하루 1회). */
    private fun prepareEngine(jobId: String) {
        if (!engineReady) {
            emit(jobId, 1f, "다운로드 엔진 준비 중...")
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context) // MP3·WAV 변환과 MP4 영상+음성 병합에 모두 필요
            engineReady = true
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE, 0L) < UPDATE_INTERVAL_MS) return

        emit(jobId, 3f, "yt-dlp 최신 버전 확인 중...")
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
        } catch (_: Exception) {
            // 오프라인 등으로 실패하면 기존 버전으로 계속 진행
        }
    }

    private fun buildRequest(url: String, format: String, outDir: File) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--no-mtime")
        addOption("--windows-filenames")
        addOption("--match-filter", "!is_live") // 라이브 방송은 끝나지 않으므로 건너뜀
        addOption("-o", File(outDir, "%(title).80s [%(id)s].%(ext)s").absolutePath)

        if (format == "mp4") {
            // 고화질 영상+음성 병합 우선, 안 되면 단일 mp4
            addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            addOption("--merge-output-format", "mp4")
        } else {
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", format)
            if (format == "mp3") addOption("--audio-quality", "0")
        }
    }

    // ───────────────────────── 기기 저장 ─────────────────────────

    /** 캐시의 파일을 Download/MyTube 로 복사하고, 실제 저장된 파일 이름을 돌려준다. */
    private fun publish(file: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("저장 위치를 만들 수 없습니다.")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("파일을 쓸 수 없습니다.")
                out.use { file.inputStream().use { input -> input.copyTo(it) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return file.name
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER)
        dir.mkdirs()
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
        file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return target.name
    }

    // ───────────────────────── 보조 함수 ─────────────────────────

    private fun PluginCall.args(): Pair<String, String> =
        getString("url").orEmpty().trim() to getString("format", "mp4").orEmpty().trim().lowercase()

    private fun emit(jobId: String, progress: Float, stage: String) {
        notifyListeners("downloadProgress", JSObject().apply {
            put("jobId", jobId)
            put("progress", progress.coerceIn(0f, 100f).toDouble())
            put("stage", stage)
        })
    }
}
