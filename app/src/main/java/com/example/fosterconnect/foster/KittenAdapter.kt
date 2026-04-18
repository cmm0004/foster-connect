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

        // Breed · Sex · Altered status
        val alteredStatus = if (fosterCase.isAlteredAtIntake) "Altered" else "Intact"
        holder.binding.textBreedColor.text = "${fosterCase.color.display} · ${fosterCase.sex.display} · $alteredStatus"

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
        val currentWeightGrams = latest?.weightGrams
        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.intakeDateMillis,
            fosterCase.estimatedBirthdayMillis,
            currentWeightGrams,
            fosterCase.administeredTreatments
        )
        val overdueCount = schedule.count { it.isPast && !it.isAdministered }
        val hasOverdue = overdueCount > 0

        if (hasOverdue) {
            holder.binding.textNextTreatment.text = ctx.getString(R.string.today)
            holder.binding.textNextTreatment.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_crimson))
        } else {
            val nextDose = schedule.firstOrNull { !it.isAdministered && !it.isPast }
            if (nextDose != null) {
                val daysUntil = ((nextDose.scheduledDateMillis - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                holder.binding.textNextTreatment.text = "${daysUntil}d"
            } else {
                holder.binding.textNextTreatment.text = "--"
            }
            holder.binding.textNextTreatment.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_soft))
        }

        // Status bar color
        holder.binding.viewStatusBar.setBackgroundColor(
            ContextCompat.getColor(ctx, if (hasOverdue) R.color.clinical_crimson else R.color.clinical_sage)
        )

        // Status chips
        holder.binding.layoutStatusChips.removeAllViews()
        if (hasOverdue) {
            addChip(holder, ctx.getString(R.string.overdue_count_format, overdueCount),
                R.color.clinical_crimson, R.color.clinical_crimson_soft)
            // Check if weight is below expected
            if (latest != null && ageWeeks != null) {
                val expectedMinGrams = ageWeeks * 100f // rough estimate
                if (latest.weightGrams < expectedMinGrams) {
                    addChip(holder, ctx.getString(R.string.weight_low),
                        R.color.clinical_amber, R.color.clinical_amber_soft)
                }
            }
        } else {
            addChip(holder, ctx.getString(R.string.on_schedule),
                R.color.clinical_sage, R.color.clinical_sage_tint)
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
