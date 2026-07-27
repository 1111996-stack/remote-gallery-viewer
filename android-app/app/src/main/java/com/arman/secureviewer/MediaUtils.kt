package com.arman.secureviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object MediaUtils {

    private const val TAG = "MediaUtils"
    // ── THUMBNAIL SIZE UPDATE ──────────────────────────────────────────────
    private const val THUMB_W       = 100
    private const val THUMB_H       = 100
    private const val THUMB_QUALITY = 50
    // ─────────────────────────────────────────────────────────────────────
    // FIX: Hard limit — no freeze on large galleries
    private const val MAX_IMAGES = 400
    private const val MAX_VIDEOS = 100

    fun getGalleryJson(context: Context): JSONArray {
        val result = JSONArray()
        try {
            getImages(context, result)
            getVideos(context, result)
        } catch (e: Exception) {
            Log.e(TAG, "getGalleryJson error: ${e.message}")
        }
        Log.d(TAG, "Total items: ${result.length()}")
        return result
    }

    private fun getImages(context: Context, result: JSONArray) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE
        )
        // FIX: Sort newest first, LIMIT applied via cursor count check
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            var count = 0
            while (cursor.moveToNext() && count < MAX_IMAGES) {
                try {
                    val path = cursor.getString(dataCol) ?: continue
                    if (path.isBlank()) continue
                    val thumb = generateImageThumbnail(path) ?: continue
                    result.put(JSONObject().apply {
                        put("id",    cursor.getLong(idCol))
                        put("name",  cursor.getString(nameCol) ?: "")
                        put("path",  path)
                        put("size",  cursor.getLong(sizeCol))
                        put("date",  cursor.getLong(dateCol))
                        put("mime",  cursor.getString(mimeCol) ?: "image/jpeg")
                        put("type",  "image")
                        put("thumb", thumb)
                    })
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Image item error: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Images collected: ${result.length()}")
    }

    private fun getVideos(context: Context, result: JSONArray) {
        val startIdx = result.length()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            var count = 0
            while (cursor.moveToNext() && count < MAX_VIDEOS) {
                try {
                    val path = cursor.getString(dataCol) ?: continue
                    if (path.isBlank()) continue
                    val thumb = generateVideoThumbnail(path) ?: continue
                    result.put(JSONObject().apply {
                        put("id",       cursor.getLong(idCol))
                        put("name",     cursor.getString(nameCol) ?: "")
                        put("path",     path)
                        put("size",     cursor.getLong(sizeCol))
                        put("date",     cursor.getLong(dateCol))
                        put("mime",     cursor.getString(mimeCol) ?: "video/mp4")
                        put("type",     "video")
                        put("duration", cursor.getLong(durCol))
                        put("thumb",    thumb)
                    })
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Video item error: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Videos collected: ${result.length() - startIdx}")
    }

    private fun generateImageThumbnail(path: String): String? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0) return null
            opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / THUMB_W)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, THUMB_W, THUMB_H, true)
            if (bmp !== scaled) bmp.recycle()
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, baos)
            scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun generateVideoThumbnail(path: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, THUMB_W, THUMB_H, true)
            if (bmp !== scaled) bmp.recycle()
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, baos)
            scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
    // ── NEW PAGINATED FUNCTIONS — ADD THESE ───────────────────────────────
    // Main paginated entry point
    // type = "images" or "videos"
    fun getGalleryJsonPaginated(
        context: Context,
        offset: Int,
        limit: Int,
        type: String
    ): JSONArray {
        val result = JSONArray()
        try {
            when (type) {
                "images" -> collectImagesPaginated(context, result, offset, limit)
                "videos" -> collectVideosPaginated(context, result, offset, limit)
                else     -> {
                    // Unknown type — return empty
                    Log.w(TAG, "Unknown type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGalleryJsonPaginated: ${e.message}")
        }
        Log.d(TAG, "Paginated result: ${result.length()} items (offset=$offset limit=$limit type=$type)")
        return result
    }

    // Collect images starting at offset, up to limit items
    fun collectImagesPaginated(
        context: Context,
        result: JSONArray,
        offset: Int,
        limit: Int
    ) {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cur ->
            val iId   = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iData = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val iSize = cur.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iDate = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iMime = cur.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            // Jump to offset position directly
            if (offset > 0 && !cur.moveToPosition(offset)) {
                Log.d(TAG, "Offset $offset beyond image count ${cur.count}")
                return
            }
            if (offset == 0 && !cur.moveToFirst()) {
                Log.d(TAG, "No images found")
                return
            }

            var count = 0
            do {
                if (count >= limit) break
                try {
                    val path = cur.getString(iData) ?: continue
                    if (path.isBlank()) continue
                    val thumb = generateImageThumbnail(path) ?: continue
                    result.put(JSONObject().apply {
                        put("id",    cur.getLong(iId))
                        put("name",  cur.getString(iName) ?: "")
                        put("path",  path)
                        put("size",  cur.getLong(iSize))
                        put("date",  cur.getLong(iDate))
                        put("mime",  cur.getString(iMime) ?: "image/jpeg")
                        put("type",  "image")
                        put("thumb", thumb)
                    })
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "img skip: ${e.message}")
                }
            } while (cur.moveToNext())

            Log.d(TAG, "Images paginated: collected $count from offset $offset (cursor total=${cur.count})")
        }
    }

    // Collect videos starting at offset, up to limit items
    fun collectVideosPaginated(
        context: Context,
        result: JSONArray,
        offset: Int,
        limit: Int
    ) {
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            proj, null, null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cur ->
            val iId   = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iData = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val iSize = cur.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDate = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iMime = cur.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val iDur  = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            if (offset > 0 && !cur.moveToPosition(offset)) {
                Log.d(TAG, "Offset $offset beyond video count ${cur.count}")
                return
            }
            if (offset == 0 && !cur.moveToFirst()) {
                Log.d(TAG, "No videos found")
                return
            }

            var count = 0
            do {
                if (count >= limit) break
                try {
                    val path = cur.getString(iData) ?: continue
                    if (path.isBlank()) continue
                    val thumb = generateVideoThumbnail(path) ?: continue
                    result.put(JSONObject().apply {
                        put("id",       cur.getLong(iId))
                        put("name",     cur.getString(iName) ?: "")
                        put("path",     path)
                        put("size",     cur.getLong(iSize))
                        put("date",     cur.getLong(iDate))
                        put("mime",     cur.getString(iMime) ?: "video/mp4")
                        put("type",     "video")
                        put("duration", cur.getLong(iDur))
                        put("thumb",    thumb)
                    })
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "vid skip: ${e.message}")
                }
            } while (cur.moveToNext())

            Log.d(TAG, "Videos paginated: collected $count from offset $offset (cursor total=${cur.count})")
        }
    }
}
