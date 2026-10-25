package io.github.kyotobyte.cdmplus.engine

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Unzips into [outDir], refusing entries that try to escape it (zip-slip). Returns files written. */
object ZipExtract {
    fun extract(input: InputStream, outDir: File, onFile: (File) -> Unit = {}): Int {
        val root = outDir.canonicalFile.also { it.mkdirs() }
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(root, e.name).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) { z.closeEntry(); continue }
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it, 256 * 1024) }
                    onFile(f); n++
                }
                z.closeEntry()
            }
        }
        return n
    }
}
