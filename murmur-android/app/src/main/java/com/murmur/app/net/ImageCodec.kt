package com.murmur.app.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 把本地图片压成 data URI。
 *
 * 直接塞原图是不行的：手机随手一拍就是 4000×3000、四五 MB，
 * base64 之后再涨三分之一，一条消息就能顶掉整个上下文预算，还慢得离谱。
 * 视觉模型基本都在 1024 上限内切图块，所以先缩到长边 1024 再编码，
 * 质量几乎无损而体积能小一到两个数量级。
 */
object ImageCodec {

    const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 85

    fun toDataUri(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return null

        // 先只读边界，避免把大图整个解进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_EDGE * 2) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        } else decoded

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()

        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
