package com.github.croyla.mapeditor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class
GeoJsonEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension?.lowercase() == "geojson"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return GeoJsonSplitEditor(project, file)
    }

    override fun getEditorTypeId(): String = "geojson-map-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}