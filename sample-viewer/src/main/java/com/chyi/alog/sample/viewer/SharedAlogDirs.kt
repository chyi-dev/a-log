package com.chyi.alog.sample.viewer

import android.os.Environment
import java.io.File

/** 与 sample 的 SharedAlogDirs 保持同一相对路径。 */
object SharedAlogDirs {
    const val ROOT_DIR_NAME = "a-log"
    const val FILES_DIR_NAME = "files"

    fun root(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_DIR_NAME)

    fun filesDir(): File = File(root(), FILES_DIR_NAME)
}
