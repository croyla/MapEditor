package com.github.croyla.mapeditor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class GeoJsonFileType : FileType {
    override fun getName(): String = "GeoJSON"

    override fun getDescription(): String = "GeoJSON geographic data format"

    override fun getDefaultExtension(): String = "geojson"

    override fun getIcon(): Icon? = IconLoader.getIcon("/icons/geojson.svg", GeoJsonFileType::class.java)

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = false

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = "UTF-8"

    companion object {
        val INSTANCE = GeoJsonFileType()
    }
}