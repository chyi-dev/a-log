package com.chyi.alog.sample

import android.os.Environment
import java.io.File

/** sample 与 sample-viewer 共用的公开落盘路径（Documents/a-log）。 */
object SharedAlogDirs {
    const val ROOT_DIR_NAME = "a-log"
    const val FILES_DIR_NAME = "files"

    fun root(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_DIR_NAME)

    fun filesDir(): File = File(root(), FILES_DIR_NAME)
}
