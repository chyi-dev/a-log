package com.chyi.alog.printer.file

/** [FilePrinter.Builder] 与 Writer 共用的默认 backup / 命名解析。 */
internal object FilePrinterDefaults {
    fun resolveBackup(custom: BackupStrategy?): BackupStrategy =
        custom ?: FileSizeBackupStrategy()

    fun resolveNameGenerator(
        custom: FileNameGenerator?,
        backup: BackupStrategy,
        maxFileSize: Long,
    ): FileNameGenerator = custom ?: if (backup is NeverBackupStrategy) {
        DateOnlyFileNameGenerator()
    } else {
        DateFileNameGenerator(maxFileSize)
    }
}
