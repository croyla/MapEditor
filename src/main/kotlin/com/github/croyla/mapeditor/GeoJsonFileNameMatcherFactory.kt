package com.github.croyla.mapeditor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class GeoJsonFileNameMatcherFactory : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Associate .geojson extension with JSON file type
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val fileTypeManager = FileTypeManager.getInstance()
                    val jsonFileType = fileTypeManager.getFileTypeByExtension("json")

                    if (jsonFileType.name != "UNKNOWN") {
                        (fileTypeManager as? FileTypeManagerImpl)?.associate(
                            jsonFileType,
                            ExtensionFileNameMatcher("geojson")
                        )
                    }
                } catch (e: Exception) {
                    // Fallback: association might already exist or method not available
                }
            }
        }
    }
}