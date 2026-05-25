package com.hsm.ktxmacro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    private val backupFile: File
        get() {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            return File(dir, "ktxmacro_backup.zip")
        }

    // ── 기본값 초기화 (최초 설치 시 한 번만) ─────────────────────────
    fun loadDefaultsIfNeeded() {
        val prefs = context.getSharedPreferences("backup_meta", Context.MODE_PRIVATE)
        if (prefs.getBoolean("defaults_loaded", false)) return

        // 이미지 기본값 복사
        mapOf(
            R.raw.default_b1 to "b1.png",
            R.raw.default_b2 to "b2.png",
            R.raw.default_b9 to "b9.png",
            R.raw.default_sb1 to "sb1.png",
            R.raw.default_sb2 to "sb2.png"
        ).forEach { (resId, fileName) ->
            val dest = File(context.filesDir, fileName)
            if (!dest.exists()) {
                context.resources.openRawResource(resId).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        // 텍스트 기본값 복사
        val json = context.resources.openRawResource(R.raw.default_settings)
            .bufferedReader().readText()
        val root = JSONObject(json)

        val ktxPrefs = context.getSharedPreferences("text_targets", Context.MODE_PRIVATE).edit()
        val ktxObj = root.getJSONObject("ktx")
        ktxObj.keys().forEach { k ->
            if (context.getSharedPreferences("text_targets", Context.MODE_PRIVATE)
                    .getString(k, null) == null) {
                ktxPrefs.putString(k, ktxObj.getString(k))
            }
        }
        ktxPrefs.apply()

        val srtPrefs = context.getSharedPreferences("srt_text_targets", Context.MODE_PRIVATE).edit()
        val srtObj = root.getJSONObject("srt")
        srtObj.keys().forEach { k ->
            if (context.getSharedPreferences("srt_text_targets", Context.MODE_PRIVATE)
                    .getString(k, null) == null) {
                srtPrefs.putString(k, srtObj.getString(k))
            }
        }
        srtPrefs.apply()

        prefs.edit().putBoolean("defaults_loaded", true).apply()
    }

    // ── 백업 내보내기 ────────────────────────────────────────────────
    fun export(onResult: (Boolean, String) -> Unit) {
        try {
            val zip = backupFile
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                // 이미지 파일
                listOf("b1.png", "b2.png", "b9.png", "sb1.png", "sb2.png").forEach { name ->
                    val f = File(context.filesDir, name)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("images/$name"))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // 텍스트 설정
                val root = JSONObject()
                val ktxObj = JSONObject()
                val ktxPrefs = context.getSharedPreferences("text_targets", Context.MODE_PRIVATE)
                ktxPrefs.all.forEach { (k, v) -> ktxObj.put(k, v?.toString() ?: "") }
                root.put("ktx", ktxObj)

                val srtObj = JSONObject()
                val srtPrefs = context.getSharedPreferences("srt_text_targets", Context.MODE_PRIVATE)
                srtPrefs.all.forEach { (k, v) -> srtObj.put(k, v?.toString() ?: "") }
                root.put("srt", srtObj)

                zos.putNextEntry(ZipEntry("settings.json"))
                zos.write(root.toString(2).toByteArray())
                zos.closeEntry()
            }
            onResult(true, zip.absolutePath)
        } catch (e: Exception) {
            onResult(false, e.message ?: "알 수 없는 오류")
        }
    }

    // ── 백업 불러오기 ────────────────────────────────────────────────
    fun import(
        onImageRestored: (String, Bitmap) -> Unit,
        onResult: (Boolean, String) -> Unit
    ) {
        val zip = backupFile
        if (!zip.exists()) {
            onResult(false, "백업 파일 없음:\n${zip.absolutePath}")
            return
        }
        try {
            ZipInputStream(FileInputStream(zip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name.startsWith("images/") -> {
                            val name = entry.name.removePrefix("images/")
                            val dest = File(context.filesDir, name)
                            val bytes = zis.readBytes()
                            dest.writeBytes(bytes)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val key = name.removeSuffix(".png")
                                onImageRestored(key, bmp)
                            }
                        }
                        entry.name == "settings.json" -> {
                            val root = JSONObject(zis.readBytes().toString(Charsets.UTF_8))

                            val ktxPrefs = context.getSharedPreferences("text_targets", Context.MODE_PRIVATE).edit()
                            val ktxObj = root.optJSONObject("ktx")
                            ktxObj?.keys()?.forEach { k -> ktxPrefs.putString(k, ktxObj.getString(k)) }
                            ktxPrefs.apply()

                            val srtPrefs = context.getSharedPreferences("srt_text_targets", Context.MODE_PRIVATE).edit()
                            val srtObj = root.optJSONObject("srt")
                            srtObj?.keys()?.forEach { k -> srtPrefs.putString(k, srtObj.getString(k)) }
                            srtPrefs.apply()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            onResult(true, "복원 완료 → 매크로를 재시작하세요")
        } catch (e: Exception) {
            onResult(false, e.message ?: "복원 오류")
        }
    }

    fun backupPath(): String = backupFile.absolutePath
}
