package com.example.fosterconnect.foster

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.example.fosterconnect.history.WeightAlertManager
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.history.WeightTrend
import com.example.fosterconnect.medication.FosterTreatmentSchedule
import com.example.fosterconnect.medication.Medication
import com.example.fosterconnect.medication.ScheduledDose
import com.example.fosterconnect.medication.scan.MedicationLabelParser
import com.example.fosterconnect.medication.scan.MedicationLabelScanner
import com.example.fosterconnect.medication.scan.ParsedMedication
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var fosterCaseId: String

    private val labelScanner = MedicationLabelScanner()
    private var pendingLabelFile: java.io.File? = null

    private val scanLabelLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingLabelFile
            pendingLabelFile = null
            if (!success || file == null || !file.exists() || file.length() == 0L) {
                Log.d("MedScan", "Scan cancelled or empty capture (success=$success)")
                showAddMedicationDialogPrefilled(null)
                return@registerForActivityResult
            }
            val bitmap = decodeSampledLabel(file, maxDim = 2048)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "Could not decode photo", Toast.LENGTH_SHORT).show()
                showAddMedicationDialogPrefilled(null)
                return@registerForActivityResult
            }
            Log.d("MedScan", "Captured ${file.length()} bytes, decoded ${bitmap.width}x${bitmap.height}")
            runLabelOcr(bitmap)
        }

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

        binding.buttonBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.buttonAddWeight.setOnClickListener {
            showAddWeightDialog()
        }

        binding.buttonLogStool.setOnClickListener {
            Toast.makeText(requireContext(), "Stool logging coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.buttonLogEvent.setOnClickListener {
            Toast.makeText(requireContext(), "Event logging coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.buttonAddMedication.setOnClickListener {
            launchLabelCapture()
        }
        binding.buttonAddMedication.setOnLongClickListener {
            loadTestImageAndScan()
            true
        }

        binding.buttonMarkAdopted.setOnClickListener {
            showMarkAdoptedDialog()
        }

        binding.buttonSeeHistory.setOnClickListener {
            showTreatmentHistoryDialog()
        }
    }

    private fun showMarkAdoptedDialog() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.foster_completed_title)
            .setMessage(getString(R.string.foster_completed_message, fosterCase.name))
            .setPositiveButton("Confirm") { _, _ ->
                KittenRepository.markCaseCompleted(fosterCaseId)
                findNavController().popBackStack()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshUI() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        val ctx = requireContext()

        if (fosterCase.isCompleted) {
            binding.buttonAddWeight.visibility = View.GONE
            binding.buttonLogStool.visibility = View.GONE
            binding.buttonLogEvent.visibility = View.GONE
            binding.buttonAddMedication.visibility = View.GONE
            binding.buttonMarkAdopted.visibility = View.GONE
        } else {
            binding.buttonAddWeight.visibility = View.VISIBLE
            binding.buttonLogStool.visibility = View.VISIBLE
            binding.buttonLogEvent.visibility = View.VISIBLE
            binding.buttonAddMedication.visibility = View.VISIBLE
            binding.buttonMarkAdopted.visibility = View.VISIBLE
        }

        // Header bar
        binding.textHeaderName.text = fosterCase.name
        binding.textHeaderId.text = if (fosterCase.externalId.isNotEmpty()) "· #${fosterCase.externalId}" else ""

        // Patient header
        binding.imageProfile.setImageResource(fosterCase.color.defaultProfileDrawable())
        binding.textKittenName.text = fosterCase.name

        val nickPart = if (fosterCase.litterName != null) "\"${fosterCase.litterName}\" · " else ""
        binding.textKittenBreed.text = "${nickPart}DSH · ${fosterCase.color.display}"

        // Info chips
        binding.layoutInfoChips.removeAllViews()
        addInfoChip(fosterCase.sex.display.uppercase())
        addInfoChip(if (fosterCase.isAlteredAtIntake) "ALTERED" else "INTACT")
        addInfoChip("INTAKE ${dateFormat.format(Date(fosterCase.intakeDateMillis)).uppercase()}")

        // Vitals KPIs
        val latest = fosterCase.weightEntries.lastOrNull()
        binding.textCurrentWeight.text = if (latest != null) {
            "${"%.0f".format(latest.weightGrams)}g"
        } else {
            "--"
        }
        binding.textWeightTrend.text = if (latest != null) {
            val ageWeeks = fosterCase.ageInWeeks ?: 0
            val expected = ageWeeks * 100f
            val diff = latest.weightGrams - expected
            if (diff < 0) "${"%.0f".format(diff)}g exp" else "+${"%.0f".format(diff)}g exp"
        } else {
            ""
        }

        // Placeholder stool/events data
        binding.textStoolAvg.text = "4.6/7"
        binding.textEventsCount.text = "0"

        // Status badge
        val currentWeightGrams = latest?.weightGrams
        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.intakeDateMillis, fosterCase.estimatedBirthdayMillis,
            currentWeightGrams, fosterCase.administeredTreatments
        )
        val overdueCount = schedule.count { it.isPast && !it.isAdministered }
        if (overdueCount > 0) {
            binding.textStatusBadge.text = getString(R.string.action_required)
            binding.textStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_crimson))
            binding.textStatusBadge.setBackgroundColor(ContextCompat.getColor(ctx, R.color.clinical_crimson_soft))
        } else {
            binding.textStatusBadge.text = getString(R.string.stable)
            binding.textStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
            binding.textStatusBadge.setBackgroundColor(ContextCompat.getColor(ctx, R.color.clinical_sage_tint))
        }

        // Vitals chart
        if (fosterCase.weightEntries.size >= 2) {
            binding.vitalsChart.setWeightEntries(fosterCase.weightEntries)
        } else {
            binding.vitalsChart.setPlaceholderData()
        }

        // Medications (only active ones — stopped meds go to history)
        binding.layoutMedications.removeAllViews()
        val activeMeds = fosterCase.medications.filter { it.isActive }.sortedByDescending { it.startDateMillis }
        if (activeMeds.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = getString(R.string.no_medications)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 6, 0, 6)
            }
            binding.layoutMedications.addView(empty)
        } else {
            activeMeds.forEach { med ->
                binding.layoutMedications.addView(buildMedicationCard(med))
            }
        }

        // Treatment schedule
        renderTreatmentSchedule(fosterCase, currentWeightGrams)
    }

    private fun addInfoChip(text: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val chip = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.clinical_ink_soft))
            letterSpacing = 0.05f
            val bg = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.clinical_sage_tint))
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
        val ctx = requireContext()

        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.intakeDateMillis,
            fosterCase.estimatedBirthdayMillis,
            currentWeightGrams,
            fosterCase.administeredTreatments
        )

        val overdueCount = schedule.count { it.isPast && !it.isAdministered }

        // Update ledger header
        if (overdueCount > 0) {
            binding.textTreatmentStatus.text = getString(R.string.protocols_overdue_format, overdueCount)
            binding.textTreatmentBadge.text = getString(R.string.overdue).uppercase()
            binding.textTreatmentBadge.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_crimson))
        } else {
            binding.textTreatmentStatus.text = getString(R.string.all_protocols_administered)
            binding.textTreatmentBadge.text = getString(R.string.complete)
            binding.textTreatmentBadge.setTextColor(ContextCompat.getColor(ctx, R.color.clinical_sage))
        }

        if (schedule.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = getString(R.string.no_scheduled_treatments)
                textSize = 12f
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
            }
            binding.layoutTreatmentSchedule.addView(empty)
            return
        }

        // Show the next incomplete dose group as table rows
        val byDose = schedule.groupBy { it.doseNumber }
        val nextIncompleteDose = byDose.toSortedMap().entries.firstOrNull { (_, doses) ->
            !doses.all { it.isAdministered }
        }

        val dosesToShow = nextIncompleteDose?.value ?: schedule.take(3)
        dosesToShow.forEachIndexed { index, dose ->
            binding.layoutTreatmentSchedule.addView(
                buildTreatmentRow(dose, index < dosesToShow.lastIndex)
            )
        }
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
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
        val dueCol = TextView(ctx).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            if (dose.isAdministered) {
                text = "\u2713 ${SimpleDateFormat("MM/dd", Locale.US).format(Date(dose.scheduledDateMillis))}"
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
                layoutParams = LinearLayout.LayoutParams(actionWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(doneText)
        } else {
            val giveBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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
                    KittenRepository.markTreatmentAdministered(
                        fosterCaseId,
                        AdministeredTreatment(
                            treatmentType = dose.treatment.name,
                            scheduledDateMillis = dose.scheduledDateMillis,
                            administeredDateMillis = System.currentTimeMillis()
                        )
                    )
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

    private fun showTreatmentHistoryDialog() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        val currentWeightGrams = fosterCase.weightEntries.lastOrNull()?.weightGrams
        val dp = resources.displayMetrics.density

        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.intakeDateMillis,
            fosterCase.estimatedBirthdayMillis,
            currentWeightGrams,
            fosterCase.administeredTreatments
        )

        val completedDoses = schedule.groupBy { it.doseNumber }
            .toSortedMap()
            .filter { (_, doses) -> doses.all { it.isAdministered } }
        val stoppedMeds = fosterCase.medications.filter { !it.isActive }

        if (completedDoses.isEmpty() && stoppedMeds.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.treatment_history_title)
                .setMessage(getString(R.string.no_history_yet))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val scrollView = androidx.core.widget.NestedScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(container)

        // Completed treatment doses
        if (completedDoses.isNotEmpty()) {
            val header = TextView(requireContext()).apply {
                text = getString(R.string.completed_treatments_header)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            container.addView(header)

            for ((doseNum, doses) in completedDoses) {
                val doseDate = doses.first().scheduledDateMillis
                val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                    radius = 12f * dp
                    cardElevation = 1f * dp
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * dp).toInt() }
                }

                val content = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val padPx = (12 * dp).toInt()
                    setPadding(padPx, padPx, padPx, padPx)
                }

                val titleText = TextView(requireContext()).apply {
                    text = "\u2713 ${getString(R.string.dose_header_format, doseNum, dateFormat.format(Date(doseDate)))}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                }
                content.addView(titleText)

                for (dose in doses) {
                    val row = TextView(requireContext()).apply {
                        text = "${dose.treatment.displayName}: ${dose.doseLabel}"
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    }
                    content.addView(row)
                }

                card.addView(content)
                container.addView(card)
            }
        }

        // Stopped medications
        if (stoppedMeds.isNotEmpty()) {
            val header = TextView(requireContext()).apply {
                text = getString(R.string.stopped_medications_header)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
            }
            container.addView(header)

            for (med in stoppedMeds.sortedByDescending { it.endDateMillis }) {
                val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                    radius = 12f * dp
                    cardElevation = 1f * dp
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * dp).toInt() }
                }

                val content = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val padPx = (12 * dp).toInt()
                    setPadding(padPx, padPx, padPx, padPx)
                }

                val nameText = TextView(requireContext()).apply {
                    text = med.name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                }
                content.addView(nameText)

                if (med.instructions.isNotBlank()) {
                    val instructionsText = TextView(requireContext()).apply {
                        text = med.instructions
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    }
                    content.addView(instructionsText)
                }

                val dateRange = buildString {
                    append("${dateFormat.format(Date(med.startDateMillis))} \u2014 ")
                    append(dateFormat.format(Date(med.endDateMillis ?: 0)))
                }
                val dateText = TextView(requireContext()).apply {
                    text = dateRange
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                }
                content.addView(dateText)

                card.addView(content)
                container.addView(card)
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.treatment_history_title)
            .setView(scrollView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun buildMedicationCard(med: Medication): View {
        val dp = resources.displayMetrics.density
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            radius = 12f * dp
            cardElevation = 2f * dp
            val marginPx = (8 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginPx }
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padPx = (16 * dp).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

        val nameText = TextView(requireContext()).apply {
            text = med.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        }
        content.addView(nameText)

        if (med.isActive) {
            val dayNumber = daysSinceStart(med.startDateMillis) + 1
            val dayText = TextView(requireContext()).apply {
                text = getString(R.string.day_n_format, dayNumber)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_primary, null))
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }
            content.addView(dayText)
        }

        if (med.instructions.isNotBlank()) {
            val instructionsText = TextView(requireContext()).apply {
                text = med.instructions
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            content.addView(instructionsText)
        }

        val dateRange = buildString {
            append("Started: ${dateFormat.format(Date(med.startDateMillis))}")
            if (med.endDateMillis != null) {
                append("\nEnded: ${dateFormat.format(Date(med.endDateMillis))}")
            }
        }
        val dateText = TextView(requireContext()).apply {
            text = dateRange
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        content.addView(dateText)

        if (med.isActive) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            val badge = TextView(requireContext()).apply {
                text = getString(R.string.active_label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_primary, null))
                val padH = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, 0, padH, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(badge)

            val stopBtn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.stop_medication)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    KittenRepository.stopMedication(med.id)
                    refreshUI()
                }
            }
            row.addView(stopBtn)
            content.addView(row)
        }

        card.addView(content)
        return card
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

        val toggleGroup = com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
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

    private fun launchLabelCapture() {
        val ctx = requireContext()
        val dir = java.io.File(ctx.cacheDir, "scans").apply { mkdirs() }
        val file = java.io.File(dir, "label_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingLabelFile = file
        scanLabelLauncher.launch(uri)
    }

    private fun loadTestImageAndScan() {
        try {
            val bitmap = requireContext().assets.open("testimage.jpg").use {
                BitmapFactory.decodeStream(it)
            }
            if (bitmap == null) {
                Log.e("MedScan", "Failed to decode testimage.jpg")
                showAddMedicationDialogPrefilled(null)
                return
            }
            Toast.makeText(requireContext(), "Scanning test image…", Toast.LENGTH_SHORT).show()
            runLabelOcr(bitmap)
        } catch (e: Exception) {
            Log.e("MedScan", "Failed to load test image", e)
            Toast.makeText(requireContext(), "Test image not found", Toast.LENGTH_SHORT).show()
            showAddMedicationDialogPrefilled(null)
        }
    }

    private fun decodeSampledLabel(file: java.io.File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun runLabelOcr(bitmap: Bitmap) {
        labelScanner.scan(
            bitmap,
            onResult = { rawText ->
                Log.d("MedScan", "OCR result:\n$rawText")
                val parsed = MedicationLabelParser.parse(rawText)
                parsed.animalId?.let { animalId ->
                    val fosterCase = KittenRepository.getFosterCase(fosterCaseId)
                    if (fosterCase != null) {
                        KittenRepository.setExternalId(fosterCase.animalId, animalId)
                    }
                }
                if (_binding != null) showAddMedicationDialogPrefilled(parsed)
            },
            onError = { e ->
                Log.e("MedScan", "OCR failed", e)
                if (_binding != null) {
                    Toast.makeText(requireContext(), "Could not read label", Toast.LENGTH_SHORT).show()
                    showAddMedicationDialogPrefilled(null)
                }
            }
        )
    }

    private fun showAddMedicationDialogPrefilled(parsed: ParsedMedication?) {
        setFragmentResultListener(AddMedicationDialogFragment.RESULT_KEY) { _, _ ->
            refreshUI()
        }
        AddMedicationDialogFragment.newInstance(
            fosterCaseId = fosterCaseId,
            prefillName = parsed?.name,
            prefillInstructions = parsed?.instructions,
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
                content = getString(R.string.feeding_suggestion_message, fosterCase.name, fosterCase.name),
                timestamp = System.currentTimeMillis(),
                fosterCaseId = fosterCaseId
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feeding_suggestion_title)
            .setMessage(getString(R.string.feeding_suggestion_message, fosterCase.name, fosterCase.name))
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
