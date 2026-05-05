package com.example.fosterconnect.foster

import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.databinding.ItemKittenBinding
import com.example.fosterconnect.medication.FosterTreatmentSchedule

class KittenAdapter(
    private val fosterCases: List<FosterCaseAnimal>,
    private val onClick: (FosterCaseAnimal) -> Unit,
    private val onNameUpdate: ((FosterCaseAnimal, String) -> Unit)? = null,
    private val onCollarColorUpdate: ((FosterCaseAnimal, CollarColor?) -> Unit)? = null
) : RecyclerView.Adapter<KittenAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemKittenBinding) : RecyclerView.ViewHolder(binding.root) {
        var fillAnimator: ValueAnimator? = null
        var isEditing = false
    }

    private val longPressDurationMs = 600L

    private fun resetEditIcon(holder: ViewHolder) {
        val ctx = holder.itemView.context
        holder.binding.iconEdit.alpha = 0.4f
        holder.binding.iconEdit.drawable?.colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(ctx, R.color.clinical_line_dark),
                PorterDuff.Mode.SRC_IN
            )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKittenBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fosterCase = fosterCases[position]
        val ctx = holder.itemView.context

        holder.isEditing = false
        holder.binding.textName.visibility = View.VISIBLE
        holder.binding.editName.visibility = View.GONE

        holder.binding.imageProfile.setImageResource(fosterCase.color.defaultProfileDrawable(fosterCase.name))
        holder.binding.textName.text = fosterCase.name
        holder.binding.textExternalId.text = if (fosterCase.externalId.isNotEmpty()) "#${fosterCase.externalId}" else ""
        holder.binding.textExternalId.visibility = if (fosterCase.externalId.isNotEmpty()) View.VISIBLE else View.GONE

        val sexLabel = fosterCase.sex.display
        holder.binding.textBreedColor.text = "${fosterCase.color.display} · $sexLabel"

        val latest = fosterCase.weightEntries.lastOrNull()
        holder.binding.textWeight.text = if (latest != null) {
            "${"%.0f".format(latest.weightGrams)}g"
        } else {
            "--"
        }

        val ageWeeks = fosterCase.ageInWeeks
        holder.binding.textAge.text = if (ageWeeks != null) "${ageWeeks}w" else "--"

        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.nextVaccineDateMillis,
            latest?.weightGrams,
            latest?.dateMillis,
            fosterCase.administeredTreatments
        )
        val overdueCount = schedule.count { it.isPast && !it.isAdministered }
        val hasOverdue = overdueCount > 0

        if (fosterCase.collarColor != null) {
            if (fosterCase.collarColor == CollarColor.WHITE) {
                val dp = ctx.resources.displayMetrics.density
                holder.binding.viewStatusBar.background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(ctx, R.color.collar_white))
                    setStroke((1 * dp).toInt(), ContextCompat.getColor(ctx, R.color.clinical_ink_muted))
                }
            } else {
                holder.binding.viewStatusBar.background = null
                holder.binding.viewStatusBar.setBackgroundColor(
                    ContextCompat.getColor(ctx, fosterCase.collarColor.colorResId)
                )
            }
        } else {
            holder.binding.viewStatusBar.background = null
            holder.binding.viewStatusBar.setBackgroundColor(
                ContextCompat.getColor(ctx, if (hasOverdue) R.color.clinical_crimson else R.color.clinical_sage)
            )
        }

        holder.binding.layoutStatusChips.removeAllViews()
        if (hasOverdue) {
            addChip(holder, ctx.getString(R.string.overdue_count_format, overdueCount),
                R.color.clinical_crimson, R.color.clinical_crimson_soft)
        }
        if (latest != null && ageWeeks != null) {
            val expectedMinGrams = ExpectedWeight.minAt(ageWeeks)
            if (expectedMinGrams != null && latest.weightGrams < expectedMinGrams) {
                addChip(holder, ctx.getString(R.string.weight_low),
                    R.color.clinical_amber, R.color.clinical_amber_soft)
            }
        }

        if (onNameUpdate != null || onCollarColorUpdate != null) {
            setupLongPressEdit(holder, fosterCase)
        }

        holder.itemView.setOnClickListener {
            if (!holder.isEditing) onClick(fosterCase)
        }
    }

    private fun setupLongPressEdit(holder: ViewHolder, fosterCase: FosterCaseAnimal) {
        val ctx = holder.itemView.context
        val startColor = ContextCompat.getColor(ctx, R.color.clinical_line_dark)
        val sageColor = ContextCompat.getColor(ctx, R.color.clinical_sage)

        holder.itemView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (holder.isEditing) return@setOnTouchListener false
                    holder.binding.iconEdit.alpha = 0.4f
                    holder.binding.iconEdit.drawable?.colorFilter =
                        PorterDuffColorFilter(startColor, PorterDuff.Mode.SRC_IN)

                    holder.fillAnimator?.cancel()
                    holder.fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = longPressDurationMs
                        addUpdateListener { anim ->
                            val fraction = anim.animatedFraction
                            val r = lerp(red(startColor), red(sageColor), fraction)
                            val g = lerp(green(startColor), green(sageColor), fraction)
                            val b = lerp(blue(startColor), blue(sageColor), fraction)
                            val blendedColor = android.graphics.Color.rgb(r, g, b)
                            holder.binding.iconEdit.drawable?.colorFilter =
                                PorterDuffColorFilter(blendedColor, PorterDuff.Mode.SRC_IN)
                            holder.binding.iconEdit.alpha = 0.4f + 0.6f * fraction
                        }
                    }
                    holder.fillAnimator?.start()

                    v.postDelayed({
                        if (holder.fillAnimator?.isRunning == false) return@postDelayed
                        holder.fillAnimator?.cancel()
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showLongPressOptions(holder, fosterCase)
                    }, longPressDurationMs)

                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holder.fillAnimator?.cancel()
                    if (!holder.isEditing) {
                        resetEditIcon(holder)
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun showLongPressOptions(holder: ViewHolder, fosterCase: FosterCaseAnimal) {
        val ctx = holder.itemView.context
        val hasName = onNameUpdate != null
        val hasCollar = onCollarColorUpdate != null

        if (hasName && hasCollar) {
            val options = arrayOf("Edit Name", "Set Collar Color")
            AlertDialog.Builder(ctx)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> enterEditMode(holder, fosterCase)
                        1 -> showCollarColorPicker(holder, fosterCase)
                    }
                }
                .setOnDismissListener {
                    if (!holder.isEditing) {
                        resetEditIcon(holder)
                    }
                }
                .show()
        } else if (hasName) {
            enterEditMode(holder, fosterCase)
        } else if (hasCollar) {
            showCollarColorPicker(holder, fosterCase)
        }
    }

    private fun showCollarColorPicker(holder: ViewHolder, fosterCase: FosterCaseAnimal) {
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density
        val swatchSize = (40 * dp).toInt()
        val spacing = (8 * dp).toInt()

        val grid = GridLayout(ctx).apply {
            columnCount = 4
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Collar Color")
            .setView(grid)
            .setNeutralButton("Clear") { _, _ ->
                onCollarColorUpdate?.invoke(fosterCase, null)
                resetEditIcon(holder)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                if (!holder.isEditing) resetEditIcon(holder)
            }
            .create()

        for ((index, collar) in CollarColor.entries.withIndex()) {
            val swatch = View(ctx).apply {
                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(ctx, collar.colorResId))
                    if (collar == CollarColor.WHITE) {
                        setStroke((2 * dp).toInt(), ContextCompat.getColor(ctx, R.color.clinical_line_dark))
                    }
                }
                background = circle
                contentDescription = collar.display
                setOnClickListener {
                    onCollarColorUpdate?.invoke(fosterCase, collar)
                    dialog.dismiss()
                }
            }
            val row = index / 4
            val col = index % 4
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f)
            ).apply {
                width = swatchSize
                height = swatchSize
                setMargins(spacing, spacing, spacing, spacing)
                setGravity(Gravity.CENTER)
            }
            grid.addView(swatch, params)
        }

        dialog.show()
    }

    private fun enterEditMode(holder: ViewHolder, fosterCase: FosterCaseAnimal) {
        holder.isEditing = true
        val ctx = holder.itemView.context

        holder.binding.textName.visibility = View.GONE
        holder.binding.editName.visibility = View.VISIBLE
        holder.binding.editName.filters = arrayOf(android.text.InputFilter.AllCaps())
        holder.binding.editName.setText(fosterCase.name)
        holder.binding.editName.requestFocus()
        holder.binding.editName.setSelection(holder.binding.editName.text?.length ?: 0)

        holder.binding.iconEdit.drawable?.colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(ctx, R.color.clinical_sage),
                PorterDuff.Mode.SRC_IN
            )
        holder.binding.iconEdit.alpha = 1f

        val imm = ctx.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(holder.binding.editName, InputMethodManager.SHOW_IMPLICIT)

        holder.binding.editName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitEdit(holder, fosterCase)
                true
            } else false
        }

        holder.binding.editName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitEdit(holder, fosterCase)
        }
    }

    private fun commitEdit(holder: ViewHolder, fosterCase: FosterCaseAnimal) {
        if (!holder.isEditing) return
        holder.isEditing = false

        val newName = holder.binding.editName.text?.toString()?.trim().orEmpty()
        holder.binding.editName.visibility = View.GONE
        holder.binding.textName.visibility = View.VISIBLE
        resetEditIcon(holder)

        val imm = holder.itemView.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(holder.binding.editName.windowToken, 0)

        if (newName.isNotEmpty() && newName != fosterCase.name) {
            holder.binding.textName.text = newName
            onNameUpdate?.invoke(fosterCase, newName)
        }
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

    override fun getItemCount() = fosterCases.size

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
}
