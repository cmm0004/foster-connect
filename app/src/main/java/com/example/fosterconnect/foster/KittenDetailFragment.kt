package com.example.fosterconnect.foster

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentKittenDetailBinding
import com.example.fosterconnect.foster.AdministeredTreatment
import com.example.fosterconnect.history.WeightAlertManager
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.history.WeightTrend
import com.example.fosterconnect.medication.FosterTreatmentSchedule
import com.example.fosterconnect.medication.Medication
import com.example.fosterconnect.medication.ScheduledDose
import com.example.fosterconnect.medication.StandardTreatment
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
    private lateinit var kittenId: String

    // ML Kit label scanning
    private val labelScanner = MedicationLabelScanner()
    private var dialogNameInput: TextInputEditText? = null
    private var dialogStrengthInput: TextInputEditText? = null
    private var dialogInstructionsInput: TextInputEditText? = null

    private val scanLabelLauncher: ActivityResultLauncher<Void?> =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                handleScannedBitmap(bitmap)
            }
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

        kittenId = requireArguments().getString("kittenId")!!
        refreshUI()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.kittensFlow.collect {
                    if (_binding != null) refreshUI()
                }
            }
        }

        binding.buttonSetBirthday.setOnClickListener {
            showSetBirthdayDialog()
        }

        binding.buttonAddWeight.setOnClickListener {
            showAddWeightDialog()
        }

        binding.buttonAddMedication.setOnClickListener {
            showAddMedicationDialog()
        }

        binding.buttonEmailOffice.setOnClickListener {
            val kitten = KittenRepository.getKitten(kittenId) ?: return@setOnClickListener
            copyVetRequestToClipboard(kitten)
        }

        binding.buttonMarkAdopted.setOnClickListener {
            showMarkAdoptedDialog()
        }

        binding.buttonSeeHistory.setOnClickListener {
            showTreatmentHistoryDialog()
        }
    }

    private fun showMarkAdoptedDialog() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Adoption")
            .setMessage("Are you sure you want to mark ${kitten.name} as adopted? They will be moved to the Previous Fosters list.")
            .setPositiveButton("Confirm") { _, _ ->
                KittenRepository.markAdopted(kittenId)
                findNavController().popBackStack()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshUI() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return

        if (kitten.isAdopted) {
            binding.buttonAddWeight.visibility = View.GONE
            binding.buttonAddMedication.visibility = View.GONE
            binding.buttonMarkAdopted.visibility = View.GONE
            binding.buttonSetBirthday.visibility = View.GONE
            binding.textIntakeDate.text = "Adopted on ${dateFormat.format(Date(kitten.adoptionDateMillis ?: 0))}"
        } else {
            binding.buttonAddWeight.visibility = View.VISIBLE
            binding.buttonAddMedication.visibility = View.VISIBLE
            binding.buttonMarkAdopted.visibility = View.VISIBLE
            binding.buttonSetBirthday.visibility = View.VISIBLE
            binding.textIntakeDate.text = "In care since ${dateFormat.format(Date(kitten.intakeDateMillis))}"
        }

        // Birthday and age
        val birthday = kitten.estimatedBirthdayMillis
        if (birthday != null) {
            val ageWeeks = kitten.ageInWeeks ?: 0
            binding.textBirthdayAge.text = getString(
                R.string.birthday_age_format,
                dateFormat.format(Date(birthday)),
                ageWeeks
            )
            binding.textBirthdayAge.visibility = View.VISIBLE
            binding.buttonSetBirthday.text = getString(R.string.edit_birthday_button)
        } else {
            binding.textBirthdayAge.visibility = View.GONE
            binding.buttonSetBirthday.text = getString(R.string.set_birthday_button)
        }

        val alteredStatus = if (kitten.isAltered) {
            if (kitten.sex == Sex.MALE) "Neutered" else "Spayed"
        } else {
            "Not yet altered"
        }

        binding.textKittenName.text = kitten.name
        binding.textExternalId.text = kitten.externalId
        binding.textExternalId.visibility = if (kitten.externalId.isNotEmpty()) View.VISIBLE else View.GONE
        binding.textKittenBreed.text = "${kitten.breed.display} · ${kitten.color.display}"
        binding.textKittenSex.text = "${kitten.sex.display} · $alteredStatus"

        val latest = kitten.weightEntries.lastOrNull()
        binding.textCurrentWeight.text = if (latest != null) {
            "%.0f g".format(latest.weightGrams)
        } else {
            getString(R.string.no_weight_logged)
        }

        // Weight history
        binding.layoutWeightHistory.removeAllViews()
        kitten.weightEntries.asReversed().forEach { entry ->
            val row = TextView(requireContext()).apply {
                text = "${dateFormat.format(Date(entry.dateMillis))}  —  ${"%.0f g".format(entry.weightGrams)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 6, 0, 6)
            }
            binding.layoutWeightHistory.addView(row)
        }

        // Medications (only active ones — stopped meds go to history)
        val currentWeightGrams = latest?.weightGrams
        binding.layoutMedications.removeAllViews()
        val activeMeds = kitten.medications.filter { it.isActive }.sortedByDescending { it.startDateMillis }
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
        renderTreatmentSchedule(kitten, currentWeightGrams)
    }

    private fun renderTreatmentSchedule(kitten: Kitten, currentWeightGrams: Float?) {
        binding.layoutTreatmentSchedule.removeAllViews()

        val schedule = FosterTreatmentSchedule.generateSchedule(
            kitten.intakeDateMillis,
            kitten.estimatedBirthdayMillis,
            currentWeightGrams,
            kitten.administeredTreatments
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

        if (kitten.estimatedBirthdayMillis == null) {
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
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        val currentWeightGrams = kitten.weightEntries.lastOrNull()?.weightGrams
        val dp = resources.displayMetrics.density

        val schedule = FosterTreatmentSchedule.generateSchedule(
            kitten.intakeDateMillis,
            kitten.estimatedBirthdayMillis,
            currentWeightGrams,
            kitten.administeredTreatments
        )

        val completedDoses = schedule.groupBy { it.doseNumber }
            .toSortedMap()
            .filter { (_, doses) -> doses.all { it.isAdministered } }
        val stoppedMeds = kitten.medications.filter { !it.isActive }

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
                    text = if (med.strength != null) "${med.name}  \u00b7  ${med.strength}" else med.name
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
                            kittenId,
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
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        val latestWeight = kitten.weightEntries.lastOrNull()
        val identifier = if (kitten.externalId.isNotEmpty()) "${kitten.name} (${kitten.externalId})" else kitten.name
        val doseDate = dateFormat.format(Date(doses.first().scheduledDateMillis))

        val body = buildString {
            appendLine("Subject: Treatment Complete — $identifier — Dose $doseNumber")
            appendLine()
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
                kittenId = kitten.id
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.treatment_complete_title))
            .setMessage(getString(R.string.treatment_complete_message, identifier, doseNumber))
            .setPositiveButton(R.string.copy_email_request) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Treatment Report", body))
                android.widget.Toast.makeText(requireContext(), "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
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

        val title = if (med.strength != null) "${med.name}  ·  ${med.strength}" else med.name
        val nameText = TextView(requireContext()).apply {
            text = title
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
                    KittenRepository.stopMedication(kittenId, med.id)
                    refreshUI()
                }
            }
            row.addView(stopBtn)
            content.addView(row)
        }

        card.addView(content)
        return card
    }

    private fun showSetBirthdayDialog() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        val cal = Calendar.getInstance()
        if (kitten.estimatedBirthdayMillis != null) {
            cal.timeInMillis = kitten.estimatedBirthdayMillis
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                KittenRepository.setBirthday(kittenId, selected.timeInMillis)
                refreshUI()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
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
                        kittenId,
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

    private fun showAddMedicationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_medication, null)
        val scanButton = dialogView.findViewById<MaterialButton>(R.id.button_scan_label)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.input_med_name)
        val inputStrength = dialogView.findViewById<TextInputEditText>(R.id.input_strength)
        val inputInstructions = dialogView.findViewById<TextInputEditText>(R.id.input_instructions)
        val startDateButton = dialogView.findViewById<MaterialButton>(R.id.button_start_date)

        var startDateMillis = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun updateStartDateLabel() {
            startDateButton.text = getString(R.string.start_date_format, dateFormat.format(Date(startDateMillis)))
        }
        updateStartDateLabel()
        startDateButton.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = startDateMillis }
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startDateMillis = selected.timeInMillis
                    updateStartDateLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Store refs so the camera launcher callback can prefill these fields
        dialogNameInput = inputName
        dialogStrengthInput = inputStrength
        dialogInstructionsInput = inputInstructions

        scanButton.setOnClickListener {
            scanLabelLauncher.launch(null)
        }
        scanButton.setOnLongClickListener {
            loadTestImageAndScan()
            true
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_medication_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = inputName?.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton

                val strength = inputStrength?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val instructions = inputInstructions?.text?.toString()?.trim().orEmpty()

                val medication = Medication(
                    name = name,
                    strength = strength,
                    instructions = instructions,
                    startDateMillis = startDateMillis
                )

                KittenRepository.addMedication(kittenId, medication)
                refreshUI()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            dialogNameInput = null
            dialogStrengthInput = null
            dialogInstructionsInput = null
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        inputName?.requestFocus()
    }

    private fun loadTestImageAndScan() {
        try {
            val bitmap = requireContext().assets.open("testimage.jpg").use {
                BitmapFactory.decodeStream(it)
            }
            if (bitmap == null) {
                Log.e("MedScan", "Failed to decode testimage.jpg")
                return
            }
            android.widget.Toast.makeText(requireContext(), "Scanning test image…", android.widget.Toast.LENGTH_SHORT).show()
            handleScannedBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("MedScan", "Failed to load test image", e)
            android.widget.Toast.makeText(requireContext(), "Test image not found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleScannedBitmap(bitmap: Bitmap) {
        labelScanner.scan(
            bitmap,
            onResult = { rawText ->
                Log.d("MedScan", "OCR result:\n$rawText")
                val parsed = MedicationLabelParser.parse(rawText)
                applyParsedMedicationToDialog(parsed)
            },
            onError = { e ->
                Log.e("MedScan", "OCR failed", e)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Could not read label",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    private fun applyParsedMedicationToDialog(parsed: ParsedMedication) {
        parsed.name?.let { dialogNameInput?.setText(it) }
        parsed.strength?.let { dialogStrengthInput?.setText(it) }
        parsed.instructions?.let { dialogInstructionsInput?.setText(it) }
        parsed.animalId?.let { animalId ->
            KittenRepository.setExternalId(kittenId, animalId)
            refreshUI()
        }
    }

    private fun checkWeightTrend() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        when (WeightAlertManager.evaluate(kitten)) {
            WeightTrend.NORMAL -> {
                if (kitten.weightDeclineWarned) {
                    KittenRepository.setWeightDeclineWarned(kittenId, false)
                }
            }
            WeightTrend.DECLINING -> showDeclineAlert()
            WeightTrend.DECLINING_AFTER_WARNING -> showEscalationAlert()
        }
    }

    private fun showDeclineAlert() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return
        
        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.weight_alert_title),
                content = getString(R.string.weight_alert_message, kitten.name),
                timestamp = System.currentTimeMillis(),
                kittenId = kitten.id
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weight_alert_title)
            .setMessage(getString(R.string.weight_alert_message, kitten.name))
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
        KittenRepository.setWeightDeclineWarned(kittenId, true)
        val kitten = KittenRepository.getKitten(kittenId) ?: return

        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.feeding_suggestion_title),
                content = getString(R.string.feeding_suggestion_message, kitten.name, kitten.name),
                timestamp = System.currentTimeMillis(),
                kittenId = kitten.id
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feeding_suggestion_title)
            .setMessage(getString(R.string.feeding_suggestion_message, kitten.name, kitten.name))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showEscalationAlert() {
        val kitten = KittenRepository.getKitten(kittenId) ?: return

        KittenRepository.addMessage(
            com.example.fosterconnect.history.Message(
                title = getString(R.string.escalation_title),
                content = getString(R.string.escalation_message, kitten.name),
                timestamp = System.currentTimeMillis(),
                kittenId = kitten.id
            )
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.escalation_title)
            .setMessage(getString(R.string.escalation_message, kitten.name))
            .setPositiveButton(R.string.copy_email_request) { _, _ ->
                copyVetRequestToClipboard(kitten)
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
    }

    private fun copyVetRequestToClipboard(kitten: Kitten) {
        val latest = kitten.weightEntries.lastOrNull()
        val recentWeights = kitten.weightEntries.takeLast(5).joinToString("\n") { entry ->
            "${dateFormat.format(Date(entry.dateMillis))}: ${"%.0f".format(entry.weightGrams)} g"
        }

        val subject = "Weight concern & Vet Appointment Request: ${kitten.name}"
        val body = buildString {
            appendLine("Subject: $subject")
            appendLine()
            appendLine("Foster kitten ${kitten.name} has shown declining weight.")
            appendLine("I would like to request a vet appointment for a check-up.")
            appendLine()
            appendLine("Current weight: ${if (latest != null) "${"%.0f".format(latest.weightGrams)} g" else "N/A"}")
            appendLine()
            appendLine("Recent weight history:")
            appendLine(recentWeights)
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Vet Appointment Request", body)
        clipboard.setPrimaryClip(clip)

        android.widget.Toast.makeText(
            requireContext(),
            "Request copied to clipboard",
            android.widget.Toast.LENGTH_SHORT
        ).show()
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
