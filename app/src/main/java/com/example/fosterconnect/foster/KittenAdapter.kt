package com.example.fosterconnect.foster

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemKittenBinding
import com.example.fosterconnect.medication.FosterTreatmentSchedule

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
        val ctx = holder.itemView.context

        holder.binding.imageProfile.setImageResource(fosterCase.color.defaultProfileDrawable())
        holder.binding.textName.text = fosterCase.name
        holder.binding.textExternalId.text = if (fosterCase.externalId.isNotEmpty()) "#${fosterCase.externalId}" else ""
        holder.binding.textExternalId.visibility = if (fosterCase.externalId.isNotEmpty()) View.VISIBLE else View.GONE

        val sexLabel = fosterCase.sex.display
        holder.binding.textBreedColor.text = "${fosterCase.color.display} · $sexLabel"

        // Weight stat
        val latest = fosterCase.weightEntries.lastOrNull()
        holder.binding.textWeight.text = if (latest != null) {
            "${"%.0f".format(latest.weightGrams)}g"
        } else {
            "--"
        }

        // Age stat
        val ageWeeks = fosterCase.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) "${ageWeeks}w" else "--"

        // Next treatment stat
        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.nextVaccineDateMillis,
            latest?.weightGrams,
            latest?.dateMillis,
            fosterCase.administeredTreatments
        )
        val overdueCount = schedule.count { it.isPast && !it.isAdministered }
        val hasOverdue = overdueCount > 0

        // Status bar color
        holder.binding.viewStatusBar.setBackgroundColor(
            ContextCompat.getColor(ctx, if (hasOverdue) R.color.clinical_crimson else R.color.clinical_sage)
        )

        // Status chips
        holder.binding.layoutStatusChips.removeAllViews()
        if (hasOverdue) {
            addChip(holder, ctx.getString(R.string.overdue_count_format, overdueCount),
                R.color.clinical_crimson, R.color.clinical_crimson_soft)
        }
        // Check if weight is below expected
        if (latest != null && ageWeeks != null) {
            val expectedMinGrams = ExpectedWeight.minAt(ageWeeks)
            if (expectedMinGrams != null && latest.weightGrams < expectedMinGrams) {
                addChip(holder, ctx.getString(R.string.weight_low),
                    R.color.clinical_amber, R.color.clinical_amber_soft)
            }
        }

        holder.itemView.setOnClickListener { onClick(fosterCase) }
    }

    private fun addChip(holder: ViewHolder, text: String, textColorRes: Int, bgColorRes: Int) {
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density
        val chip = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
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

    override fun getItemCount() = fosterCases.size
}
