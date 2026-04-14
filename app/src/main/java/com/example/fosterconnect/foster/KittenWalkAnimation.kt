package com.example.fosterconnect.foster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.example.fosterconnect.R

object KittenWalkAnimation {

    private const val FRAME_COUNT = 6
    private const val FRAME_DURATION_MS = 250

    private var cachedFrames: List<Bitmap>? = null

    fun applyTo(imageView: ImageView) {
        val frames = loadFrames(imageView.context)
        val animation = AnimationDrawable().apply {
            isOneShot = false
            val resources = imageView.resources
            frames.forEach { frame ->
                addFrame(BitmapDrawable(resources, frame), FRAME_DURATION_MS)
            }
        }
        imageView.setImageDrawable(animation)
        imageView.post { animation.start() }
    }

    private fun loadFrames(context: Context): List<Bitmap> {
        cachedFrames?.let { return it }
        val options = BitmapFactory.Options().apply { inScaled = false }
        val sheet = BitmapFactory.decodeResource(context.resources, R.drawable.kitten_walk, options)
        val frameWidth = sheet.width / FRAME_COUNT
        val frameHeight = sheet.height
        val frames = (0 until FRAME_COUNT).map { i ->
            Bitmap.createBitmap(sheet, i * frameWidth, 0, frameWidth, frameHeight)
        }
        cachedFrames = frames
        return frames
    }
}
