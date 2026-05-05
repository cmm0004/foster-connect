package com.example.fosterconnect.foster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentGalleryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var fosterCaseId: String
    private var photos: List<FosterPhoto> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fosterCaseId = requireArguments().getString("fosterCaseId")
            ?: error("Missing fosterCaseId argument")

        binding.recyclerGallery.layoutManager = GridLayoutManager(requireContext(), 3)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.fosterCasesFlow.collect { cases ->
                    val fosterCase = cases.find { it.fosterCaseId == fosterCaseId }
                    photos = fosterCase?.photos.orEmpty()
                    binding.textEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerGallery.adapter = GalleryAdapter(
                        photos,
                        onLongClick = { photo -> confirmDelete(photo) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            KittenRepository.prunePhotos(fosterCaseId, requireContext().contentResolver)
        }
    }

    private fun confirmDelete(photo: FosterPhoto) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_photo_title)
            .setMessage(R.string.delete_photo_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    KittenRepository.deletePhoto(photo.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class GalleryAdapter(
        private val photos: List<FosterPhoto>,
        private val onLongClick: (FosterPhoto) -> Unit
    ) : RecyclerView.Adapter<GalleryAdapter.VH>() {

        class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.image_photo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_photo, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            val ctx = holder.view.context
            val uri = Uri.parse(photo.uri)
            val bitmap = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                val decoded = decodeSampled(bytes, 512) ?: return@runCatching null
                val rotation = readExifRotation(ctx, uri)
                if (rotation != 0) rotateBitmap(decoded, rotation) else decoded
            }.getOrNull()
            if (bitmap != null) {
                holder.image.setImageBitmap(bitmap)
            } else {
                holder.image.setImageDrawable(null)
                Log.w("GalleryFragment", "Failed to load ${photo.uri}; pruning")
                kotlinx.coroutines.MainScope().launch { KittenRepository.deletePhoto(photo.id) }
            }
            holder.view.setOnLongClickListener {
                onLongClick(photo)
                true
            }
        }

        override fun getItemCount() = photos.size

        private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        private fun readExifRotation(ctx: android.content.Context, uri: Uri): Int {
            return runCatching {
                val input = ctx.contentResolver.openInputStream(uri) ?: return 0
                val exif = ExifInterface(input)
                input.close()
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
        }

        private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
