package io.legado.app.ui.file

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.UploadHistory
import io.legado.app.help.DirectLinkUpload
import io.legado.app.model.upload.DirectLinkUploadRepository
import io.legado.app.utils.*

import java.io.File

class HandleFileViewModel(application: Application) : BaseViewModel(application) {

    val errorLiveData = MutableLiveData<String>()
    private val repository = DirectLinkUploadRepository()

    fun upload(
        fileName: String,
        file: Any,
        contentType: String,
        success: (url: String) -> Unit
    ) {
        execute {
            repository.importDefaultRules()
            val rule = repository.getDefaultRule()
                ?: throw IllegalStateException("没有可用的上传规则")
            
            val startTime = System.currentTimeMillis()
            
            try {
                // 使用新方法获取下载链接和文件大小
                val (downloadUrl, fileSize) = DirectLinkUpload.upLoadWithSize(
                    fileName = fileName,
                    file = file,
                    contentType = contentType,
                    rule = DirectLinkUpload.Rule(
                        uploadUrl = rule.uploadUrl,
                        downloadUrlRule = rule.downloadUrlRule,
                        summary = rule.summary,
                        compress = rule.compress
                    )
                )
                
                val duration = System.currentTimeMillis() - startTime
                
                val history = UploadHistory(
                    fileName = fileName,
                    fileSize = fileSize,  // 使用从上传方法返回的文件大小
                    contentType = contentType,
                    duration = duration,
                    downloadUrl = downloadUrl,
                    ruleId = rule.id,
                    ruleSummary = rule.summary,
                    success = true
                )
                
                repository.addHistory(history)
                repository.incrementUploadCount(rule.id)
                
                downloadUrl
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                
                // 失败时也要计算文件大小
                val fileSize = getFileSize(file)
                
                val history = UploadHistory(
                    fileName = fileName,
                    fileSize = fileSize,
                    contentType = contentType,
                    duration = duration,
                    downloadUrl = "",
                    ruleId = rule.id,
                    ruleSummary = rule.summary,
                    success = false,
                    errorMsg = e.localizedMessage
                )
                
                repository.addHistory(history)
                
                throw e
            }
        }.onSuccess {
            success.invoke(it)
        }.onError {
            AppLog.put("上传文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }
    }
    
    /**
     * 获取文件大小
     * 支持多种文件类型：File、ByteArray、String、其他对象（转为JSON）
     * 
     * @param file 文件对象
     * @return 文件大小（字节）
     */
    private fun getFileSize(file: Any): Long {
        return when (file) {
            is File -> {
                val size = file.length()
                android.util.Log.d("HandleFileViewModel", "File type: File, size: $size")
                size
            }
            is ByteArray -> {
                val size = file.size.toLong()
                android.util.Log.d("HandleFileViewModel", "File type: ByteArray, size: $size")
                size
            }
            is String -> {
                val size = file.toByteArray().size.toLong()
                android.util.Log.d("HandleFileViewModel", "File type: String, size: $size")
                size
            }
            else -> {
                // 其他类型转换为JSON后计算大小
                try {
                    val json = GSON.toJson(file)
                    val size = json.toByteArray().size.toLong()
                    android.util.Log.d("HandleFileViewModel", "File type: ${file::class.simpleName}, JSON size: $size")
                    size
                } catch (e: Exception) {
                    android.util.Log.e("HandleFileViewModel", "Failed to calculate size for type: ${file::class.simpleName}", e)
                    0L
                }
            }
        }
    }

    fun saveToLocal(uri: Uri, fileName: String, data: Any, success: (uri: Uri) -> Unit) {
        execute {
            val bytes = when (data) {
                is File -> data.readBytes()
                is ByteArray -> data
                is String -> data.toByteArray()
                else -> GSON.toJson(data).toByteArray()
            }
            return@execute if (uri.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(context, uri)!!
                doc.findFile(fileName)?.delete()
                val newDoc = doc.createFile("", fileName)
                newDoc!!.writeBytes(context, bytes)
                newDoc.uri
            } else {
                val file = File(uri.path ?: uri.toString())
                val newFile = FileUtils.createFileIfNotExist(file, fileName)
                newFile.writeBytes(bytes)
                Uri.fromFile(newFile)
            }
        }.onError {
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }.onSuccess {
            success.invoke(it)
        }
    }

}