package com.example.fosterconnect.foster

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.foster.scan.AgeUnit
import com.example.fosterconnect.foster.scan.FosterAgreementParser
import com.example.fosterconnect.foster.scan.ParsedAge
import com.example.fosterconnect.medication.scan.MedicationLabelScanner
import com.google.android.material.datepicker.MaterialDatePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AddLitterFragment : Fragment() {

    private enum class WeightUnit { POUNDS, GRAMS }

    private data class KittenData(
        var name: String = "",
        var animalId: String = "",
        var color: CoatColor? = null,
        var sex: Sex? = null,
        var age: Int? = null,
        var ageUnit: AgeUnit = AgeUnit.WEEKS,
        var weight: Float? = null,
        var weightUnit: WeightUnit = WeightUnit.POUNDS,
        var weightDateMillis: Long? = null,
        var breed: Breed? = null
    )

    private val kittens = mutableListOf(KittenData(), KittenData())
    private var intakeDateMillis: Long? = null

    private lateinit var kittenCardsContainer: LinearLayout
    private lateinit var ghostAddCard: LinearLayout
    private lateinit var scrollKittens: android.widget.HorizontalScrollView
    private lateinit var textKittenCount: TextView
    private lateinit var buttonCreate: com.google.android.material.button.MaterialButton
    private lateinit var inputLitterName: EditText
    private lateinit var inputIntakeDate: TextView

    private val scanner = MedicationLabelScanner()
    private var pendingScanUri: Uri? = null
    private var pendingScanFile: File? = null

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCameraCapture()
            else Toast.makeText(requireContext(), "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    private val scanAgreementLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingScanFile
            pendingScanFile = null
            pendingScanUri = null
            if (!success || file == null || !file.exists() || file.length() == 0L) return@registerForActivityResult
            val bitmap = decodeSampled(file, 2048) ?: return@registerForActivityResult
            scanAgreementBitmap(bitmap)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_add_litter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        kittenCardsContainer = view.findViewById(R.id.layout_kitten_cards)
        ghostAddCard = view.findViewById(R.id.card_ghost_add)
        scrollKittens = view.findViewById(R.id.scroll_kittens)
        textKittenCount = view.findViewById(R.id.text_kitten_count)
        buttonCreate = view.findViewById(R.id.button_create)
        inputLitterName = view.findViewById(R.id.input_litter_name)
        inputIntakeDate = view.findViewById(R.id.input_intake_date)

        inputIntakeDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Intake date")
                .apply { intakeDateMillis?.let { setSelection(it) } }
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                intakeDateMillis = millis
                val fmt = SimpleDateFormat("MM / dd / yy", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                inputIntakeDate.text = fmt.format(Date(millis))
            }
            picker.show(parentFragmentManager, "intakeDate")
        }

        inputLitterName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSummary() }
        })

        view.findViewById<View>(R.id.button_add_kitten).setOnClickListener { addKitten() }
        ghostAddCard.setOnClickListener { addKitten() }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_cancel).setOnClickListener {
            findNavController().popBackStack()
        }

        buttonCreate.setOnClickListener { createLitter() }

        view.findViewById<View>(R.id.banner_scan).setOnClickListener {
            ensureCameraPermissionAndScan()
        }

        rebuildKittenCards()
        updateSummary()
    }

    private fun addKitten() {
        kittens.add(KittenData())
        rebuildKittenCards()
        updateSummary()
        scrollKittens.post {
            scrollKittens.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun removeKitten(index: Int) {
        if (kittens.size <= 1) return
        kittens.removeAt(index)
        rebuildKittenCards()
        updateSummary()
    }

    private fun rebuildKittenCards() {
        val ghostIndex = kittenCardsContainer.indexOfChild(ghostAddCard)
        for (i in kittenCardsContainer.childCount - 1 downTo 0) {
            if (i != ghostIndex) kittenCardsContainer.removeViewAt(i)
        }

        val inflater = LayoutInflater.from(requireContext())
        val colorLabels = CoatColor.values().map { it.display }
        val unitLabels = AgeUnit.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }

        for ((index, kitten) in kittens.withIndex()) {
            val card = inflater.inflate(R.layout.item_kitten_intake_card, kittenCardsContainer, false)
            bindKittenCard(card, index, kitten, colorLabels, unitLabels)
            kittenCardsContainer.addView(card, kittenCardsContainer.childCount - 1)
        }
    }

    private fun bindKittenCard(
        card: View,
        index: Int,
        kitten: KittenData,
        colorLabels: List<String>,
        unitLabels: List<String>
    ) {
        val ctx = requireContext()

        card.findViewById<TextView>(R.id.text_kitten_index).text = "KITTEN ${index + 1}"
        val namePreview = card.findViewById<TextView>(R.id.text_kitten_name_preview)
        namePreview.text = kitten.name.ifEmpty { "Unnamed" }
        namePreview.setTextColor(
            ContextCompat.getColor(ctx, if (kitten.name.isNotEmpty()) R.color.clinical_ink else R.color.clinical_ink_muted)
        )

        val avatar = card.findViewById<ImageView>(R.id.image_kitten_avatar)
        val profileRes = (kitten.color ?: CoatColor.CALICO).defaultProfileDrawable(kitten.name)
        avatar.setImageResource(profileRes)

        val removeBtn = card.findViewById<ImageButton>(R.id.button_remove_kitten)
        if (kittens.size > 1) {
            removeBtn.visibility = View.VISIBLE
            removeBtn.setOnClickListener { removeKitten(index) }
        } else {
            removeBtn.visibility = View.GONE
        }

        val nameInput = card.findViewById<EditText>(R.id.input_kitten_name)
        nameInput.setText(kitten.name)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                kitten.name = s?.toString().orEmpty()
                namePreview.text = kitten.name.ifEmpty { "Unnamed" }
                namePreview.setTextColor(
                    ContextCompat.getColor(ctx, if (kitten.name.isNotEmpty()) R.color.clinical_ink else R.color.clinical_ink_muted)
                )
                updateSummary()
            }
        })

        val animalIdInput = card.findViewById<EditText>(R.id.input_kitten_animal_id)
        animalIdInput.setText(kitten.animalId)
        animalIdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                kitten.animalId = s?.toString().orEmpty()
                updateSummary()
            }
        })

        val colorInput = card.findViewById<AutoCompleteTextView>(R.id.input_kitten_color)
        colorInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, colorLabels))
        kitten.color?.let { colorInput.setText(it.display, false) }
        colorInput.setOnItemClickListener { _, _, idx, _ ->
            kitten.color = CoatColor.values()[idx]
            avatar.setImageResource(kitten.color!!.defaultProfileDrawable(kitten.name))
        }

        val sexButtons = listOf(
            card.findViewById<TextView>(R.id.btn_sex_male) to Sex.MALE,
            card.findViewById<TextView>(R.id.btn_sex_female) to Sex.FEMALE,
            card.findViewById<TextView>(R.id.btn_sex_unknown) to null
        )
        fun updateSexButtons() {
            for ((btn, sex) in sexButtons) {
                val selected = kitten.sex == sex
                btn.setBackgroundResource(if (selected) R.drawable.bg_sex_selected else 0)
                btn.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.clinical_sage else R.color.clinical_ink_muted))
                btn.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        updateSexButtons()
        for ((btn, sex) in sexButtons) {
            btn.setOnClickListener {
                kitten.sex = sex
                updateSexButtons()
                updateSummary()
            }
        }

        val ageInput = card.findViewById<EditText>(R.id.input_kitten_age)
        kitten.age?.let { ageInput.setText(it.toString()) }
        ageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                kitten.age = s?.toString()?.toIntOrNull()
            }
        })

        val ageUnitInput = card.findViewById<AutoCompleteTextView>(R.id.input_kitten_age_unit)
        ageUnitInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, unitLabels))
        ageUnitInput.setText(unitLabels[kitten.ageUnit.ordinal], false)
        ageUnitInput.setOnItemClickListener { _, _, idx, _ ->
            kitten.ageUnit = AgeUnit.values()[idx]
        }

        val weightInput = card.findViewById<EditText>(R.id.input_kitten_weight)
        kitten.weight?.let { weightInput.setText(it.toString()) }
        weightInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                kitten.weight = s?.toString()?.toFloatOrNull()
            }
        })

        val weightDateBtn = card.findViewById<TextView>(R.id.input_kitten_weight_date)
        kitten.weightDateMillis?.let { setDateField(weightDateBtn, it) }
        weightDateBtn.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Weight date")
                .apply { kitten.weightDateMillis?.let { setSelection(it) } }
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                kitten.weightDateMillis = millis
                setDateField(weightDateBtn, millis)
            }
            picker.show(parentFragmentManager, "weightDate_$index")
        }

        val weightUnitButtons = listOf(
            card.findViewById<TextView>(R.id.btn_unit_lbs) to WeightUnit.POUNDS,
            card.findViewById<TextView>(R.id.btn_unit_grams) to WeightUnit.GRAMS
        )
        fun updateWeightUnitButtons() {
            for ((btn, unit) in weightUnitButtons) {
                val selected = kitten.weightUnit == unit
                btn.setBackgroundResource(if (selected) R.drawable.bg_sex_selected else 0)
                btn.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.clinical_sage else R.color.clinical_ink_muted))
                btn.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        updateWeightUnitButtons()
        for ((btn, unit) in weightUnitButtons) {
            btn.setOnClickListener {
                kitten.weightUnit = unit
                updateWeightUnitButtons()
            }
        }
    }

    private fun setDateField(view: TextView, millis: Long) {
        val fmt = SimpleDateFormat("MM/dd/yy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        view.text = fmt.format(Date(millis))
    }

    private fun updateSummary() {
        val count = kittens.size
        val named = kittens.count { it.name.isNotBlank() }

        val litterName = inputLitterName.text?.toString()?.trim().orEmpty()
        val canCreate = litterName.isNotEmpty() && named > 0
        buttonCreate.isEnabled = canCreate
        buttonCreate.text = "Create $count ${if (count == 1) "Kitten" else "Kittens"}"
    }

    private fun createLitter() {
        val ctx = requireContext()
        val litterName = inputLitterName.text?.toString()?.trim().orEmpty()
        if (litterName.isEmpty()) {
            Toast.makeText(ctx, "Litter name is required", Toast.LENGTH_SHORT).show()
            return
        }

        val namedKittens = kittens.filter { it.name.isNotBlank() }
        if (namedKittens.isEmpty()) {
            Toast.makeText(ctx, "At least one kitten must be named", Toast.LENGTH_SHORT).show()
            return
        }

        val intakeMillis = intakeDateMillis ?: System.currentTimeMillis()

        for (kitten in namedKittens) {
            val estimatedBirthday = kitten.age?.let {
                ParsedAge(it, kitten.ageUnit).estimatedBirthdayMillis(intakeMillis)
            }
            val weightGrams = kitten.weight?.let { w ->
                when (kitten.weightUnit) {
                    WeightUnit.GRAMS -> w
                    WeightUnit.POUNDS -> w * 453.592f
                }
            }
            KittenRepository.createFosterCase(
                externalId = kitten.animalId,
                name = kitten.name.trim(),
                litterName = litterName,
                breed = kitten.breed ?: Breed.DOMESTIC_SHORT_HAIR,
                color = kitten.color ?: CoatColor.BLACK,
                sex = kitten.sex ?: Sex.FEMALE,
                intakeDateMillis = intakeMillis,
                estimatedBirthdayMillis = estimatedBirthday,
                initialWeightGrams = weightGrams,
                initialWeightDateMillis = kitten.weightDateMillis
            )
        }

        val count = namedKittens.size
        Toast.makeText(ctx, "Created $count ${if (count == 1) "kitten" else "kittens"}", Toast.LENGTH_SHORT).show()
        findNavController().popBackStack()
    }

    private fun ensureCameraPermissionAndScan() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCameraCapture()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    private fun scanAgreementBitmap(bitmap: Bitmap) {
        scanner.scan(
            bitmap,
            onResult = { rawText ->
                val parsed = FosterAgreementParser.parse(rawText)
                if (view != null) {
                    parsed.name?.let { name ->
                        if (kittens.isNotEmpty()) kittens[0].name = name
                    }
                    parsed.animalExternalId?.let { id ->
                        if (kittens.isNotEmpty()) kittens[0].animalId = id
                    }
                    parsed.color?.let { color ->
                        if (kittens.isNotEmpty()) kittens[0].color = color
                    }
                    parsed.sex?.let { sex ->
                        if (kittens.isNotEmpty()) kittens[0].sex = sex
                    }
                    parsed.age?.let { age ->
                        if (kittens.isNotEmpty()) {
                            kittens[0].age = age.value
                            kittens[0].ageUnit = age.unit
                        }
                    }
                    parsed.breed?.let { breed ->
                        if (kittens.isNotEmpty()) kittens[0].breed = breed
                    }
                    parsed.intakeDateMillis?.let { millis ->
                        intakeDateMillis = millis
                        val fmt = SimpleDateFormat("MM / dd / yy", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        inputIntakeDate.text = fmt.format(Date(millis))
                    }
                    rebuildKittenCards()
                    updateSummary()
                    Toast.makeText(requireContext(), "Agreement scanned", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { e ->
                Log.e(TAG, "OCR failed", e)
                if (view != null) {
                    Toast.makeText(requireContext(), "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val TAG = "AddLitter"
    }
}
