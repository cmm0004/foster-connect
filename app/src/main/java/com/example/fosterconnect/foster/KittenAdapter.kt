package com.example.fosterconnect.foster

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemKittenBinding

class KittenAdapter(
    private val fosterCases: List<FosterCaseAnimal>,
    private val onClick: (FosterCaseAnimal) -> Unit,
    private val onAddPhoto: (FosterCaseAnimal) -> Unit,
    private val onViewGallery: (FosterCaseAnimal) -> Unit
) : RecyclerView.Adapter<KittenAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemKittenBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKittenBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fosterCase = fosterCases[position]
            // KittenWalkAnimation.applyTo(holder.binding.imageProfile)
        holder.binding.imageProfile.setImageResource(fosterCase.color.defaultProfileDrawable())
        holder.binding.textName.text = fosterCase.name
        val litter = fosterCase.litterName
        holder.binding.textLitterName.text = litter
        holder.binding.textLitterName.visibility = if (!litter.isNullOrBlank()) View.VISIBLE else View.GONE
        holder.binding.textExternalId.text = fosterCase.externalId
        holder.binding.textExternalId.visibility = if (fosterCase.externalId.isNotEmpty()) View.VISIBLE else View.GONE
        holder.binding.textBreedColor.text = "${fosterCase.breed.display} · ${fosterCase.color.display}"

        val latest = fosterCase.weightEntries.lastOrNull()
        holder.binding.textWeight.text = if (latest != null) {
            val g = latest.weightGrams
            val lb = g / 453.592f
            "${"%.0f".format(g)} g (${"%.2f".format(lb)} lb)"
        } else {
            "No weight logged yet"
        }

        val ageWeeks = fosterCase.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) {
            formatAge(ageWeeks)
        } else {
            holder.itemView.context.getString(R.string.birthday_not_set)
        }

        holder.binding.textPhotoCount.text = holder.itemView.context.getString(
            R.string.photo_count_format,
            fosterCase.photos.size,
            com.example.fosterconnect.data.KittenRepository.MAX_PHOTOS_PER_CASE
        )
        holder.binding.buttonAddPhoto.setOnClickListener { onAddPhoto(fosterCase) }
        holder.binding.buttonViewGallery.setOnClickListener { onViewGallery(fosterCase) }

        holder.itemView.setOnClickListener { onClick(fosterCase) }
    }

    override fun getItemCount() = fosterCases.size

    private fun formatAge(weeks: Int): String = when {
        weeks < 12 -> "$weeks week${if (weeks != 1) "s" else ""} old"
        weeks < 52 -> {
            val months = weeks / 4
            "$months month${if (months != 1) "s" else ""} old"
        }
        else -> {
            val years = weeks / 52
            "$years year${if (years != 1) "s" else ""} old"
        }
    }
}
