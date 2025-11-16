package com.github.croyla.mapeditor

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class GeoJsonSplitEditor private constructor(
    textEditor: TextEditor,
    preview: GeoJsonPreviewFileEditor
) : TextEditorWithPreview(
    textEditor,
    preview,
    "GeoJSON Editor",
    Layout.SHOW_EDITOR_AND_PREVIEW,
    false // horizontal split
) {
    companion object {
        operator fun invoke(project: Project, file: VirtualFile): GeoJsonSplitEditor {
            val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
            val preview = GeoJsonPreviewFileEditor(project, file)
            preview.setTextEditor(textEditor)
            return GeoJsonSplitEditor(textEditor, preview)
        }
    }
}