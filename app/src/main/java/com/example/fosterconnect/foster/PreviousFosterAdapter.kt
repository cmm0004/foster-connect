package com.example.fosterconnect.foster

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemPreviousFosterBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PreviousFosterAdapter(
    private val fosters: List<CompletedFoster>,
    private val onClick: (CompletedFoster) -> Unit
) : RecyclerView.Adapter<PreviousFosterAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPreviousFosterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPreviousFosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val foster = fosters[position]
        val ctx = holder.itemView.context

        holder.binding.imageProfile.setImageResource(foster.color.defaultProfileDrawable())
        holder.binding.textName.text = foster.name
        holder.binding.textExternalId.text = if (foster.externalId.isNotEmpty()) "#${foster.externalId}" else ""
        holder.binding.textExternalId.visibility =
            if (foster.externalId.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val sexLabel = foster.sex.display
        holder.binding.textBreedColor.text = "${foster.color.display} · $sexLabel"

        holder.binding.textWeight.text = if (foster.finalWeightGrams != null) {
            "${"%.0f".format(foster.finalWeightGrams)}g"
        } else {
            "--"
        }

        val ageWeeks = foster.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) "${ageWeeks}w" else "--"

        holder.binding.textDays.text = "${foster.daysFostered}d"

        holder.binding.layoutStatusChips.removeAllViews()
        addChip(
            holder,
            "Adopted ${dateFormat.format(Date(foster.outDateMillis))}",
            R.color.clinical_ink_muted,
            R.color.clinical_sage_tint
        )

        holder.itemView.setOnClickListener { onClick(foster) }
    }

    private fun addChip(holder: ViewHolder, text: String, textColorRes: Int, bgColorRes: Int) {
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density
        val chip = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, textColorRes))
            letterSpacing = 0.05f
            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, bgColorRes))
                cornerRadius = 3f * dp
            }
            background = bg
            setPadding((7 * dp).toInt(), (3 * dp).toInt(), (7 * dp).toInt(), (3 * dp).toInt())
        }
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (4 * dp).toInt() }
        holder.binding.layoutStatusChips.addView(chip, params)
    }

    override fun getItemCount() = fosters.size

    companion object {
        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    }
}
