package com.example.fosterconnect.foster

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemKittenBinding

class KittenAdapter(
    private val kittens: List<Kitten>,
    private val onClick: (Kitten) -> Unit
) : RecyclerView.Adapter<KittenAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemKittenBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKittenBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val kitten = kittens[position]
        holder.binding.textName.text = kitten.name
        holder.binding.textExternalId.text = kitten.externalId
        holder.binding.textExternalId.visibility = if (kitten.externalId.isNotEmpty()) View.VISIBLE else View.GONE
        holder.binding.textBreedColor.text = "${kitten.breed.display} · ${kitten.color.display}"

        val latest = kitten.weightEntries.lastOrNull()
        holder.binding.textWeight.text = if (latest != null) {
            "Latest weight: ${"%.0f".format(latest.weightGrams)} g"
        } else {
            "No weight logged yet"
        }

        val ageWeeks = kitten.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) {
            "$ageWeeks week${if (ageWeeks != 1) "s" else ""} old"
        } else {
            holder.itemView.context.getString(R.string.birthday_not_set)
        }

        holder.itemView.setOnClickListener { onClick(kitten) }
    }

    override fun getItemCount() = kittens.size
}
