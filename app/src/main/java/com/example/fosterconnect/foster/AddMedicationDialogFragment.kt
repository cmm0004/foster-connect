package com.example.fosterconnect.foster

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AddMedicationDialogFragment : DialogFragment() {

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

    private lateinit var fosterCaseId: String
    private var startDateMillis: Long = 0L
    private var untilDateMillis: Long = 0L

    private var nameInput: TextInputEditText? = null
    private var instructionsInput: TextInputEditText? = null
    private var startDateButton: MaterialButton? = null
    private var untilDateButton: MaterialButton? = null
    private var scanButton: MaterialButton? = null

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
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startDateMillis = savedInstanceState?.getLong(STATE_START_DATE)
            ?: todayCal.timeInMillis
        untilDateMillis = savedInstanceState?.getLong(STATE_UNTIL_DATE)
            ?: Calendar.getInstance().apply {
                timeInMillis = todayCal.timeInMillis
                add(Calendar.DAY_OF_YEAR, 7)
            }.timeInMillis
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_START_DATE, startDateMillis)
        outState.putLong(STATE_UNTIL_DATE, untilDateMillis)
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

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val saveButton = view.findViewById<MaterialButton>(R.id.button_save)
        nameInput = view.findViewById(R.id.input_med_name)
        instructionsInput = view.findViewById(R.id.input_instructions)
        startDateButton = view.findViewById(R.id.button_start_date)
        untilDateButton = view.findViewById(R.id.button_until_date)
        scanButton = view.findViewById(R.id.button_scan_label)

        toolbar.setNavigationOnClickListener { dismiss() }

        if (savedInstanceState == null) {
            requireArguments().getString(ARG_PREFILL_NAME)?.let { nameInput?.setText(it) }
            requireArguments().getString(ARG_PREFILL_INSTRUCTIONS)?.let { instructionsInput?.setText(it) }
        }

        updateStartDateLabel()
        updateUntilDateLabel()
        startDateButton?.setOnClickListener {
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
        untilDateButton?.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = untilDateMillis }
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    untilDateMillis = selected.timeInMillis
                    updateUntilDateLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        scanButton?.setOnClickListener { ensureCameraPermissionAndScan() }
        saveButton.setOnClickListener { save() }

        nameInput?.requestFocus()
    }

    override fun onDestroyView() {
        nameInput = null
        instructionsInput = null
        startDateButton = null
        untilDateButton = null
        scanButton = null
        super.onDestroyView()
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

    private fun updateStartDateLabel() {
        startDateButton?.text =
            getString(R.string.start_date_format, dateFormat.format(Date(startDateMillis)))
    }

    private fun updateUntilDateLabel() {
        untilDateButton?.text =
            getString(R.string.until_date_format, dateFormat.format(Date(untilDateMillis)))
    }

    private fun save() {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            nameInput?.error = getString(R.string.med_name_hint)
            return
        }
        val instructions = instructionsInput?.text?.toString()?.trim().orEmpty()

        val medication = Medication(
            name = name,
            instructions = instructions,
            startDateMillis = startDateMillis,
            endDateMillis = untilDateMillis
        )
        KittenRepository.addMedication(fosterCaseId, medication)
        setFragmentResult(RESULT_KEY, bundleOf())
        dismiss()
    }

    companion object {
        const val RESULT_KEY = "add_medication_result"
        private const val ARG_FOSTER_CASE_ID = "foster_case_id"
        private const val ARG_PREFILL_NAME = "prefill_name"
        private const val ARG_PREFILL_INSTRUCTIONS = "prefill_instructions"
        private const val STATE_START_DATE = "start_date_millis"
        private const val STATE_UNTIL_DATE = "until_date_millis"

        fun newInstance(
            fosterCaseId: String,
            prefillName: String? = null,
            prefillInstructions: String? = null
        ) = AddMedicationDialogFragment().apply {
            arguments = bundleOf(
                ARG_FOSTER_CASE_ID to fosterCaseId,
                ARG_PREFILL_NAME to prefillName,
                ARG_PREFILL_INSTRUCTIONS to prefillInstructions,
            )
        }
    }
}
