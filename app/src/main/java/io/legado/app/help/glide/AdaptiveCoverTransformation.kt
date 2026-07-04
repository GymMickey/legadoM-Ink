package io.legado.app.help.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.security.MessageDigest

/**
 * 自适应封面裁剪转换
 *
 * 根据图片实际宽高比与容器比例的关系，自动选择裁剪策略：
 * - 竖版图（宽高比 ≤ 容器比例，如常见书籍封面 2:3）：使用 centerCrop，行为与之前完全一致
 * - 横版图（宽高比 > 容器比例，如 16:9、4:3 等宽图）：使用 fitCenter，完整显示不裁剪
 *
 * 解决部分书源封面图片宽度较大、在固定 3:4 容器中使用 centerCrop
 * 导致左右大量内容被裁剪、封面无法完整显示的问题。
 *
 * @param containerAspectRatio 容器宽高比，封面场景通常为 3f / 4f
 */
class AdaptiveCoverTransformation(
    private val containerAspectRatio: Float
) : BitmapTransformation() {

    /**
     * 执行自适应裁剪变换
     *
     * 根据图片自身的宽高比与容器比例做决策：
     * 大于容器比例（横版图）→ 完整显示
     * 小于等于容器比例（竖版图）→ 标准 centerCrop
     */
    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (outWidth <= 0 || outHeight <= 0) return toTransform

        val imageWidth = toTransform.width
        val imageHeight = toTransform.height
        if (imageWidth <= 0 || imageHeight <= 0) return toTransform

        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

        return if (imageAspectRatio > containerAspectRatio) {
            transformFitCenter(pool, toTransform, outWidth, outHeight)
        } else {
            TransformationUtils.centerCrop(pool, toTransform, outWidth, outHeight)
        }
    }

    /**
     * FitCenter 变换：等比例缩放图片使其完整适配容器，居中显示，空白区域透明
     *
     * 流程：
     * 1. 计算缩放比例（取宽高缩放比中较小的值，确保不超出容器）
     * 2. 创建缩放后的中间 Bitmap
     * 3. 从 BitmapPool 复用目标尺寸的 Bitmap，先清空像素防残留
     * 4. 将缩放后的图片居中绘制到结果 Bitmap 上
     * 5. 立即回收中间 Bitmap 归还内存
     */
    private fun transformFitCenter(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val scaleX = outWidth.toFloat() / toTransform.width
        val scaleY = outHeight.toFloat() / toTransform.height
        val scale = minOf(scaleX, scaleY)
        val scaledWidth = (toTransform.width * scale).toInt()
        val scaledHeight = (toTransform.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(toTransform, scaledWidth, scaledHeight, true)

        val result = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        result.eraseColor(0x00000000)
        val canvas = Canvas(result)
        val dx = (outWidth - scaledWidth) / 2f
        val dy = (outHeight - scaledHeight) / 2f
        canvas.drawBitmap(scaled, dx, dy, null)
        scaled.recycle()

        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("adaptive_cover_${containerAspectRatio}".toByteArray())
    }
}
