package com.th4rv4.zukinav.papers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/** Copies a picked photo or PDF into the app as page images. */
object PaperFiles {

    /** Long side of a saved page, enough to read small print when zoomed. */
    private const val MAX_SIDE = 2200

    /**
     * Saves up to [room] pages from [uri] for paper [id]. A PDF gives one page
     * image per page. Returns the new file names; empty if nothing could be read.
     */
    fun add(ctx: Context, uri: Uri, id: Long, room: Int): List<String> {
        if (room <= 0) return emptyList()
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val bitmaps: List<Bitmap> = try {
            if (type == "application/pdf") fromPdf(ctx, uri, room) else listOfNotNull(fromImage(ctx, uri))
        } catch (_: Exception) {
            emptyList()
        }
        return bitmaps.mapNotNull { b ->
            val name = "$id-${System.nanoTime()}.jpg"
            try {
                FileOutputStream(File(PaperStore.dir(ctx), name)).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                name
            } catch (_: Exception) {
                null
            } finally {
                b.recycle()
            }
        }
    }

    /** Loads a saved page no bigger than [maxSide] pixels. */
    fun load(ctx: Context, name: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val f = File(PaperStore.dir(ctx), name)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, maxSide) })
    }

    fun delete(ctx: Context, name: String) { File(PaperStore.dir(ctx), name).delete() }

    private fun fromImage(ctx: Context, uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, MAX_SIDE) })
        } ?: return null
        return rotate(ctx, uri, raw)
    }

    /** Photos from a camera are often stored sideways with a rotation tag. */
    private fun rotate(ctx: Context, uri: Uri, b: Bitmap): Bitmap {
        val degrees: Float = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (degrees == 0f) return b
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r != b) b.recycle()
        return r
    }

    private fun fromPdf(ctx: Context, uri: Uri, room: Int): List<Bitmap> {
        val fd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return fd.use {
            PdfRenderer(it).use { pdf ->
                (0 until minOf(pdf.pageCount, room)).map { i ->
                    pdf.openPage(i).use { page ->
                        val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                        val b = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
        }
    }

    private fun sample(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }
}
