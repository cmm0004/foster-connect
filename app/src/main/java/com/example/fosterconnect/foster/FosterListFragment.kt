package com.example.fosterconnect.foster

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentFosterListBinding
import com.example.fosterconnect.foster.scan.AgeUnit
import com.example.fosterconnect.foster.scan.FosterAgreementParser
import com.example.fosterconnect.foster.scan.ParsedAge
import com.example.fosterconnect.foster.scan.ParsedFosterAgreement
import com.example.fosterconnect.medication.scan.MedicationLabelScanner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class FosterListFragment : Fragment() {

    private var _binding: FragmentFosterListBinding? = null
    private val binding get() = _binding!!

    private val scanner = MedicationLabelScanner()
    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)

    private var pendingScanUri: Uri? = null
    private var pendingScanFile: File? = null

    // Dialog field references for filling after OCR scan
    private var dialogNameInput: TextInputEditText? = null
    private var dialogLitterNameInput: TextInputEditText? = null
    private var dialogIdInput: TextInputEditText? = null
    private var dialogBreedInput: AutoCompleteTextView? = null
    private var dialogColorInput: AutoCompleteTextView? = null
    private var dialogSexInput: AutoCompleteTextView? = null
    private var dialogAlteredSwitch: MaterialSwitch? = null
    private var dialogAgeValueInput: TextInputEditText? = null
    private var dialogAgeUnitInput: AutoCompleteTextView? = null
    private var dialogIntakeDateInput: TextInputEditText? = null
    private var dialogWeightInput: TextInputEditText? = null
    private var dialogWeightDateInput: TextInputEditText? = null

    private var pendingPhotoUri: Uri? = null
    private var pendingPhotoCaseId: String? = null
    private var pendingPermissionCaseId: String? = null

    private val photoCaptureLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingPhotoUri
            val caseId = pendingPhotoCaseId
            pendingPhotoUri = null
            pendingPhotoCaseId = null
            if (uri == null || caseId == null) return@registerForActivityResult
            if (success) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val added = KittenRepository.addPhoto(caseId, uri.toString())
                    if (!added) {
                        requireContext().contentResolver.delete(uri, null, null)
                        Toast.makeText(requireContext(), R.string.photo_max_reached, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                requireContext().contentResolver.delete(uri, null, null)
            }
        }

    private val photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val caseId = pendingPhotoCaseId
            pendingPhotoCaseId = null
            if (uri == null || caseId == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "takePersistableUriPermission failed", e)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val added = KittenRepository.addPhoto(caseId, uri.toString())
                if (!added) {
                    Toast.makeText(requireContext(), R.string.photo_max_reached, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private var pendingPermissionScan: Boolean = false

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val caseId = pendingPermissionCaseId
            val isScan = pendingPermissionScan
            pendingPermissionCaseId = null
            pendingPermissionScan = false

            if (granted) {
                if (caseId != null) {
                    launchPhotoCapture(caseId)
                } else if (isScan) {
                    launchCameraCapture()
                }
            } else {
                Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

    private val scanAgreementLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingScanFile
            pendingScanFile = null
            pendingScanUri = null
            if (!success || file == null || !file.exists() || file.length() == 0L) {
                Log.d(TAG, "Scan cancelled or empty capture (success=$success, file=$file)")
                return@registerForActivityResult
            }
            val bitmap = decodeSampled(file, maxDim = 2048)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "Could not decode photo", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            Log.d(TAG, "Captured ${file.length()} bytes, decoded ${bitmap.width}x${bitmap.height}")
            scanAgreementBitmap(bitmap)
        }

    private fun launchCameraCapture() {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, "scans").apply { mkdirs() }
        val file = File(dir, "foster_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingScanFile = file
        pendingScanUri = uri
        scanAgreementLauncher.launch(uri)
    }

    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFosterListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerKittens.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.activeFostersFlow.collect { fosters ->
                    binding.recyclerKittens.adapter = KittenAdapter(
                        fosterCases = fosters,
                        onClick = { fosterCase ->
                            findNavController().navigate(
                                R.id.action_FosterList_to_KittenDetail,
                                bundleOf("fosterCaseId" to fosterCase.fosterCaseId)
                            )
                        },
                        onAddPhoto = { fosterCase -> onAddPhotoClicked(fosterCase) },
                        onViewGallery = { fosterCase ->
                            findNavController().navigate(
                                R.id.action_FosterList_to_Gallery,
                                bundleOf("fosterCaseId" to fosterCase.fosterCaseId)
                            )
                        }
                    )
                }
            }
        }

        binding.buttonNewFoster.setOnClickListener {
            showReviewDialog(null)
        }
        binding.buttonNewFoster.setOnLongClickListener {
            scanTestAgreement()
            true
        }
    }

    private fun scanAgreementBitmap(bitmap: Bitmap) {
        scanner.scan(
            bitmap,
            onResult = { rawText ->
                Log.d(TAG, "---- RAW OCR (${rawText.length} chars, ${rawText.lines().size} lines) ----")
                rawText.lines().forEachIndexed { i, line ->
                    Log.d(TAG, "raw[%02d] |%s|".format(i, line))
                }
                Log.d(TAG, "---- END RAW OCR ----")
                val parsed = FosterAgreementParser.parse(rawText)
                Log.d(TAG, parsed.summary())
                if (_binding != null) fillDialogFromParsed(parsed)
            },
            onError = { e ->
                Log.e(TAG, "OCR failed", e)
                if (_binding != null) {
                    Toast.makeText(requireContext(), "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun fillDialogFromParsed(parsed: ParsedFosterAgreement) {
        val unitLabels = AgeUnit.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }
        dialogNameInput?.setText(parsed.name.orEmpty())
        dialogIdInput?.setText(parsed.animalExternalId.orEmpty())
        parsed.breed?.let { dialogBreedInput?.setText(it.display, false) }
        parsed.color?.let { dialogColorInput?.setText(it.display, false) }
        parsed.sex?.let { dialogSexInput?.setText(it.display, false) }
        if (parsed.isAlteredAtIntake != null) dialogAlteredSwitch?.isChecked = parsed.isAlteredAtIntake
        parsed.age?.let {
            dialogAgeValueInput?.setText(it.value.toString())
            dialogAgeUnitInput?.setText(unitLabels[it.unit.ordinal], false)
        }
        dialogIntakeDateInput?.setText(parsed.intakeDateMillis?.let { dateFormat.format(Date(it)) }.orEmpty())
        parsed.lastWeightGrams?.let { dialogWeightInput?.setText("%.0f".format(it)) }
        dialogWeightDateInput?.setText(parsed.lastWeightDateMillis?.let { dateFormat.format(Date(it)) }.orEmpty())
        Toast.makeText(requireContext(), "Agreement scanned", Toast.LENGTH_SHORT).show()
    }

    private fun scanTestAgreement() {
        val ctx = requireContext()
        val bitmap = try {
            ctx.assets.open("testfosteragreement.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load testfosteragreement.jpg", e)
            Toast.makeText(ctx, "Failed to load test image", Toast.LENGTH_SHORT).show()
            return
        }
        if (bitmap == null) {
            Toast.makeText(ctx, "Could not decode test image", Toast.LENGTH_SHORT).show()
            return
        }
        scanAgreementBitmap(bitmap)
    }

    private fun showReviewDialog(parsed: ParsedFosterAgreement?) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_review_foster_agreement, null)

        val scanButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_scan_agreement)
        val nameInput = view.findViewById<TextInputEditText>(R.id.input_name)
        val litterNameInput = view.findViewById<TextInputEditText>(R.id.input_litter_name)
        val idInput = view.findViewById<TextInputEditText>(R.id.input_external_id)
        val breedInput = view.findViewById<AutoCompleteTextView>(R.id.input_breed)
        val colorInput = view.findViewById<AutoCompleteTextView>(R.id.input_color)
        val sexInput = view.findViewById<AutoCompleteTextView>(R.id.input_sex)
        val alteredSwitch = view.findViewById<MaterialSwitch>(R.id.switch_altered)
        val ageValueInput = view.findViewById<TextInputEditText>(R.id.input_age_value)
        val ageUnitInput = view.findViewById<AutoCompleteTextView>(R.id.input_age_unit)
        val intakeDateInput = view.findViewById<TextInputEditText>(R.id.input_intake_date)
        val weightInput = view.findViewById<TextInputEditText>(R.id.input_weight)
        val weightDateInput = view.findViewById<TextInputEditText>(R.id.input_weight_date)

        // Store references so OCR callback can fill them
        dialogNameInput = nameInput
        dialogLitterNameInput = litterNameInput
        dialogIdInput = idInput
        dialogBreedInput = breedInput
        dialogColorInput = colorInput
        dialogSexInput = sexInput
        dialogAlteredSwitch = alteredSwitch
        dialogAgeValueInput = ageValueInput
        dialogAgeUnitInput = ageUnitInput
        dialogIntakeDateInput = intakeDateInput
        dialogWeightInput = weightInput
        dialogWeightDateInput = weightDateInput

        val breedLabels = Breed.values().map { it.display }
        val colorLabels = CoatColor.values().map { it.display }
        val sexLabels = Sex.values().map { it.display }
        val unitLabels = AgeUnit.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }

        breedInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, breedLabels))
        colorInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, colorLabels))
        sexInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sexLabels))
        ageUnitInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, unitLabels))

        // Pre-fill if parsed data provided
        if (parsed != null) {
            nameInput.setText(parsed.name.orEmpty())
            idInput.setText(parsed.animalExternalId.orEmpty())
            parsed.breed?.let { breedInput.setText(it.display, false) }
            parsed.color?.let { colorInput.setText(it.display, false) }
            parsed.sex?.let { sexInput.setText(it.display, false) }
            alteredSwitch.isChecked = parsed.isAlteredAtIntake == true
            parsed.age?.let {
                ageValueInput.setText(it.value.toString())
                ageUnitInput.setText(unitLabels[it.unit.ordinal], false)
            }
            intakeDateInput.setText(parsed.intakeDateMillis?.let { dateFormat.format(Date(it)) }.orEmpty())
            parsed.lastWeightGrams?.let { weightInput.setText("%.0f".format(it)) }
            weightDateInput.setText(parsed.lastWeightDateMillis?.let { dateFormat.format(Date(it)) }.orEmpty())
        }

        scanButton.setOnClickListener {
            ensureCameraPermissionAndScan()
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("New foster")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val breed = breedLabels.indexOf(breedInput.text?.toString())
                    .takeIf { it >= 0 }?.let { Breed.values()[it] } ?: Breed.DOMESTIC_SHORT_HAIR
                val color = colorLabels.indexOf(colorInput.text?.toString())
                    .takeIf { it >= 0 }?.let { CoatColor.values()[it] } ?: CoatColor.BLACK
                val sex = sexLabels.indexOf(sexInput.text?.toString())
                    .takeIf { it >= 0 }?.let { Sex.values()[it] } ?: Sex.FEMALE
                val intakeMillis = parseDateInput(intakeDateInput.text?.toString()) ?: System.currentTimeMillis()
                val ageValue = ageValueInput.text?.toString()?.toIntOrNull()
                val unitIdx = unitLabels.indexOf(ageUnitInput.text?.toString()).takeIf { it >= 0 }
                val estimatedBirthday = if (ageValue != null && unitIdx != null) {
                    ParsedAge(ageValue, AgeUnit.values()[unitIdx]).estimatedBirthdayMillis(intakeMillis)
                } else null
                val weightGrams = weightInput.text?.toString()?.toFloatOrNull()
                val weightDate = parseDateInput(weightDateInput.text?.toString())

                val litterName = litterNameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                KittenRepository.createFosterCase(
                    externalId = idInput.text?.toString()?.trim().orEmpty(),
                    name = name,
                    litterName = litterName,
                    breed = breed,
                    color = color,
                    sex = sex,
                    isAlteredAtIntake = alteredSwitch.isChecked,
                    intakeDateMillis = intakeMillis,
                    estimatedBirthdayMillis = estimatedBirthday,
                    initialWeightGrams = weightGrams,
                    initialWeightDateMillis = weightDate
                )
                Toast.makeText(ctx, "Foster case created", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                dialogNameInput = null
                dialogLitterNameInput = null
                dialogIdInput = null
                dialogBreedInput = null
                dialogColorInput = null
                dialogSexInput = null
                dialogAlteredSwitch = null
                dialogAgeValueInput = null
                dialogAgeUnitInput = null
                dialogIntakeDateInput = null
                dialogWeightInput = null
                dialogWeightDateInput = null
            }
            .show()
    }

    private fun onAddPhotoClicked(fosterCase: FosterCaseAnimal) {
        if (fosterCase.photos.size >= KittenRepository.MAX_PHOTOS_PER_CASE) {
            Toast.makeText(requireContext(), R.string.photo_max_reached, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.photo_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.photo_source_camera),
                    getString(R.string.photo_source_library)
                )
            ) { _, which ->
                when (which) {
                    0 -> ensureCameraPermissionAndCapture(fosterCase.fosterCaseId)
                    1 -> {
                        pendingPhotoCaseId = fosterCase.fosterCaseId
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                }
            }
            .show()
    }

    private fun ensureCameraPermissionAndScan() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraCapture()
        } else {
            pendingPermissionScan = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ensureCameraPermissionAndCapture(caseId: String) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchPhotoCapture(caseId)
        } else {
            pendingPermissionCaseId = caseId
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchPhotoCapture(caseId: String) {
        val uri = createPhotoUri(caseId)
        if (uri == null) {
            Toast.makeText(requireContext(), "Could not create photo file", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPhotoUri = uri
        pendingPhotoCaseId = caseId
        photoCaptureLauncher.launch(uri)
    }

    private fun createPhotoUri(caseId: String): Uri? {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "foster_${caseId}_${System.currentTimeMillis()}.jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FosterConnect")
        }
        return requireContext().contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
    }

    private fun parseDateInput(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { dateFormat.parse(raw)?.time }.getOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "FosterAgreementOCR"
    }
}
