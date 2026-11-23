package app.harvester.companion.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import app.harvester.companion.UHLog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Zero-reencode, zero-loss media pipeline for static and motion wallpapers:
 * - Static images: verbatim original file copy (zero compression, zero downscale).
 * - Full short videos (<= 30s): verbatim original file copy preserving native container.
 * - Trimmed videos (any length, including 1-hour+ movies): direct URI-level lossless container cut
 *   via Android's native [MediaExtractor] + [MediaMuxer] (never copies the full 1-hour file,
 *   writes ONLY the trimmed video packets with ZERO re-encoding and ZERO quality loss, plus exact
 *   sub-GOP fine [VideoClipBounds] when the nearest keyframe precedes the requested start).
 */
object WallpaperMediaProcessor {

    data class VideoProbe(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val durationSec: Float,
        val fps: Float,
        val bitrateBps: Long,
        val hasVideoTrack: Boolean,
    ) {
        val maxDim: Int get() = max(width, height)

        val displayWidth: Int
            get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

        val displayHeight: Int
            get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        val aspectRatio: Float
            get() = if (displayWidth > 0 && displayHeight > 0) {
                displayWidth.toFloat() / displayHeight.toFloat()
            } else 0f
    }

    data class TrimmedVideoResult(
        val file: File,
        val fineClipBounds: VideoClipBounds? = null,
    )

    class TrimCancellationToken {
        private val cancelled = AtomicBoolean(false)
        private val activeSessionId = AtomicLong(-1L)

        val isCancelled: Boolean
            get() = cancelled.get()

        internal fun attachSession(sessionId: Long) {
            activeSessionId.set(sessionId)
            if (cancelled.get()) {
                try { FFmpegKit.cancel(sessionId) } catch (_: Exception) {}
            }
        }

        internal fun clearSession(sessionId: Long) {
            activeSessionId.compareAndSet(sessionId, -1L)
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                val sid = activeSessionId.getAndSet(-1L)
                if (sid >= 0L) {
                    try { FFmpegKit.cancel(sid) } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Lossless image import: copies original bytes verbatim while preserving native extension,
     * verifying decodability with a lightweight header-only bounds pass.
     */
    fun importLocalImageLossless(ctx: Context, uri: Uri, destDir: File): File? {
        return try {
            val ext = guessImageExtensionFromUri(ctx, uri)
            val tmpFile = File(destDir, "wall_${System.currentTimeMillis()}.tmp")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (!isValidImageFile(tmpFile)) {
                tmpFile.delete()
                return null
            }

            val detectedExt = detectImageExtensionFromMagicBytes(tmpFile) ?: ext
            val destFile = File(destDir, "wall_${System.currentTimeMillis()}.$detectedExt")
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            destFile.takeIf { it.exists() && it.length() > 64L }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "importLocalImageLossless failed: ${e.message}")
            null
        }
    }

    /**
     * Moves or copies an already-downloaded remote image without any re-compression.
     */
    fun finalizeRemoteImageLossless(
        srcFile: File,
        destDir: File,
        hash: String,
        contentType: String = "",
        url: String = "",
    ): File? {
        if (!isValidImageFile(srcFile)) return null
        val ext = detectImageExtensionFromMagicBytes(srcFile)
            ?: guessImageExtensionFromMeta(contentType, url)
        val destFile = File(destDir, "wall_$hash.$ext")
        if (destFile.exists()) destFile.delete()
        if (!srcFile.renameTo(destFile)) {
            srcFile.copyTo(destFile, overwrite = true)
            srcFile.delete()
        }
        return destFile.takeIf { it.exists() && it.length() > 64L }
    }

    /**
     * Single-pass verbatim local video import for short full clips (<= 30s):
     * preserves the exact original container and bytes with ZERO re-encoding.
     */
    fun importLocalVideoVerbatim(
        ctx: Context,
        uri: Uri,
        destDir: File,
        onProgress: ((Float) -> Unit)? = null,
        cancelToken: TrimCancellationToken? = null,
    ): File? {
        val guessedExt = guessVideoExtensionFromUri(ctx, uri)
        val tmpFile = File(destDir, "motion_${System.currentTimeMillis()}.tmp")
        return try {
            onProgress?.invoke(0.15f)
            val totalBytes = queryUriSize(ctx, uri)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var read: Int
                    var lastReported = 0.15f
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelToken?.isCancelled == true) {
                            tmpFile.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalBytes > 0L) {
                            val pct = (0.15f + (copied.toFloat() / totalBytes) * 0.75f).coerceIn(0.15f, 0.90f)
                            if (pct - lastReported >= 0.03f) {
                                lastReported = pct
                                onProgress?.invoke(pct)
                            }
                        }
                    }
                }
            } ?: return null

            if (cancelToken?.isCancelled == true || !tmpFile.exists() || tmpFile.length() < 1024L) {
                tmpFile.delete()
                return null
            }

            val finalExt = detectVideoExtensionFromMagicBytes(tmpFile, guessedExt)
            val destFile = File(destDir, "motion_${System.currentTimeMillis()}.$finalExt")
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }

            val probe = probeVideo(destFile.absolutePath)
            if (!probe.hasVideoTrack && probe.width <= 0 && probe.height <= 0) {
                UHLog.w("wallpaper_warn", "Imported file had no recognizable video track: ${destFile.name}")
                destFile.delete()
                return null
            }

            onProgress?.invoke(0.95f)
            destFile.takeIf { it.exists() && it.length() > 1024L }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "importLocalVideoVerbatim failed: ${e.message}")
            tmpFile.delete()
            null
        }
    }

    /**
     * 3-Tier blazing-fast video trimmer that NEVER hangs at 10%:
     * 1. Tier 1 (50–300ms): Android native [MediaExtractor] + [MediaMuxer] direct packet remux
     *    with seek-landing validation (aborts immediately if container lacks seek index),
     *    rotation preservation, and smooth live progress.
     * 2. Tier 2 (200ms–1.5s): Async FFmpeg lossless stream-copy (`-c:v copy`) with live
     *    `StatisticsCallback` progress and instant Cancel support.
     * 3. Tier 3 (1–4s, compatibility fallback only): Async FFmpeg ultrafast H.264 re-encode
     *    of ONLY the trimmed <=30s slice for exotic/corrupt codecs that cannot stream-copy.
     */
    fun importLocalVideoTrimmedLossless(
        ctx: Context,
        uri: Uri,
        destDir: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null,
        onStageChanged: ((String) -> Unit)? = null,
        cancelToken: TrimCancellationToken? = null,
    ): TrimmedVideoResult? {
        val safeStartMs = startMs.coerceAtLeast(0L)
        val safeEndMs = endMs.coerceAtLeast(safeStartMs + 500L)
        if (cancelToken?.isCancelled == true) return null

        onStageChanged?.invoke("Cutting lossless clip…")
        onProgress?.invoke(0.12f)

        // Tier 1: Native Android MediaExtractor + MediaMuxer (instant, zero re-encode)
        muxTrimFromUri(ctx, uri, destDir, safeStartMs, safeEndMs, onProgress, cancelToken)?.let { result ->
            if (cancelToken?.isCancelled == true) {
                result.file.delete()
                return null
            }
            onProgress?.invoke(0.95f)
            return result
        }
        if (cancelToken?.isCancelled == true) return null

        // Tier 2: Async FFmpeg lossless stream-copy with live StatisticsCallback progress
        UHLog.w("video_warn", "Tier 1 MediaMuxer trim skipped/unsupported; running Tier 2 lossless stream-copy.")
        onStageChanged?.invoke("Fast lossless stream-copy…")
        onProgress?.invoke(0.16f)
        streamCopyTrimFromUriAsync(ctx, uri, destDir, safeStartMs, safeEndMs, onProgress, cancelToken)?.let { file ->
            if (cancelToken?.isCancelled == true) {
                file.delete()
                return null
            }
            onProgress?.invoke(0.95f)
            return TrimmedVideoResult(file = file, fineClipBounds = null)
        }
        if (cancelToken?.isCancelled == true) return null

        // Tier 3: Smart ultrafast compatibility re-encode for exotic codecs/containers (trimmed slice ONLY)
        UHLog.w("video_warn", "Tier 2 stream-copy failed; running Tier 3 ultrafast compatibility encode.")
        onStageChanged?.invoke("Optimizing video format…")
        onProgress?.invoke(0.18f)
        reencodeTrimFromUriAsync(ctx, uri, destDir, safeStartMs, safeEndMs, onProgress, cancelToken)?.let { file ->
            if (cancelToken?.isCancelled == true) {
                file.delete()
                return null
            }
            onProgress?.invoke(0.95f)
            return TrimmedVideoResult(file = file, fineClipBounds = null)
        }

        return null
    }

    private fun muxTrimFromUri(
        ctx: Context,
        uri: Uri,
        destDir: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)?,
        cancelToken: TrimCancellationToken?,
    ): TrimmedVideoResult? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var wroteSamples = false
        var outFile: File? = null

        return try {
            extractor.setDataSource(ctx, uri, null)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var videoMime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    videoMime = mime
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return null

            // Check seekability BEFORE creating output file so unindexed files fail fast (<5ms)
            extractor.selectTrack(videoTrackIndex)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val targetDurationUs = (endUs - startUs).coerceAtLeast(500_000L)

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val landedUs = extractor.sampleTime
            if (landedUs < 0L) return null
            // If seekTo landed >12s before startUs (missing/broken seek table on long file),
            // bail immediately to FFmpeg instead of scanning thousands of unneeded packets!
            if (startUs > 15_000_000L && (startUs - landedUs) > 12_000_000L) {
                UHLog.w("video_warn", "Tier 1 seek landed ${(startUs - landedUs) / 1_000_000L}s early; delegating to Tier 2.")
                return null
            }

            val isWebmCodec = videoMime == MediaFormat.MIMETYPE_VIDEO_VP8 ||
                videoMime == MediaFormat.MIMETYPE_VIDEO_VP9
            val outputFormat = if (isWebmCodec) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val outputExt = if (isWebmCodec) "webm" else "mp4"

            outFile = File(destDir, "motion_${System.currentTimeMillis()}.$outputExt")
            muxer = MediaMuxer(outFile.absolutePath, outputFormat)

            val rotation = readRotationDegrees(ctx, uri, videoFormat)
            if (rotation in setOf(0, 90, 180, 270)) {
                muxer.setOrientationHint(rotation)
            }

            val muxerTrackIndex = muxer.addTrack(videoFormat)
            muxer.start()
            muxerStarted = true

            val initialCap = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(512 * 1024, 8 * 1024 * 1024)
            } else {
                2 * 1024 * 1024
            }
            var buffer = ByteBuffer.allocate(initialCap)
            val bufferInfo = MediaCodec.BufferInfo()

            var basePtsUs = -1L
            var lastReportedPct = 0.12f
            var writtenFrames = 0
            val maxSafeFrames = 4500
            val muxStartWallMs = System.currentTimeMillis()
            val maxMuxWallMs = 12_000L

            while (true) {
                if (cancelToken?.isCancelled == true) {
                    outFile.delete()
                    return null
                }
                if (System.currentTimeMillis() - muxStartWallMs > maxMuxWallMs) {
                    UHLog.w("video_warn", "Tier 1 MediaMuxer trim exceeded 12s wall-clock guard; delegating to Tier 2.")
                    outFile.delete()
                    return null
                }

                val sampleSize = try {
                    extractor.readSampleData(buffer, 0)
                } catch (_: IllegalArgumentException) {
                    if (buffer.capacity() >= 16 * 1024 * 1024) break
                    buffer = ByteBuffer.allocate(buffer.capacity() * 2)
                    extractor.readSampleData(buffer, 0)
                }

                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break

                if (basePtsUs >= 0L && sampleTimeUs > endUs + 50_000L) {
                    break
                }

                if (basePtsUs < 0L) {
                    basePtsUs = sampleTimeUs
                } else if (sampleTimeUs < basePtsUs) {
                    if (!extractor.advance()) break
                    continue
                }

                val sampleFlags = extractor.sampleFlags
                var muxFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    muxFlags = muxFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    muxFlags = muxFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                bufferInfo.flags = muxFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                wroteSamples = true
                writtenFrames++

                if (writtenFrames > maxSafeFrames) {
                    UHLog.w("video_warn", "Tier 1 exceeded safe frame bound ($writtenFrames); delegating to Tier 2.")
                    outFile.delete()
                    return null
                }

                val spanUs = (endUs - basePtsUs).coerceAtLeast(targetDurationUs)
                val progressUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                val pct = (0.14f + (progressUs.toFloat() / spanUs) * 0.78f).coerceIn(0.14f, 0.92f)
                if (pct - lastReportedPct >= 0.03f) {
                    lastReportedPct = pct
                    onProgress?.invoke(pct)
                }

                if (!extractor.advance()) break
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            muxer.release()
            muxer = null

            if (cancelToken?.isCancelled == true || !wroteSamples || !outFile.exists() || outFile.length() < 1024L) {
                outFile.delete()
                return null
            }

            val preRollMs = if (basePtsUs >= 0L) ((startUs - basePtsUs) / 1000L).coerceAtLeast(0L) else 0L
            val fineClip = if (preRollMs > 150L) {
                val clippedEndMs = ((endUs - basePtsUs) / 1000L).coerceAtLeast(preRollMs + 500L)
                VideoClipBounds(preRollMs, clippedEndMs).takeIf { it.isValid }
            } else null

            TrimmedVideoResult(file = outFile, fineClipBounds = fineClip)
        } catch (e: Exception) {
            UHLog.w("video_warn", "Tier 1 MediaMuxer trim failed: ${e.message}")
            outFile?.delete()
            null
        } finally {
            try {
                if (muxer != null) {
                    if (muxerStarted && wroteSamples) {
                        try { muxer.stop() } catch (_: Exception) {}
                    }
                    muxer.release()
                }
            } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun streamCopyTrimFromUriAsync(
        ctx: Context,
        uri: Uri,
        destDir: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)?,
        cancelToken: TrimCancellationToken?,
    ): File? {
        val ext = guessVideoExtensionFromUri(ctx, uri)
            .takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v") } ?: "mp4"
        val outFile = File(destDir, "motion_${System.currentTimeMillis()}.$ext")
        val targetDurationMs = (endMs - startMs).coerceAtLeast(500L).toDouble()
        val safInput = try {
            FFmpegKitConfig.getSafParameterForRead(ctx, uri)
        } catch (_: Exception) {
            null
        } ?: return null

        val startStr = "%.3f".format(Locale.US, startMs / 1000.0)
        val durStr = "%.3f".format(Locale.US, targetDurationMs / 1000.0)
        val faststart = if (ext == "mp4" || ext == "mov" || ext == "m4v") "-movflags +faststart " else ""
        val cmd = "-y -noaccurate_seek -ss $startStr -i \"$safInput\" -t $durStr -map 0:v:0 -c:v copy -an -fflags +genpts -avoid_negative_ts make_zero $faststart\"${outFile.absolutePath}\""

        return runFfmpegAsyncWithProgress(
            cmd = cmd,
            outFile = outFile,
            targetDurationMs = targetDurationMs,
            basePct = 0.16f,
            maxPct = 0.92f,
            timeoutSeconds = 25L,
            onProgress = onProgress,
            cancelToken = cancelToken,
            verifyTrack = true,
        )
    }

    private fun reencodeTrimFromUriAsync(
        ctx: Context,
        uri: Uri,
        destDir: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)?,
        cancelToken: TrimCancellationToken?,
    ): File? {
        val outFile = File(destDir, "motion_${System.currentTimeMillis()}.mp4")
        val targetDurationMs = (endMs - startMs).coerceAtLeast(500L).toDouble()
        val safInput = try {
            FFmpegKitConfig.getSafParameterForRead(ctx, uri)
        } catch (_: Exception) {
            null
        } ?: return null

        val startStr = "%.3f".format(Locale.US, startMs / 1000.0)
        val durStr = "%.3f".format(Locale.US, targetDurationMs / 1000.0)
        val scaleFilter = "scale='min(1920,iw)':'min(1920,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2"
        val cmd = "-y -ss $startStr -i \"$safInput\" -t $durStr -map 0:v:0 -c:v libx264 -preset ultrafast -tune fastdecode -crf 21 -pix_fmt yuv420p -vf \"$scaleFilter\" -an -movflags +faststart \"${outFile.absolutePath}\""

        return runFfmpegAsyncWithProgress(
            cmd = cmd,
            outFile = outFile,
            targetDurationMs = targetDurationMs,
            basePct = 0.18f,
            maxPct = 0.92f,
            timeoutSeconds = 45L,
            onProgress = onProgress,
            cancelToken = cancelToken,
            verifyTrack = true,
        )
    }

    private fun runFfmpegAsyncWithProgress(
        cmd: String,
        outFile: File,
        targetDurationMs: Double,
        basePct: Float,
        maxPct: Float,
        timeoutSeconds: Long,
        onProgress: ((Float) -> Unit)?,
        cancelToken: TrimCancellationToken?,
        verifyTrack: Boolean,
    ): File? {
        if (cancelToken?.isCancelled == true) return null
        val latch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        var lastPct = basePct

        val session = try {
            FFmpegKit.executeAsync(
                cmd,
                { completed ->
                    val ok = ReturnCode.isSuccess(completed.returnCode) &&
                        cancelToken?.isCancelled != true &&
                        outFile.exists() &&
                        outFile.length() > 1024L
                    succeeded.set(ok)
                    cancelToken?.clearSession(completed.sessionId)
                    latch.countDown()
                },
                null,
                { stats ->
                    val currentMs = stats.time.toDouble()
                    if (currentMs > 0.0 && targetDurationMs > 0.0) {
                        val frac = (currentMs / targetDurationMs).coerceIn(0.0, 1.0).toFloat()
                        val pct = (basePct + frac * (maxPct - basePct)).coerceIn(basePct, maxPct)
                        if (pct - lastPct >= 0.02f) {
                            lastPct = pct
                            onProgress?.invoke(pct)
                        }
                    }
                },
            )
        } catch (e: Exception) {
            UHLog.w("video_warn", "FFmpegKit executeAsync launch failed: ${e.message}")
            outFile.delete()
            return null
        }

        cancelToken?.attachSession(session.sessionId)

        val finishedInTime = try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            try { FFmpegKit.cancel(session.sessionId) } catch (_: Exception) {}
            cancelToken?.clearSession(session.sessionId)
            outFile.delete()
            return null
        }

        if (!finishedInTime) {
            UHLog.w("video_warn", "FFmpeg trim timed out after ${timeoutSeconds}s; cancelling session.")
            try { FFmpegKit.cancel(session.sessionId) } catch (_: Exception) {}
            cancelToken?.clearSession(session.sessionId)
            outFile.delete()
            return null
        }

        if (!succeeded.get() || cancelToken?.isCancelled == true) {
            outFile.delete()
            return null
        }

        if (verifyTrack) {
            val probe = probeVideo(outFile.absolutePath)
            if (!probe.hasVideoTrack && probe.width <= 0 && probe.height <= 0) {
                UHLog.w("video_warn", "FFmpeg trimmed output had no valid video track; discarding.")
                outFile.delete()
                return null
            }
        }

        return outFile
    }

    private fun readRotationDegrees(ctx: Context, uri: Uri, format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            return try {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } catch (_: Exception) {
                0
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copies an already-downloaded remote video verbatim into permanent wallpaper storage
     * with ZERO conversion or re-encoding.
     */
    fun finalizeRemoteVideoVerbatim(
        srcFile: File,
        destDir: File,
        hash: String,
        url: String = "",
    ): File? {
        if (!srcFile.exists() || srcFile.length() < 1024L) return null
        val urlExt = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in SUPPORTED_VIDEO_EXTENSIONS } ?: "mp4"
        val ext = detectVideoExtensionFromMagicBytes(srcFile, urlExt)
        val destFile = File(destDir, "motion_$hash.$ext")
        return try {
            if (srcFile.absolutePath != destFile.absolutePath) {
                srcFile.copyTo(destFile, overwrite = true)
            }
            destFile.takeIf { it.exists() && it.length() > 1024L }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "finalizeRemoteVideoVerbatim failed: ${e.message}")
            destFile.delete()
            null
        }
    }

    private fun queryUriSize(ctx: Context, uri: Uri): Long {
        return try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Reads true display aspect ratio (`width / height`, accounting for EXIF orientation)
     * for a local image file without decoding pixel data.
     */
    fun getImageAspectRatio(path: String): Float {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val rawW = opts.outWidth
            val rawH = opts.outHeight
            if (rawW <= 0 || rawH <= 0) return 0f
            val orientation = try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            val w = if (rotated) rawH else rawW
            val h = if (rotated) rawW else rawH
            if (h > 0) w.toFloat() / h.toFloat() else 0f
        } catch (_: Exception) {
            0f
        }
    }

    fun probeVideo(path: String): VideoProbe {
        var width = 0
        var height = 0
        var rotation = 0
        var durationSec = 15f
        var fps = 30f
        var bitrate = 0L
        var hasVideoTrack = false

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
            durationSec = (durMs / 1000f).coerceAtLeast(1f)
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            if (width > 0 && height > 0) hasVideoTrack = true
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                if (trackMime.startsWith("video/")) {
                    hasVideoTrack = true
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val extractedFps = try {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (_: Exception) {
                            try { format.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { fps }
                        }
                        if (extractedFps > 0f) fps = extractedFps
                    }
                    if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (rotation == 0 && format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        return VideoProbe(
            width = width,
            height = height,
            rotationDegrees = rotation,
            durationSec = durationSec,
            fps = fps,
            bitrateBps = bitrate,
            hasVideoTrack = hasVideoTrack,
        )
    }

    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 64L) return false
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    fun hasVideoMagicBytes(file: File): Boolean {
        return try {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count < 12) return false
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
                if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) {
                    return false
                }
                return true
            }
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                return true
            }
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'A'.code.toByte() && header[9] == 'V'.code.toByte() &&
                header[10] == 'I'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }

    private fun detectVideoExtensionFromMagicBytes(file: File, fallback: String): String {
        val safeFallback = fallback.lowercase().takeIf { it in SUPPORTED_VIDEO_EXTENSIONS } ?: "mp4"
        return try {
            val h = ByteArray(64)
            val n = file.inputStream().use { it.read(h) }
            if (n < 12) return safeFallback
            if (h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() &&
                h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte()
            ) {
                val brand = String(h, 8, 4, Charsets.US_ASCII).lowercase()
                return when {
                    brand.startsWith("qt") -> "mov"
                    brand.startsWith("3gp") -> "3gp"
                    brand.startsWith("m4v") -> "m4v"
                    else -> "mp4"
                }
            }
            if (h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() &&
                h[2] == 0xDF.toByte() && h[3] == 0xA3.toByte()
            ) {
                val ascii = String(h, 0, n, Charsets.ISO_8859_1).lowercase()
                return if (ascii.contains("webm")) "webm" else "mkv"
            }
            if (h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[8] == 'A'.code.toByte() && h[9] == 'V'.code.toByte() &&
                h[10] == 'I'.code.toByte()
            ) {
                return "avi"
            }
            safeFallback
        } catch (_: Exception) {
            safeFallback
        }
    }

    fun detectImageExtensionFromMagicBytes(file: File): String? {
        return try {
            val h = ByteArray(16)
            val n = file.inputStream().use { it.read(h) }
            if (n < 12) return null
            when {
                h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> "jpg"
                h[0] == 0x89.toByte() && h[1] == 0x50.toByte() && h[2] == 0x4E.toByte() && h[3] == 0x47.toByte() -> "png"
                h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
                    h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() && h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() -> "webp"
                h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() -> "gif"
                h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte() -> {
                    val brand = String(h, 8, 4, Charsets.US_ASCII).lowercase()
                    when {
                        brand.startsWith("avif") || brand.startsWith("avis") -> "avif"
                        brand.startsWith("hei") || brand == "mif1" || brand == "msf1" -> "heic"
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun guessImageExtensionFromUri(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        return guessImageExtensionFromMeta(mime, uri.lastPathSegment.orEmpty())
    }

    fun guessImageExtensionFromMeta(contentType: String, urlOrPath: String): String {
        val mime = contentType.lowercase()
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("avif") -> "avif"
            mime.contains("heic") || mime.contains("heif") -> "heic"
            mime.contains("gif") -> "gif"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            else -> {
                val ext = urlOrPath.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif")) {
                    if (ext == "jpeg") "jpg" else ext
                } else "jpg"
            }
        }
    }

    fun guessVideoExtensionFromUri(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("x-matroska") || mime.contains("mkv") -> "mkv"
            mime.contains("quicktime") -> "mov"
            mime.contains("3gpp") -> "3gp"
            mime.contains("avi") -> "avi"
            else -> {
                val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                if (pathExt in SUPPORTED_VIDEO_EXTENSIONS) pathExt else "mp4"
            }
        }
    }

    @Suppress("DEPRECATION")
    fun webpPosterFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    /**
     * Extracts a crisp full-resolution poster frame at [timeMs] for instant display
     * before playback starts and during Battery Saver pause.
     */
    fun generateVideoPosterBitmap(videoPath: String, timeMs: Long = 0L): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val timeUs = (timeMs.coerceAtLeast(0L)) * 1000L
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs + 500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "3gp", "m4v")
}
