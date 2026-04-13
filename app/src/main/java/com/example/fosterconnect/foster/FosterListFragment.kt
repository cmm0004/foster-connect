package com.example.fosterconnect.foster

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
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
                    binding.recyclerKittens.adapter = KittenAdapter(fosters) { fosterCase ->
                        findNavController().navigate(
                            R.id.action_FosterList_to_KittenDetail,
                            bundleOf("fosterCaseId" to fosterCase.fosterCaseId)
                        )
                    }
                }
            }
        }

        binding.buttonNewFoster.setOnClickListener { scanTestAgreement() }
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
        scanner.scan(
            bitmap,
            onResult = { rawText ->
                val parsed = FosterAgreementParser.parse(rawText)
                Log.d(TAG, parsed.summary())
                if (_binding != null) showReviewDialog(parsed)
            },
            onError = { e ->
                Log.e(TAG, "OCR failed", e)
                if (_binding != null) {
                    Toast.makeText(requireContext(), "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showReviewDialog(parsed: ParsedFosterAgreement) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_review_foster_agreement, null)

        val nameInput = view.findViewById<TextInputEditText>(R.id.input_name)
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

        val breedLabels = Breed.values().map { it.display }
        val colorLabels = CoatColor.values().map { it.display }
        val sexLabels = Sex.values().map { it.display }
        val unitLabels = AgeUnit.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }

        breedInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, breedLabels))
        colorInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, colorLabels))
        sexInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sexLabels))
        ageUnitInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, unitLabels))

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

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Review new foster")
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

                KittenRepository.createFosterCase(
                    externalId = idInput.text?.toString()?.trim().orEmpty(),
                    name = name,
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
            .show()
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
