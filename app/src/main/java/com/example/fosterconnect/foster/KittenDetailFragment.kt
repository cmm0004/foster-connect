package com.example.fosterconnect.foster

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
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

        binding.buttonAddWeight.setOnClickListener {
            showAddWeightDialog()
        }

        binding.buttonWeightHistory.setOnClickListener {
            showWeightHistoryDialog()
        }

        binding.buttonAddMedication.setOnClickListener {
            launchLabelCapture()
        }
        binding.buttonAddMedication.setOnLongClickListener {
            loadTestImageAndScan()
            true
        }

        binding.buttonEmailOffice.setOnClickListener {
            val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return@setOnClickListener
            sendVetRequestEmail(fosterCase)
        }

        binding.buttonMarkAdopted.setOnClickListener {
            showMarkAdoptedDialog()
        }

        binding.buttonSeeHistory.setOnClickListener {
            showTreatmentHistoryDialog()
        }

        binding.buttonEditRankings.setOnClickListener {
            val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return@setOnClickListener
            findNavController().navigate(
                R.id.KittenRankingFragment,
                bundleOf(
                    "animalId" to fosterCase.animalId,
                    "fosterCaseId" to fosterCase.fosterCaseId
                )
            )
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

        if (fosterCase.isCompleted) {
            binding.buttonAddWeight.visibility = View.GONE
            binding.buttonAddMedication.visibility = View.GONE
            binding.buttonMarkAdopted.visibility = View.GONE
            binding.textIntakeDate.text = getString(
                R.string.returned_to_shelter_format,
                dateFormat.format(Date(fosterCase.outDateMillis ?: 0L))
            )
        } else {
            binding.buttonAddWeight.visibility = View.VISIBLE
            binding.buttonAddMedication.visibility = View.VISIBLE
            binding.buttonMarkAdopted.visibility = View.VISIBLE
            binding.textIntakeDate.text = "In care since ${dateFormat.format(Date(fosterCase.intakeDateMillis))}"
        }

        val birthday = fosterCase.estimatedBirthdayMillis
        if (birthday != null) {
            val ageWeeks = fosterCase.ageInWeeks ?: 0
            binding.textBirthdayAge.text = formatAge(ageWeeks)
            binding.layoutAge.visibility = View.VISIBLE
        } else {
            binding.layoutAge.visibility = View.GONE
        }

        val alteredStatus = if (fosterCase.isAlteredAtIntake) {
            if (fosterCase.sex == Sex.MALE) "Neutered" else "Spayed"
        } else {
            "Not yet altered"
        }

        binding.textKittenName.text = fosterCase.name
        binding.textExternalId.text = fosterCase.externalId
        binding.textExternalId.visibility = if (fosterCase.externalId.isNotEmpty()) View.VISIBLE else View.GONE
        binding.textKittenBreed.text = "${fosterCase.breed.display} · ${fosterCase.color.display}"
        binding.textKittenSex.text = "${fosterCase.sex.display} · $alteredStatus"

        val latest = fosterCase.weightEntries.lastOrNull()
        binding.textCurrentWeight.text = if (latest != null) {
            formatWeight(latest.weightGrams)
        } else {
            getString(R.string.no_weight_logged)
        }

        binding.buttonWeightHistory.visibility = if (fosterCase.weightEntries.size > 1) View.VISIBLE else View.GONE

        // Medications (only active ones — stopped meds go to history)
        val currentWeightGrams = latest?.weightGrams
        binding.layoutMedications.removeAllViews()
        val activeMeds = fosterCase.medications.filter { it.isActive }.sortedByDescending { it.startDateMillis }
        if (activeMeds.isEmpty()) {
            val empty = TextView(requireContext()).apply {
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

    private fun renderTreatmentSchedule(fosterCase: FosterCaseAnimal, currentWeightGrams: Float?) {
        binding.layoutTreatmentSchedule.removeAllViews()

        val schedule = FosterTreatmentSchedule.generateSchedule(
            fosterCase.intakeDateMillis,
            fosterCase.estimatedBirthdayMillis,
            currentWeightGrams,
            fosterCase.administeredTreatments
        )

        if (schedule.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.no_scheduled_treatments)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 6, 0, 6)
            }
            binding.layoutTreatmentSchedule.addView(empty)
            return
        }

        if (fosterCase.estimatedBirthdayMillis == null) {
            val hint = TextView(requireContext()).apply {
                text = getString(R.string.set_birthday_for_schedule)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_on_surface, null))
                setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            }
            binding.layoutTreatmentSchedule.addView(hint)
        }

        // Only show the next incomplete dose group
        val byDose = schedule.groupBy { it.doseNumber }
        val nextIncompleteDose = byDose.toSortedMap().entries.firstOrNull { (_, doses) ->
            !doses.all { it.isAdministered }
        }

        if (nextIncompleteDose != null) {
            binding.layoutTreatmentSchedule.addView(
                buildDoseCard(nextIncompleteDose.key, nextIncompleteDose.value)
            )
        } else {
            val done = TextView(requireContext()).apply {
                text = getString(R.string.no_scheduled_treatments)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 6, 0, 6)
            }
            binding.layoutTreatmentSchedule.addView(done)
        }
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

    private fun buildDoseCard(doseNumber: Int, doses: List<ScheduledDose>): View {
        val dp = resources.displayMetrics.density
        val allAdministered = doses.all { it.isAdministered }
        val isPast = doses.first().isPast
        val doseDate = doses.first().scheduledDateMillis

        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            radius = 12f * dp
            cardElevation = 2f * dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
            if (allAdministered) alpha = 0.6f
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padPx = (16 * dp).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

        // Header row
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerText = TextView(requireContext()).apply {
            text = getString(R.string.dose_header_format, doseNumber, dateFormat.format(Date(doseDate)))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerText)

        if (allAdministered) {
            val badge = TextView(requireContext()).apply {
                text = "\u2713 ${getString(R.string.dose_given)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_primary, null))
            }
            headerRow.addView(badge)
        } else if (isPast) {
            val badge = TextView(requireContext()).apply {
                text = getString(R.string.overdue)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_error, null))
                setTypeface(null, Typeface.BOLD)
            }
            headerRow.addView(badge)
        }

        content.addView(headerRow)

        // Treatment rows
        for (dose in doses) {
            val treatmentRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                val topPad = (8 * dp).toInt()
                setPadding(0, topPad, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val checkmark = if (dose.isAdministered) "\u2713 " else ""
            val nameText = TextView(requireContext()).apply {
                text = "$checkmark${dose.treatment.displayName}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                if (dose.isAdministered) {
                    setTypeface(null, Typeface.ITALIC)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            treatmentRow.addView(nameText)

            val doseText = TextView(requireContext()).apply {
                text = dose.doseLabel
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            }
            treatmentRow.addView(doseText)

            if (!dose.isAdministered) {
                val markBtn = MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = getString(R.string.mark_as_given)
                    isAllCaps = false
                    textSize = 11f
                    val padH = (8 * dp).toInt()
                    setPadding(padH, 0, padH, 0)
                    minimumHeight = (32 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * dp).toInt() }
                    setOnClickListener {
                        KittenRepository.markTreatmentAdministered(
                            fosterCaseId,
                            AdministeredTreatment(
                                treatmentType = dose.treatment.name,
                                scheduledDateMillis = dose.scheduledDateMillis,
                                administeredDateMillis = System.currentTimeMillis()
                            )
                        )
                        // Check if all treatments in this dose are now complete
                        val othersDone = doses.all { it.isAdministered || it.treatment == dose.treatment }
                        if (othersDone) {
                            showTreatmentCompleteAlert(doseNumber, doses)
                        }
                        refreshUI()
                    }
                }
                treatmentRow.addView(markBtn)
            }

            content.addView(treatmentRow)
        }

        card.addView(content)
        return card
    }

    private fun showTreatmentCompleteAlert(doseNumber: Int, doses: List<ScheduledDose>) {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        val latestWeight = fosterCase.weightEntries.lastOrNull()
        val identifier = if (fosterCase.externalId.isNotEmpty()) {
            "${fosterCase.name} (${fosterCase.externalId})"
        } else {
            fosterCase.name
        }
        val doseDate = dateFormat.format(Date(doses.first().scheduledDateMillis))

        val body = buildString {
            appendLine("Foster kitten $identifier has completed treatment dose $doseNumber ($doseDate).")
            appendLine()
            appendLine("Weight at time of treatment: ${if (latestWeight != null) "${"%.0f".format(latestWeight.weightGrams)} g" else "N/A"}")
            appendLine()
            appendLine("Treatments administered:")
            for (dose in doses) {
                appendLine("  - ${dose.treatment.displayName}: ${dose.doseLabel}")
            }
        }

        // Save to messages
        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = "Treatment Complete — Dose $doseNumber",
                content = "All treatments for $identifier dose $doseNumber ($doseDate) have been administered. Tap to copy email body.",
                timestamp = System.currentTimeMillis(),
                fosterCaseId = fosterCase.fosterCaseId
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.treatment_complete_title))
            .setMessage(getString(R.string.treatment_complete_message, identifier, doseNumber))
            .setPositiveButton(R.string.copy_email_request) { _, _ ->
                val subject = if (fosterCase.externalId.isNotEmpty()) {
                    "Treatment Complete — ${fosterCase.externalId} — Dose $doseNumber"
                } else {
                    "Treatment Complete — ${fosterCase.name} — Dose $doseNumber"
                }
                sendEmail(subject, body)
            }
            .setNegativeButton(R.string.dismiss, null)
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
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.weight_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 24, 48, 8)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_weight_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val grams = input.text.toString().toFloatOrNull()
                if (grams != null && grams > 0f) {
                    KittenRepository.addWeight(
                        fosterCaseId,
                        WeightEntry(System.currentTimeMillis(), grams)
                    )
                    refreshUI()
                    checkWeightTrend()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showWeightHistoryDialog() {
        val fosterCase = KittenRepository.getFosterCase(fosterCaseId) ?: return
        if (fosterCase.weightEntries.isEmpty()) return

        val scrollView = android.widget.ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        fosterCase.weightEntries.asReversed().forEach { entry ->
            val row = TextView(requireContext()).apply {
                text = "${dateFormat.format(Date(entry.dateMillis))}  —  ${formatWeight(entry.weightGrams)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 12, 0, 12)
            }
            layout.addView(row)
        }
        scrollView.addView(layout)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weight_history_label)
            .setView(scrollView)
            .setPositiveButton(R.string.dismiss, null)
            .show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
