package com.example.fosterconnect.foster

import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentKittenDetailBinding
import com.example.fosterconnect.history.EventEntry
import com.example.fosterconnect.history.EventType
import com.example.fosterconnect.history.StoolEntry
import com.example.fosterconnect.history.WeightAlertManager
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.history.WeightTrend
import com.example.fosterconnect.medication.FosterTreatmentSchedule
import com.example.fosterconnect.medication.Medication
import com.example.fosterconnect.medication.ScheduledDose
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class KittenDetailFragment : Fragment() {

    private var _binding: FragmentKittenDetailBinding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    private lateinit var fosterCaseId: String

    private fun formatWeight(grams: Float): String {
        val lb = grams / 453.592f
        return "%.0f g (%.2f lb)".format(grams, lb)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKittenDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fosterCaseId = requireArguments().getString("fosterCaseId")!!
        refreshUI()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.fosterCasesFlow.collect {
                    if (_binding != null) refreshUI()
                }
            }
        }

        binding.buttonAddWeight.setOnClickListener {
            showAddWeightDialog()
        }

        binding.buttonLogStool.setOnClickListener {
            showLogStoolDialog()
        }

        binding.buttonLogEvent.setOnClickListener {
            showLogEventDialog()
        }

        binding.buttonAddMedication.setOnClickListener {
            showAddMedicationDialog()
        }

        binding.buttonMarkAdopted.setOnConfirmedListener {
            KittenRepository.markCaseCompleted(fosterCaseId)
            findNavController().popBackStack()
        }

        binding.buttonReopenCase.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reopen_foster_case)
                .setMessage(getString(R.string.reopen_foster_message))
                .setPositiveButton("Reopen") { _, _ ->
                    KittenRepository.reopenCase(fosterCaseId)
                    refreshUI()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.buttonExport.setOnClickListener { exportFosterHistory() }
    }


    private fun refreshUI() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        val ctx = requireContext()

        if (fosterCase.isCompleted) {
            binding.buttonAddWeight.visibility = View.GONE
            binding.buttonLogStool.visibility = View.GONE
            binding.buttonLogEvent.visibility = View.GONE
            binding.buttonAddMedication.visibility = View.GONE
            binding.buttonScheduleTreatment.visibility = View.GONE
            binding.buttonMarkAdopted.visibility = View.GONE
            binding.buttonReopenCase.visibility = View.VISIBLE
            binding.textTreatmentTitle.setText(R.string.treatment_history_label)
            binding.textMedicationsTitle.setText(R.string.medication_history_label)
        } else {
            binding.buttonAddWeight.visibility = View.VISIBLE
            binding.buttonLogStool.visibility = View.VISIBLE
            binding.buttonLogEvent.visibility = View.VISIBLE
            binding.buttonAddMedication.visibility = View.VISIBLE
            binding.buttonMarkAdopted.visibility = View.VISIBLE
            binding.buttonReopenCase.visibility = View.GONE
            binding.textTreatmentTitle.setText(R.string.treatment_ledger_label)
            binding.textMedicationsTitle.setText(R.string.medications_label_upper)
        }

        // Patient header
        binding.imageProfile.setImageResource(fosterCase.color.defaultProfileDrawable(fosterCase.name))
        binding.textKittenName.text = fosterCase.name

        val nickPart = if (fosterCase.litterName != null) "\"${fosterCase.litterName}\" · " else ""
        val idPart = if (fosterCase.externalId.isNotBlank()) fosterCase.externalId else ""
        binding.textKittenBreed.text = "${nickPart}${idPart}".trimEnd(' ', '·').ifBlank { null }
        binding.textKittenBreed.visibility = if (binding.textKittenBreed.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        // Info chips
        binding.layoutInfoChips.removeAllViews()
        if (fosterCase.isCompleted) {
            addInfoChip("ARCHIVED", R.color.clinical_ink_muted, R.color.clinical_line)
        }
        val sexLabel = fosterCase.sex.display.uppercase()
        addInfoChip(sexLabel)

        // Vitals KPIs
        val latest = fosterCase.weightEntries.lastOrNull()
        binding.textCurrentWeight.text = if (latest != null) {
            "${"%.0f".format(latest.weightGrams)}g"
        } else {
            "--"
        }
        binding.textWeightTrend.text = if (latest != null) {
            val ageWeeks = fosterCase.ageInWeeks ?: 0
            val expected = ExpectedWeight.avgAt(ageWeeks.toFloat()) ?: (ageWeeks * 100f)
            val diff = latest.weightGrams - expected
            if (diff < 0) "${"%.0f".format(diff)}g exp" else "+${"%.0f".format(diff)}g exp"
        } else {
            ""
        }

        // Stool KPI – 3-day recency-weighted average
        val threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val recentStools = fosterCase.stoolEntries.filter { it.dateMillis >= threeDaysAgo }
        if (recentStools.isNotEmpty()) {
            val avg = recentStools.map { it.level }.average()
            binding.textStoolAvg.text = "%.1f/7".format(avg)
            val (statusText, statusColor, valueColor) = when {
                avg >= 7.0 -> Triple(
                    R.string.stool_diarrhea,
                    R.color.clinical_crimson,
                    R.color.clinical_crimson
                )

                avg >= 5.0 -> Triple(
                    R.string.stool_loose,
                    R.color.clinical_amber,
                    R.color.clinical_amber
                )

                else -> Triple(R.string.in_range, R.color.clinical_sage, R.color.clinical_sage)
            }
            binding.textStoolStatus.setText(statusText)
            binding.textStoolStatus.setTextColor(ctx.getColor(statusColor))
            binding.textStoolAvg.setTextColor(ctx.getColor(valueColor))
        } else {
            binding.textStoolAvg.text = "--"
            binding.textStoolStatus.setText(R.string.in_range)
            binding.textStoolStatus.setTextColor(ctx.getColor(R.color.clinical_sage))
            binding.textStoolAvg.setTextColor(ctx.getColor(R.color.clinical_sage))
        }
        val mostRecentDataMillis = maxOf(
            fosterCase.weightEntries.maxOfOrNull { it.dateMillis } ?: 0L,
            fosterCase.stoolEntries.maxOfOrNull { it.dateMillis } ?: 0L,
            fosterCase.eventEntries.maxOfOrNull { it.dateMillis } ?: 0L
        )
        val recentEvents = fosterCase.eventEntries.filter {
            it.dateMillis >= mostRecentDataMillis - 14L * 24 * 60 * 60 * 1000
        }
        binding.textEventsCount.text = "${recentEvents.size}"

        // Status badge
        val currentWeightGrams = latest?.weightGrams

        // Vitals chart
        binding.vitalsChart.setBirthdayMillis(fosterCase.estimatedBirthdayMillis)
        binding.vitalsChart.setWeightEntries(fosterCase.weightEntries)
        binding.vitalsChart.setStoolEntries(fosterCase.stoolEntries)
        binding.vitalsChart.setEventEntries(fosterCase.eventEntries)

        binding.layoutMedications.removeAllViews()
        if (fosterCase.isCompleted) {
            // Archive: show all medications as read-only history
            val allMeds = fosterCase.medications.sortedByDescending { it.startDateMillis }
            if (allMeds.isNotEmpty()) {
                allMeds.forEachIndexed { index, med ->
                    if (index > 0) binding.layoutMedications.addView(buildDivider())
                    binding.layoutMedications.addView(buildMedicationHistoryRow(med))
                }
            } else {
                binding.layoutMedications.addView(buildEmptyLabel(getString(R.string.no_medication_history)))
            }

            // Archive: show administered treatments inline
            renderTreatmentHistory(fosterCase)
        } else {
            // Active: only show active medications
            val activeMeds = fosterCase.medications.filter { it.isActive }
                .sortedByDescending { it.startDateMillis }
            if (activeMeds.isNotEmpty()) {
                activeMeds.forEachIndexed { index, med ->
                    if (index > 0) binding.layoutMedications.addView(buildDivider())
                    binding.layoutMedications.addView(buildMedicationCard(med))
                }
            }

            renderTreatmentSchedule(fosterCase, currentWeightGrams)
        }
    }

    private fun addInfoChip(
        text: String,
        textColorRes: Int = R.color.clinical_ink_soft,
        bgColorRes: Int = R.color.clinical_sage_tint
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
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
        binding.layoutInfoChips.addView(chip, params)
    }

    private fun renderTreatmentSchedule(fosterCase: FosterCaseAnimal, currentWeightGrams: Float?) {
        binding.layoutTreatmentSchedule.removeAllViews()
        val latestWeightEntry = fosterCase.weightEntries.lastOrNull()

        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.nextVaccineDateMillis,
            currentWeightGrams,
            latestWeightEntry?.dateMillis,
            fosterCase.administeredTreatments
        )

        binding.buttonScheduleTreatment.visibility = View.VISIBLE

        if (schedule.isEmpty()) {
            binding.layoutTableHeader.visibility = View.GONE
            binding.dividerTableTop.visibility = View.GONE
            binding.dividerTableBottom.visibility = View.GONE
            binding.buttonScheduleTreatment.text = getString(R.string.schedule)
            binding.buttonScheduleTreatment.setOnClickListener {
                showScheduleTreatmentDialog(fosterCase)
            }
            return
        }

        binding.layoutTableHeader.visibility = View.VISIBLE
        binding.dividerTableTop.visibility = View.VISIBLE
        binding.dividerTableBottom.visibility = View.VISIBLE

        binding.buttonScheduleTreatment.text = getString(R.string.complete_dose)
        binding.buttonScheduleTreatment.setOnClickListener {
            val allGiven = FosterTreatmentSchedule.isCurrentDoseComplete(schedule)
            if (!allGiven) {
                Toast.makeText(requireContext(), R.string.not_all_given_message, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.complete_dose_title)
                .setMessage(R.string.complete_dose_message)
                .setPositiveButton(R.string.complete_dose) { _, _ ->
                    KittenRepository.completeTreatmentDose(fosterCaseId)
                    refreshUI()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        schedule.forEachIndexed { index, dose ->
            binding.layoutTreatmentSchedule.addView(
                buildTreatmentRow(dose, index < schedule.lastIndex)
            )
        }
    }

    private fun renderTreatmentHistory(fosterCase: FosterCaseAnimal) {
        binding.layoutTreatmentSchedule.removeAllViews()
        binding.layoutTableHeader.visibility = View.GONE
        binding.dividerTableTop.visibility = View.GONE
        binding.dividerTableBottom.visibility = View.GONE
        binding.buttonScheduleTreatment.visibility = View.GONE

        val completedDoses = fosterCase.administeredTreatments
            .filter { it.administeredDateMillis != null }
            .groupBy { it.scheduledDateMillis }
            .toSortedMap()

        if (completedDoses.isEmpty()) {
            binding.layoutTreatmentSchedule.addView(
                buildEmptyLabel(getString(R.string.no_treatment_history))
            )
            return
        }

        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        var doseNum = 0
        for ((doseDate, treatments) in completedDoses) {
            doseNum++
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 8f * dp
                cardElevation = 0f
                strokeColor = ContextCompat.getColor(ctx, R.color.clinical_line)
                strokeWidth = (1 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val padPx = (12 * dp).toInt()
                setPadding(padPx, padPx, padPx, padPx)
            }
            val titleText = TextView(ctx).apply {
                text = "\u2713 ${
                    getString(
                        R.string.dose_header_format,
                        doseNum,
                        dateFormat.format(Date(doseDate))
                    )
                }"
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink))
                textSize = 12f
            }
            content.addView(titleText)
            for (t in treatments) {
                val displayName = com.example.fosterconnect.medication.StandardTreatment.entries
                    .firstOrNull { it.name == t.treatmentType }?.displayName ?: t.treatmentType
                val label = if (t.doseGiven != null) "$displayName -${t.doseGiven}" else displayName
                val row = TextView(ctx).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_soft))
                    textSize = 11f
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                }
                content.addView(row)
            }
            card.addView(content)
            binding.layoutTreatmentSchedule.addView(card)
        }
    }

    private fun buildMedicationHistoryRow(med: com.example.fosterconnect.medication.Medication): View {
        return buildMedicationCard(med)
    }

    private fun buildDivider(): View {
        val dp = resources.displayMetrics.density
        val margin = (14 * dp).toInt()
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                marginStart = margin
                marginEnd = margin
            }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.clinical_line_dark))
        }
    }

    private fun buildEmptyLabel(message: String): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_muted))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        }
    }

    private fun showScheduleTreatmentDialog(fosterCase: FosterCaseAnimal) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val oneWeekMs = 7L * 24 * 60 * 60 * 1000
        val defaultDate = System.currentTimeMillis() + oneWeekMs

        var selectedDateMillis = defaultDate
        var ponazurilChecked = true

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        val dateBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(selectedDateMillis))
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                val c = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
                android.app.DatePickerDialog(ctx, { _, y, m, d ->
                    val picked = Calendar.getInstance()
                        .apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                    selectedDateMillis = picked.timeInMillis
                    this.text =
                        SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(selectedDateMillis))
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        container.addView(dateBtn)

        val ponazurilCheck = android.widget.CheckBox(ctx).apply {
            text = "Include Ponazuril"
            isChecked = true
            textSize = 14f
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
            setOnCheckedChangeListener { _, checked -> ponazurilChecked = checked }
        }
        container.addView(ponazurilCheck)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.schedule)
            .setView(container)
            .setPositiveButton(R.string.schedule) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = KittenRepository.scheduleNextTreatment(
                        fosterCaseId,
                        selectedDateMillis,
                        ponazurilChecked
                    )
                    if (success) {
                        refreshUI()
                    } else {
                        Toast.makeText(ctx, R.string.duplicate_schedule_error, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildTreatmentRow(dose: ScheduledDose, showDivider: Boolean): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }

        // Protocol column
        val protocolCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        protocolCol.addView(TextView(ctx).apply {
            text = dose.treatment.displayName
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        protocolCol.addView(TextView(ctx).apply {
            text = "Dose ${dose.doseNumber}"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_muted))
        })
        row.addView(protocolCol)

        // Dose column
        val doseCol = TextView(ctx).apply {
            text = dose.doseLabel
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(doseCol)

        // Due column
        val oneDayMs = 24L * 60 * 60 * 1000
        val todayStart = System.currentTimeMillis().let { it - it % oneDayMs }
        val isDueToday = dose.scheduledDateMillis in todayStart until (todayStart + oneDayMs)
        val isActionable = dose.isPast || isDueToday

        val dueCol = TextView(ctx).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            if (dose.isAdministered) {
                text = "\u2713 ${
                    SimpleDateFormat(
                        "MM/dd",
                        Locale.US
                    ).format(Date(dose.scheduledDateMillis))
                }"
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
            } else if (isDueToday) {
                text = getString(R.string.col_due)
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
            } else if (dose.isPast) {
                text = getString(R.string.overdue).uppercase()
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_crimson))
            } else {
                text = SimpleDateFormat("MM/dd", Locale.US).format(Date(dose.scheduledDateMillis))
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_muted))
            }
        }
        row.addView(dueCol)

        // Action column
        val actionWidth = (70 * dp).toInt()
        if (dose.isAdministered) {
            val doneText = TextView(ctx).apply {
                text = getString(R.string.done_check)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
                gravity = android.view.Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(actionWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(doneText)
        } else if (!isActionable) {
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(actionWidth, 0)
            })
        } else {
            val giveBtn = MaterialButton(
                ctx,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.give)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                isAllCaps = false
                minimumHeight = (32 * dp).toInt()
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                cornerRadius = (4 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(actionWidth, (32 * dp).toInt())

                if (dose.isPast) {
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
                    setTextColor(ContextCompat.getColor(ctx, R.color.white))
                    strokeWidth = 0
                } else {
                    setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_soft))
                    strokeColor = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.clinical_line)
                    )
                }

                setOnClickListener {
                    KittenRepository.markTreatmentAdministered(dose.treatmentId, dose.doseLabel)
                    refreshUI()
                }
            }
            row.addView(giveBtn)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(row)
        if (showDivider) {
            container.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                )
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.clinical_line_soft))
            })
        }
        return container
    }

    private fun buildMedicationCard(med: Medication): View {
        val view =
            layoutInflater.inflate(R.layout.item_medication_card, binding.layoutMedications, false)

        view.findViewById<TextView>(R.id.text_med_name).text = med.name

        val detailParts = listOfNotNull(
            med.dose.takeIf { it.isNotBlank() }?.let { "$it ${med.doseUnit}".trim() },
            med.route.takeIf { it.isNotBlank() },
            med.frequency.takeIf { it.isNotBlank() }
        )
        view.findViewById<TextView>(R.id.text_med_detail_line).apply {
            if (detailParts.isNotEmpty()) {
                text = detailParts.joinToString(" · ")
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        view.findViewById<TextView>(R.id.text_med_start).text =
            shortDateFormat.format(Date(med.startDateMillis))

        if (med.endDateMillis != null) {
            view.findViewById<TextView>(R.id.text_med_end).text =
                shortDateFormat.format(Date(med.endDateMillis))

            val days = ((med.endDateMillis - med.startDateMillis) / (1000 * 60 * 60 * 24)).toInt()
            view.findViewById<TextView>(R.id.text_med_course).text = when {
                days <= 0 -> "Single dose"
                days == 1 -> "1-day course"
                else -> "$days-day course"
            }
        } else {
            view.findViewById<TextView>(R.id.text_med_end).text = "Ongoing"
            view.findViewById<TextView>(R.id.text_med_course).text = "Open-ended"
        }

        if (med.instructions.isNotBlank()) {
            view.findViewById<TextView>(R.id.text_med_notes).apply {
                text = med.instructions
                visibility = View.VISIBLE
            }
        }

        return view
    }

    private fun exportFosterHistory() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        val ctx = requireContext()
        val df = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
        val tsf = SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.getDefault())

        val text = buildString {
            appendLine("FOSTER RECORD: ${fosterCase.name}")
            appendLine("=".repeat(40))
            appendLine("Breed: ${fosterCase.breed.display}")
            appendLine("Color: ${fosterCase.color.display}")
            appendLine("Sex: ${fosterCase.sex.display}")
            fosterCase.estimatedBirthdayMillis?.let { appendLine("Est. Birthday: ${df.format(Date(it))}") }
            fosterCase.ageInWeeks?.let { appendLine("Age: $it weeks") }
            appendLine("Intake: ${df.format(Date(fosterCase.intakeDateMillis))}")
            if (fosterCase.externalId.isNotBlank()) appendLine("Animal ID: ${fosterCase.externalId}")
            appendLine()

            if (fosterCase.weightEntries.isNotEmpty()) {
                appendLine("WEIGHT HISTORY")
                appendLine("-".repeat(30))
                fosterCase.weightEntries.sortedBy { it.dateMillis }.forEach { w ->
                    val lbs = w.weightGrams / 453.592f
                    appendLine(
                        "  ${df.format(Date(w.dateMillis))}  ${w.weightGrams.toInt()}g (%.2f lbs)".format(
                            lbs
                        )
                    )
                }
                appendLine()
            }

            if (fosterCase.stoolEntries.isNotEmpty()) {
                appendLine("STOOL LOG")
                appendLine("-".repeat(30))
                fosterCase.stoolEntries.sortedBy { it.dateMillis }.forEach { s ->
                    appendLine("  ${df.format(Date(s.dateMillis))}  Level ${s.level}")
                }
                appendLine()
            }

            if (fosterCase.eventEntries.isNotEmpty()) {
                appendLine("EVENTS")
                appendLine("-".repeat(30))
                fosterCase.eventEntries.sortedBy { it.dateMillis }.forEach { e ->
                    appendLine("  ${df.format(Date(e.dateMillis))}  ${e.type.display}")
                }
                appendLine()
            }

            if (fosterCase.administeredTreatments.isNotEmpty()) {
                appendLine("TREATMENTS")
                appendLine("-".repeat(30))
                fosterCase.administeredTreatments
                    .sortedBy { it.scheduledDateMillis }
                    .forEach { t ->
                        val status = if (t.administeredDateMillis != null) {
                            "Given ${df.format(Date(t.administeredDateMillis))}"
                        } else "Scheduled"
                        val dose = t.doseGiven?.let { " ($it)" } ?: ""
                        appendLine("  ${df.format(Date(t.scheduledDateMillis))}  ${t.treatmentType}$dose -$status")
                    }
                appendLine()
            }

            if (fosterCase.medications.isNotEmpty()) {
                appendLine("MEDICATIONS")
                appendLine("-".repeat(30))
                fosterCase.medications.sortedByDescending { it.startDateMillis }.forEach { m ->
                    val status = if (m.isActive) "Active" else "Stopped"
                    appendLine("  ${m.name} [$status]")
                    appendLine("    Started: ${df.format(Date(m.startDateMillis))}")
                    m.endDateMillis?.let {
                        val label = if (m.isActive) "Until" else "Ended"
                        appendLine("    $label: ${df.format(Date(it))}")
                    }
                    if (m.instructions.isNotBlank()) appendLine("    Instructions: ${m.instructions}")
                }
                appendLine()
            }

            appendLine("-".repeat(40))
            appendLine("Exported ${tsf.format(Date())}")
        }

        val safeName = fosterCase.name.replace(Regex("[^a-zA-Z0-9]"), "_").lowercase()
        val fileName = "${safeName}_foster_record.txt"

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Toast.makeText(ctx, getString(R.string.export_success, fileName), Toast.LENGTH_LONG)
                    .show()
            } else {
                Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Export", "Failed to export", e)
            Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddWeightDialog() {
        val MAX_WEIGHT_GRAMS = 9072f // 20 lbs

        val context = requireContext()
        var isLbs = false

        // Build layout: toggle group + text input
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val toggleGroup =
            com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
        val btnGrams = com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = getString(R.string.weight_unit_grams)
        }
        val btnLbs = com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = getString(R.string.weight_unit_lbs)
        }
        toggleGroup.addView(btnGrams)
        toggleGroup.addView(btnLbs)
        toggleGroup.check(btnGrams.id)
        container.addView(toggleGroup)

        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            hint = getString(R.string.weight_hint_grams)
        }
        val input = com.google.android.material.textfield.TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        inputLayout.addView(input)
        container.addView(inputLayout)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isLbs = checkedId == btnLbs.id
                inputLayout.hint = getString(
                    if (isLbs) R.string.weight_hint_lbs else R.string.weight_hint_grams
                )
                inputLayout.error = null
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.add_weight_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()

        // Override positive button to prevent dismiss on validation failure
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = input.text.toString().toFloatOrNull()
            when {
                raw == null -> {
                    inputLayout.error = getString(R.string.weight_error_empty)
                }

                raw <= 0f -> {
                    inputLayout.error = getString(R.string.weight_error_negative)
                }

                else -> {
                    val grams = if (isLbs) raw * 453.592f else raw
                    if (grams > MAX_WEIGHT_GRAMS) {
                        inputLayout.error = getString(R.string.weight_error_too_heavy)
                    } else {
                        KittenRepository.addWeight(
                            fosterCaseId,
                            WeightEntry(System.currentTimeMillis(), grams)
                        )
                        refreshUI()
                        checkWeightTrend()
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    private fun showLogStoolDialog() {
        val levels = (1..7).map { it.toString() }.toTypedArray()
        var selected = 3 // default to level 4 (0-indexed = 3)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.log_stool_title)
            .setSingleChoiceItems(levels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.save) { dialog, _ ->
                val level = selected + 1
                KittenRepository.addStool(
                    fosterCaseId,
                    StoolEntry(System.currentTimeMillis(), level)
                )
                refreshUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLogEventDialog() {
        val types = EventType.entries.toTypedArray()
        val labels = types.map { it.display }.toTypedArray()
        var selected = 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.log_event_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.save) { dialog, _ ->
                KittenRepository.addEvent(
                    fosterCaseId,
                    EventEntry(System.currentTimeMillis(), types[selected])
                )
                refreshUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddMedicationDialog() {
        setFragmentResultListener(AddMedicationDialogFragment.RESULT_KEY) { _, _ ->
            refreshUI()
        }
        val fosterInfo = KittenRepository.getFosterCase(fosterCaseId)
        AddMedicationDialogFragment.newInstance(
            fosterCaseId = fosterCaseId,
            patientName = fosterInfo?.name,
            animalNumber = fosterInfo?.externalId
        ).show(parentFragmentManager, "add_medication")
    }

    private fun checkWeightTrend() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        when (WeightAlertManager.evaluate(fosterCase)) {
            WeightTrend.NORMAL -> {
                if (fosterCase.weightDeclineWarned) {
                    KittenRepository.setWeightDeclineWarned(fosterCaseId, false)
                }
            }

            WeightTrend.DECLINING -> showDeclineAlert()
            WeightTrend.DECLINING_AFTER_WARNING -> showEscalationAlert()
        }
    }

    private fun showDeclineAlert() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return

        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.weight_alert_title),
                content = getString(R.string.weight_alert_message, fosterCase.name),
                timestamp = System.currentTimeMillis(),
                fosterCaseId = fosterCaseId
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weight_alert_title)
            .setMessage(getString(R.string.weight_alert_message, fosterCase.name))
            .setPositiveButton(R.string.alert_check_symptoms) { _, _ ->
                showSymptomQuestions()
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
    }

    private fun showSymptomQuestions() {
        val symptoms = booleanArrayOf(false, false)
        val items = arrayOf(
            getString(R.string.symptom_vomiting),
            getString(R.string.symptom_diarrhea)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.symptom_check_title)
            .setMultiChoiceItems(items, symptoms) { _, which, checked ->
                symptoms[which] = checked
            }
            .setPositiveButton(R.string.next) { _, _ ->
                if (symptoms.any { it }) {
                    showEscalationAlert()
                } else {
                    showFeedingSuggestion()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFeedingSuggestion() {
        KittenRepository.setWeightDeclineWarned(fosterCaseId, true)
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return

        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.feeding_suggestion_title),
                content = getString(
                    R.string.feeding_suggestion_message,
                    fosterCase.name,
                    fosterCase.name
                ),
                timestamp = System.currentTimeMillis(),
                fosterCaseId = fosterCaseId
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feeding_suggestion_title)
            .setMessage(
                getString(
                    R.string.feeding_suggestion_message,
                    fosterCase.name,
                    fosterCase.name
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showEscalationAlert() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return

        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.escalation_title),
                content = getString(R.string.escalation_message, fosterCase.name),
                timestamp = System.currentTimeMillis(),
                fosterCaseId = fosterCaseId
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.escalation_title)
            .setMessage(getString(R.string.escalation_message, fosterCase.name))
            .setPositiveButton(R.string.copy_email_request) { _, _ ->
                sendVetRequestEmail(fosterCase)
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
    }

    private fun sendVetRequestEmail(fosterCase: FosterCaseAnimal) {
        val latest = fosterCase.weightEntries.lastOrNull()
        val recentWeights = fosterCase.weightEntries.takeLast(5).joinToString("\n") { entry ->
            "${dateFormat.format(Date(entry.dateMillis))}: ${"%.0f".format(entry.weightGrams)} g"
        }

        val subject = if (fosterCase.externalId.isNotEmpty()) {
            "Weight concern & Vet Appointment Request: ${fosterCase.externalId}"
        } else {
            "Weight concern & Vet Appointment Request: ${fosterCase.name}"
        }
        val body = buildString {
            appendLine("Foster kitten ${fosterCase.name} has shown declining weight.")
            appendLine("I would like to request a vet appointment for a check-up.")
            appendLine()
            appendLine("Current weight: ${if (latest != null) "${"%.0f".format(latest.weightGrams)} g" else "N/A"}")
            appendLine()
            appendLine("Recent weight history:")
            appendLine(recentWeights)
        }

        sendEmail(subject, body)
    }

    private fun sendEmail(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("foster@humanecolorado.org"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent.createChooser(intent, "Send email"))
        }
    }

    private fun daysSinceStart(startDateMillis: Long): Int {
        val startCal = Calendar.getInstance().apply {
            timeInMillis = startDateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = nowCal.timeInMillis - startCal.timeInMillis
        return (diff / (24L * 60 * 60 * 1000)).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
