package com.github.croyla.mapeditor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class GeoJsonPreviewFileEditor(
    private val project: Project,
    private val file: VirtualFile
) : FileEditor {

    private val mapEditor: GeoJsonMapEditor
    private var textEditor: TextEditor? = null

    init {
        // Create map editor - will connect text editor later
        mapEditor = GeoJsonMapEditor(project, file, null, this)
    }

    fun setTextEditor(editor: TextEditor) {
        this.textEditor = editor
        mapEditor.setTextEditor(editor)
    }

    override fun getComponent(): JComponent = mapEditor.component

    override fun getPreferredFocusedComponent(): JComponent = mapEditor.component

    override fun getName(): String = "Map Preview"

    override fun setState(state: FileEditorState) {
        // No state to set for preview
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // No properties to listen to
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // No properties to listen to
    }

    override fun dispose() {
        mapEditor.dispose()
    }

    override fun getFile(): VirtualFile = file

    override fun <T : Any?> getUserData(key: Key<T>): T? = null

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) {
        // No user data
    }
}