package com.example.fosterconnect.foster

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemKittenBinding

class KittenAdapter(
    private val fosterCases: List<FosterCaseAnimal>,
    private val onClick: (FosterCaseAnimal) -> Unit
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
        holder.binding.textExternalId.text = fosterCase.externalId
        holder.binding.textExternalId.visibility = if (fosterCase.externalId.isNotEmpty()) View.VISIBLE else View.GONE
        holder.binding.textBreedColor.text = "${fosterCase.breed.display} · ${fosterCase.color.display}"

        val latest = fosterCase.weightEntries.lastOrNull()
        holder.binding.textWeight.text = if (latest != null) {
            "Latest weight: ${"%.0f".format(latest.weightGrams)} g"
        } else {
            "No weight logged yet"
        }

        val ageWeeks = fosterCase.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) {
            "$ageWeeks week${if (ageWeeks != 1) "s" else ""} old"
        } else {
            holder.itemView.context.getString(R.string.birthday_not_set)
        }

        holder.itemView.setOnClickListener { onClick(fosterCase) }
    }

    override fun getItemCount() = fosterCases.size
}
