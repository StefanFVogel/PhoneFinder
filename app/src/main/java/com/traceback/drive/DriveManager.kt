package com.traceback.drive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages KML file storage in Google Drive.
 * Files are stored in a visible "TraceBack" folder in user's Drive.
 */
class DriveManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DriveManager"
        private const val APP_NAME = "TraceBack"
        private const val FOLDER_NAME = "TraceBack"
        private const val MIME_KML = "application/vnd.google-earth.kml+xml"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    
    private var driveService: Drive? = null
    private var folderId: String? = null
    
    /**
     * Initialize Drive service with signed-in Google account.
     */
    fun initialize(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
        
        Log.i(TAG, "Drive service initialized for ${account.email}")
    }
    
    /**
     * Check if Drive is ready.
     */
    fun isReady(): Boolean {
        if (driveService != null) return true
        
        // Try to auto-initialize from last signed-in account
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            initialize(account)
            return true
        }
        return false
    }
    
    /**
     * Get or create the TraceBack folder in Drive root.
     */
    private suspend fun getOrCreateFolder(): String? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        
        // Return cached folder ID
        folderId?.let { return@withContext it }
        
        try {
            // Search for existing folder
            val result = service.files().list()
                .setQ("name = '$FOLDER_NAME' and mimeType = '$MIME_FOLDER' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()
            
            if (result.files.isNotEmpty()) {
                folderId = result.files[0].id
                Log.i(TAG, "Found existing folder: $folderId")
                return@withContext folderId
            }
            
            // Create new folder
            val folderMetadata = DriveFile().apply {
                name = FOLDER_NAME
                mimeType = MIME_FOLDER
            }
            val folder = service.files().create(folderMetadata)
                .setFields("id")
                .execute()
            folderId = folder.id
            Log.i(TAG, "Created folder: $folderId")
            return@withContext folderId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get/create folder", e)
            return@withContext null
        }
    }
    
    /**
     * Upload KML content to TraceBack folder (visible in Drive).
     */
    suspend fun uploadKml(kmlContent: String): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: run {
            Log.e(TAG, "Drive service not initialized")
            return@withContext false
        }
        
        val parentFolder = getOrCreateFolder() ?: run {
            Log.e(TAG, "Failed to get folder")
            return@withContext false
        }
        
        try {
            val today = dateFormat.format(Date())
            val fileName = "track_$today.kml"
            
            // Check if file already exists
            val existingFile = findFile(fileName, parentFolder)
            
            val content = ByteArrayContent.fromString(MIME_KML, kmlContent)
            
            if (existingFile != null) {
                // Update existing file
                service.files().update(existingFile.id, null, content).execute()
                Log.i(TAG, "Updated $fileName")
            } else {
                // Create new file
                val metadata = DriveFile().apply {
                    name = fileName
                    parents = listOf(parentFolder)
                }
                service.files().create(metadata, content)
                    .setFields("id")
                    .execute()
                Log.i(TAG, "Created $fileName")
            }
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            return@withContext false
        }
    }
    
    /**
     * Find a file by name in TraceBack folder.
     */
    private fun findFile(fileName: String, parentFolder: String): DriveFile? {
        val service = driveService ?: return null
        
        val result = service.files().list()
            .setSpaces("drive")
            .setQ("name = '$fileName' and '$parentFolder' in parents and trashed = false")
            .setFields("files(id, name)")
            .execute()
        
        return result.files.firstOrNull()
    }
    
    /**
     * List all KML files in TraceBack folder.
     */
    suspend fun listFiles(): List<DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyList()
        
        val parentFolder = getOrCreateFolder() ?: return@withContext emptyList()
        
        try {
            val result = service.files().list()
                .setSpaces("drive")
                .setQ("'$parentFolder' in parents and trashed = false")
                .setFields("files(id, name, createdTime, size)")
                .setOrderBy("createdTime desc")
                .execute()
            
            return@withContext result.files ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Upload Ping KML (overwrites existing ping.kml).
     */
    suspend fun uploadPingKml(kmlContent: String): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: run {
            Log.e(TAG, "Drive service not initialized")
            return@withContext false
        }
        
        val parentFolder = getOrCreateFolder() ?: run {
            Log.e(TAG, "Failed to get folder")
            return@withContext false
        }
        
        try {
            val fileName = "ping.kml"
            
            // Check if file already exists
            val existingFile = findFile(fileName, parentFolder)
            
            val content = ByteArrayContent.fromString(MIME_KML, kmlContent)
            
            if (existingFile != null) {
                // Update existing file
                service.files().update(existingFile.id, null, content).execute()
                Log.i(TAG, "Updated $fileName")
            } else {
                // Create new file
                val metadata = DriveFile().apply {
                    name = fileName
                    parents = listOf(parentFolder)
                }
                service.files().create(metadata, content)
                    .setFields("id")
                    .execute()
                Log.i(TAG, "Created $fileName")
            }
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Ping upload failed", e)
            return@withContext false
        }
    }
    
    /**
     * Upload Last Breath KML with timestamp in filename.
     */
    suspend fun uploadLastBreathKml(kmlContent: String): Boolean = withContext(Dispatchers.IO) {
        val service = driveService ?: run {
            Log.e(TAG, "Drive service not initialized")
            return@withContext false
        }
        
        val parentFolder = getOrCreateFolder() ?: run {
            Log.e(TAG, "Failed to get folder")
            return@withContext false
        }
        
        try {
            // Create unique filename with datetime
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val fileName = "last_breath_${dateTimeFormat.format(Date())}.kml"
            
            val content = ByteArrayContent.fromString(MIME_KML, kmlContent)
            
            // Always create new file (don't overwrite)
            val metadata = DriveFile().apply {
                name = fileName
                parents = listOf(parentFolder)
            }
            service.files().create(metadata, content)
                .setFields("id")
                .execute()
            Log.i(TAG, "Created $fileName")
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Last Breath upload failed", e)
            return@withContext false
        }
    }
    
    /**
     * Download a KML file by ID.
     */
    suspend fun downloadFile(fileId: String): String? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        
        try {
            val outputStream = java.io.ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            return@withContext outputStream.toString("UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            return@withContext null
        }
    }
    
    /**
     * Delete old files (data aging).
     */
    suspend fun deleteOldFiles(olderThanDays: Int) = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext
        
        val cutoff = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -olderThanDays)
        }.time
        
        try {
            val files = listFiles()
            files.forEach { file ->
                val createdTime = Date(file.createdTime.value)
                if (createdTime.before(cutoff)) {
                    service.files().delete(file.id).execute()
                    Log.i(TAG, "Deleted old file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete old files", e)
        }
    }
}
