package com.example.fosterconnect.foster

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.medication.Medication
import com.example.fosterconnect.medication.scan.MedicationLabelParser
import com.example.fosterconnect.medication.scan.MedicationLabelScanner
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AddMedicationDialogFragment : DialogFragment() {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private lateinit var fosterCaseId: String
    private var patientName: String? = null
    private var animalNumber: String? = null
    private var startDateMillis: Long = 0L
    private var endDateMillis: Long = 0L
    private var selectedFrequency: String = ""

    private var nameInput: EditText? = null
    private var doseInput: EditText? = null
    private var doseUnitSpinner: Spinner? = null
    private var routeSpinner: Spinner? = null
    private var instructionsInput: EditText? = null
    private var startDateButton: TextView? = null
    private var endDateButton: TextView? = null
    private var chipGroupFrequency: ChipGroup? = null
    private var durationPill: LinearLayout? = null
    private var durationText: TextView? = null
    private var durationDetailText: TextView? = null
    private var saveButton: MaterialButton? = null

    private val labelScanner = MedicationLabelScanner()
    private var pendingLabelFile: java.io.File? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(requireContext(), R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
            }
        }

    private val scanLabelLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingLabelFile
            pendingLabelFile = null
            if (!success || file == null || !file.exists() || file.length() == 0L) {
                Log.d("MedScan", "Scan cancelled or empty capture (success=$success)")
                return@registerForActivityResult
            }
            val bitmap = decodeSampledLabel(file, maxDim = 2048)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "Could not decode photo", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            Log.d("MedScan", "Captured ${file.length()} bytes, decoded ${bitmap.width}x${bitmap.height}")
            runLabelOcr(bitmap)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_FosterConnect)
        fosterCaseId = requireArguments().getString(ARG_FOSTER_CASE_ID)!!
        patientName = requireArguments().getString(ARG_PATIENT_NAME)
        animalNumber = requireArguments().getString(ARG_ANIMAL_NUMBER)
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startDateMillis = savedInstanceState?.getLong(STATE_START_DATE)
            ?: todayCal.timeInMillis
        endDateMillis = savedInstanceState?.getLong(STATE_END_DATE)
            ?: Calendar.getInstance().apply {
                timeInMillis = todayCal.timeInMillis
                add(Calendar.DAY_OF_YEAR, 7)
            }.timeInMillis
        selectedFrequency = savedInstanceState?.getString(STATE_FREQUENCY) ?: ""
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_START_DATE, startDateMillis)
        outState.putLong(STATE_END_DATE, endDateMillis)
        outState.putString(STATE_FREQUENCY, selectedFrequency)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_add_medication, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nameInput = view.findViewById(R.id.input_med_name)
        doseInput = view.findViewById(R.id.input_dose)
        doseUnitSpinner = view.findViewById(R.id.spinner_dose_unit)
        routeSpinner = view.findViewById(R.id.spinner_route)
        instructionsInput = view.findViewById(R.id.input_instructions)
        startDateButton = view.findViewById(R.id.button_start_date)
        endDateButton = view.findViewById(R.id.button_end_date)
        chipGroupFrequency = view.findViewById(R.id.chip_group_frequency)
        durationPill = view.findViewById(R.id.pill_duration)
        durationText = view.findViewById(R.id.text_duration)
        durationDetailText = view.findViewById(R.id.text_duration_detail)
        saveButton = view.findViewById(R.id.button_save)

        view.findViewById<ImageButton>(R.id.button_close).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.button_cancel).setOnClickListener { dismiss() }
        view.findViewById<LinearLayout>(R.id.banner_scan).setOnClickListener { ensureCameraPermissionAndScan() }

        patientName?.let { name ->
            val label = if (!animalNumber.isNullOrBlank()) "$name · $animalNumber" else name
            view.findViewById<TextView>(R.id.text_patient_name)?.text = label
        }

        setupSpinners()
        setupFrequencyChips()

        if (savedInstanceState == null) {
            requireArguments().getString(ARG_PREFILL_NAME)?.let { nameInput?.setText(it) }
            requireArguments().getString(ARG_PREFILL_INSTRUCTIONS)?.let { instructionsInput?.setText(it) }
        }

        updateStartDateLabel()
        updateEndDateLabel()
        updateDurationPill()

        startDateButton?.setOnClickListener { showDatePicker(isStart = true) }
        endDateButton?.setOnClickListener { showDatePicker(isStart = false) }
        saveButton?.setOnClickListener { save() }

        nameInput?.requestFocus()
    }

    override fun onDestroyView() {
        nameInput = null
        doseInput = null
        doseUnitSpinner = null
        routeSpinner = null
        instructionsInput = null
        startDateButton = null
        endDateButton = null
        chipGroupFrequency = null
        durationPill = null
        durationText = null
        durationDetailText = null
        saveButton = null
        super.onDestroyView()
    }

    private fun setupSpinners() {
        val doseUnits = listOf("Unit", "ml", "cc", "mg", "tablets", "drops")
        val routes = listOf("Route", "Oral", "Topical", "Injection", "Subcutaneous", "Ophthalmic")

        doseUnitSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            doseUnits
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        routeSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            routes
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupFrequencyChips() {
        val freqOptions = listOf("Once", "Daily", "Twice daily", "Every 48h", "Every 72h", "Weekly", "As needed")
        val group = chipGroupFrequency ?: return
        val ctx = requireContext()
        val sage = ContextCompat.getColor(ctx, R.color.clinical_sage)
        val white = ContextCompat.getColor(ctx, R.color.white)
        val inkSoft = ContextCompat.getColor(ctx, R.color.clinical_ink_soft)
        val line = ContextCompat.getColor(ctx, R.color.clinical_line)

        for (opt in freqOptions) {
            val chip = Chip(ctx).apply {
                text = opt
                isCheckable = true
                isCheckedIconVisible = false
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(4f * resources.displayMetrics.density).build()
                chipStrokeWidth = resources.displayMetrics.density
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.03f
                chipStartPadding = 10f * resources.displayMetrics.density
                chipEndPadding = 10f * resources.displayMetrics.density
                chipMinHeight = 28f * resources.displayMetrics.density
                updateChipStyle(this, opt == selectedFrequency, sage, white, inkSoft, line)
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                selectedFrequency = if (isChecked) opt else ""
                for (i in 0 until group.childCount) {
                    val c = group.getChildAt(i) as? Chip ?: continue
                    updateChipStyle(c, c.isChecked, sage, white, inkSoft, line)
                }
                updateDurationPill()
            }
            group.addView(chip)
        }
    }

    private fun updateChipStyle(chip: Chip, selected: Boolean, sage: Int, white: Int, inkSoft: Int, line: Int) {
        if (selected) {
            chip.chipBackgroundColor = ColorStateList.valueOf(sage)
            chip.setTextColor(white)
            chip.chipStrokeColor = ColorStateList.valueOf(sage)
        } else {
            chip.chipBackgroundColor = ColorStateList.valueOf(white)
            chip.setTextColor(inkSoft)
            chip.chipStrokeColor = ColorStateList.valueOf(line)
        }
    }

    private fun showDatePicker(isStart: Boolean) {
        val millis = if (isStart) startDateMillis else endDateMillis
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (isStart) {
                    startDateMillis = selected.timeInMillis
                    updateStartDateLabel()
                } else {
                    endDateMillis = selected.timeInMillis
                    updateEndDateLabel()
                }
                updateDurationPill()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateStartDateLabel() {
        startDateButton?.text = dateFormat.format(Date(startDateMillis))
    }

    private fun updateEndDateLabel() {
        endDateButton?.text = dateFormat.format(Date(endDateMillis))
    }

    private fun updateDurationPill() {
        val diffMillis = endDateMillis - startDateMillis
        if (diffMillis <= 0) {
            durationPill?.visibility = View.GONE
            return
        }
        val days = TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()
        durationPill?.visibility = View.VISIBLE
        durationText?.text = getString(R.string.med_duration_format, days)
        val freqLabel = selectedFrequency.ifEmpty { getString(R.string.med_freq_not_set) }
        durationDetailText?.text = getString(
            R.string.med_duration_detail_format,
            freqLabel,
            dateFormat.format(Date(endDateMillis))
        )
    }

    private fun ensureCameraPermissionAndScan() {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val dir = java.io.File(ctx.cacheDir, "scans").apply { mkdirs() }
        val file = java.io.File(dir, "label_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingLabelFile = file
        scanLabelLauncher.launch(uri)
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
                if (view != null) {
                    parsed.name?.let { nameInput?.setText(it) }
                    parsed.instructions?.let { instructionsInput?.setText(it) }
                }
            },
            onError = { e ->
                Log.e("MedScan", "OCR failed", e)
                if (view != null) {
                    Toast.makeText(requireContext(), "Could not read label", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun save() {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            nameInput?.error = getString(R.string.med_name_hint)
            return
        }
        val dose = doseInput?.text?.toString()?.trim().orEmpty()
        val doseUnit = doseUnitSpinner?.selectedItem?.toString()?.let {
            if (it == "Unit") "" else it
        }.orEmpty()
        val route = routeSpinner?.selectedItem?.toString()?.let {
            if (it == "Route") "" else it
        }.orEmpty()
        val instructions = instructionsInput?.text?.toString()?.trim().orEmpty()

        val medication = Medication(
            name = name,
            dose = dose,
            doseUnit = doseUnit,
            route = route,
            frequency = selectedFrequency,
            instructions = instructions,
            startDateMillis = startDateMillis,
            endDateMillis = endDateMillis
        )
        KittenRepository.addMedication(fosterCaseId, medication)
        setFragmentResult(RESULT_KEY, bundleOf())
        dismiss()
    }

    companion object {
        const val RESULT_KEY = "add_medication_result"
        private const val ARG_FOSTER_CASE_ID = "foster_case_id"
        private const val ARG_PATIENT_NAME = "patient_name"
        private const val ARG_ANIMAL_NUMBER = "animal_number"
        private const val ARG_PREFILL_NAME = "prefill_name"
        private const val ARG_PREFILL_INSTRUCTIONS = "prefill_instructions"
        private const val STATE_START_DATE = "start_date_millis"
        private const val STATE_END_DATE = "end_date_millis"
        private const val STATE_FREQUENCY = "frequency"

        fun newInstance(
            fosterCaseId: String,
            patientName: String? = null,
            animalNumber: String? = null,
            prefillName: String? = null,
            prefillInstructions: String? = null
        ) = AddMedicationDialogFragment().apply {
            arguments = bundleOf(
                ARG_FOSTER_CASE_ID to fosterCaseId,
                ARG_PATIENT_NAME to patientName,
                ARG_ANIMAL_NUMBER to animalNumber,
                ARG_PREFILL_NAME to prefillName,
                ARG_PREFILL_INSTRUCTIONS to prefillInstructions,
            )
        }
    }
}
