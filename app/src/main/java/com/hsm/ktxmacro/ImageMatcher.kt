package com.hsm.ktxmacro

import android.graphics.Bitmap
import android.graphics.Point
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

// PC의 cv2.matchTemplate() 대체
class ImageMatcher {

    init {
        OpenCVLoader.initLocal()
    }

    // 전체 화면에서 템플릿 탐색 → 중심 좌표 반환 (없으면 null)
    // PC: self._match(template)
    fun match(screen: Bitmap, template: Bitmap, threshold: Float = 0.85f): Point? {
        val screenMat = toMat(screen)
        val templateMat = toMat(template)

        if (templateMat.rows() > screenMat.rows() || templateMat.cols() > screenMat.cols())
            return null

        val result = Mat()
        Imgproc.matchTemplate(screenMat, templateMat, result, Imgproc.TM_CCOEFF_NORMED)

        val mm = Core.minMaxLoc(result)
        if (mm.maxVal >= threshold) {
            return Point(
                (mm.maxLoc.x + templateMat.cols() / 2).toInt(),
                (mm.maxLoc.y + templateMat.rows() / 2).toInt()
            )
        }
        return null
    }

    // 특정 영역 안에서만 탐색 → 절대 좌표 반환
    // PC: self._match(template, region=(rx, ry, rw, rh))
    fun matchInRegion(
        screen: Bitmap, template: Bitmap,
        rx: Int, ry: Int, rw: Int, rh: Int,
        threshold: Float = 0.85f
    ): Point? {
        val region = Bitmap.createBitmap(screen, rx, ry, rw, rh)
        val rel = match(region, template, threshold) ?: return null
        return Point(rx + rel.x, ry + rel.y)
    }

    private fun toMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        return mat
    }
}
