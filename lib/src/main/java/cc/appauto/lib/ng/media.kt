package cc.appauto.lib.ng

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

@SuppressLint("StaticFieldLeak")
object MediaRuntime {
    private val name = "media"

    val displayMetrics = DisplayMetrics()

    // handler to run media work
    var mediaHandler: Handler
        private set

    internal lateinit var imageReader: ImageReader

    private lateinit var requestLauncher: ActivityResultLauncher<Intent>
    private lateinit var ctx: Context

    private var mediaThread: HandlerThread = HandlerThread("${TAG}_${name}_thread")

    private var initialized: Boolean = false

    init {
        mediaThread.start()
        mediaHandler = Handler(mediaThread.looper)
    }

    private fun prepareMediaRequestLauncher(activity: AppCompatActivity) {
        requestLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null) {
                Log.w(TAG, "$name: request media projection approved by user, start media service now")
                val intent = Intent(ctx, AppAutoMediaService::class.java)
                intent.putExtra("data", it.data)
                intent.putExtra("resultCode", it.resultCode)
                intent.putExtra("surface", imageReader.surface)
                ctx.startService(intent)
            }
            else {
                Log.w(TAG, "$name: request media projection denied by user, result code: ${it.resultCode}")
            }
        }
    }

    // if setup invoked again after initialized, just update the request launcher with given activity and return
    @Synchronized
    internal fun setup(activity: AppCompatActivity) {
        if (initialized) {
            requestLauncher.unregister()
            prepareMediaRequestLauncher(activity)
            Log.i(TAG, "$name: automedia updated with latest activity")
            return
        }

        ctx = activity.applicationContext
        AppAutoContext.windowManager.defaultDisplay.getMetrics(displayMetrics)
        imageReader = ImageReader.newInstance(displayMetrics.widthPixels, displayMetrics.heightPixels, PixelFormat.RGBA_8888, 3)
        prepareMediaRequestLauncher(activity)

        initialized = true
        Log.i(TAG, "$name: automedia initialized")
    }

    fun requestMediaProjection(): Boolean {
        requestLauncher.launch(AppAutoContext.mediaProjectionManager.createScreenCaptureIntent())
        return true
    }


    fun onImageAvailable(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        if (image.planes.isEmpty()) {
            image.close()
            return
        }
        val plane = image.planes[0]
        val width = plane.rowStride/plane.pixelStride
        Log.i(TAG, "$name: image available, plane: ${plane.rowStride}/${plane.pixelStride}, image: ${image.width} x ${image.height}")
        var bitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)

        try {
            val dir = ctx.getExternalFilesDir(null)
            val fn = File(dir, "${getDateStr()}.jpg")
            val fos = FileOutputStream(fn)
            // crop the bitmap to image.width/image.height
            if (width != image.width) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            }
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        } catch (e: Exception){
            Log.e(TAG, "$name: save screenshot bitmap leads to exception:\n${Log.getStackTraceString(e)}")
        }
        image.close()
    }
}