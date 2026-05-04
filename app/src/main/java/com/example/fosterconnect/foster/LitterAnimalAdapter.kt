package com.example.fosterconnect.foster

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.foster.scan.AgeUnit
import com.example.fosterconnect.foster.scan.ParsedAge
import com.example.fosterconnect.foster.scan.ParsedFosterAgreement
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LitterAnimalAdapter(
    private val animals: MutableList<ParsedFosterAgreement>,
    private val fragmentManager: FragmentManager
) : RecyclerView.Adapter<LitterAnimalAdapter.ViewHolder>() {

    private val breedLabels = Breed.values().map { it.display }
    private val colorLabels = CoatColor.values().map { it.display }
    private val sexLabels = Sex.values().map { it.display }
    private val unitLabels = AgeUnit.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: android.widget.TextView = view.findViewById(R.id.text_animal_label)
        val nameInput: TextInputEditText = view.findViewById(R.id.input_name)
        val idInput: TextInputEditText = view.findViewById(R.id.input_external_id)
        val breedInput: AutoCompleteTextView = view.findViewById(R.id.input_breed)
        val colorInput: AutoCompleteTextView = view.findViewById(R.id.input_color)
        val sexInput: AutoCompleteTextView = view.findViewById(R.id.input_sex)
        val ageValueInput: TextInputEditText = view.findViewById(R.id.input_age_value)
        val ageUnitInput: AutoCompleteTextView = view.findViewById(R.id.input_age_unit)
        val weightInput: TextInputEditText = view.findViewById(R.id.input_weight)
        val weightDateInput: TextInputEditText = view.findViewById(R.id.input_weight_date)
        val watchers = mutableListOf<Pair<TextInputEditText, TextWatcher>>()

        fun clearWatchers() {
            watchers.forEach { (input, watcher) -> input.removeTextChangedListener(watcher) }
            watchers.clear()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_litter_animal_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.clearWatchers()
        val animal = animals[position]
        val ctx = holder.itemView.context

        holder.label.text = "Animal ${('A' + position)}"
        holder.nameInput.setText(animal.name.orEmpty())
        holder.idInput.setText(animal.animalExternalId.orEmpty())

        holder.breedInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, breedLabels))
        holder.colorInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, colorLabels))
        holder.sexInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sexLabels))
        holder.ageUnitInput.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, unitLabels))

        animal.breed?.let { holder.breedInput.setText(it.display, false) }
        animal.color?.let { holder.colorInput.setText(it.display, false) }
        animal.sex?.let { holder.sexInput.setText(it.display, false) }
        animal.age?.let {
            holder.ageValueInput.setText(it.value.toString())
            holder.ageUnitInput.setText(unitLabels[it.unit.ordinal], false)
        }
        animal.lastWeightGrams?.let { holder.weightInput.setText("%.0f".format(it)) }
        animal.lastWeightDateMillis?.let { setDateField(holder.weightDateInput, it) }

        holder.weightDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Weight date")
                .apply { animal.lastWeightDateMillis?.let { setSelection(it) } }
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                animals[position] = animals[position].copy(lastWeightDateMillis = millis)
                setDateField(holder.weightDateInput, millis)
            }
            picker.show(fragmentManager, "weightDate_$position")
        }

        fun addWatcher(input: TextInputEditText, onChange: (String) -> Unit) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
            }
            input.addTextChangedListener(watcher)
            holder.watchers.add(input to watcher)
        }

        addWatcher(holder.nameInput) { text ->
            animals[position] = animals[position].copy(name = text.takeIf { it.isNotEmpty() })
        }
        addWatcher(holder.idInput) { text ->
            animals[position] = animals[position].copy(animalExternalId = text.takeIf { it.isNotEmpty() })
        }
        addWatcher(holder.weightInput) { text ->
            animals[position] = animals[position].copy(lastWeightGrams = text.toFloatOrNull())
        }
        addWatcher(holder.ageValueInput) { text ->
            val ageVal = text.toIntOrNull()
            val currentUnit = animals[position].age?.unit ?: AgeUnit.WEEKS
            animals[position] = animals[position].copy(
                age = ageVal?.let { ParsedAge(it, currentUnit) }
            )
        }

        holder.breedInput.setOnItemClickListener { _, _, idx, _ ->
            animals[position] = animals[position].copy(breed = Breed.values()[idx])
        }
        holder.colorInput.setOnItemClickListener { _, _, idx, _ ->
            animals[position] = animals[position].copy(color = CoatColor.values()[idx])
        }
        holder.sexInput.setOnItemClickListener { _, _, idx, _ ->
            animals[position] = animals[position].copy(sex = Sex.values()[idx])
        }
        holder.ageUnitInput.setOnItemClickListener { _, _, idx, _ ->
            val currentAge = animals[position].age?.value ?: return@setOnItemClickListener
            animals[position] = animals[position].copy(age = ParsedAge(currentAge, AgeUnit.values()[idx]))
        }
    }

    override fun getItemCount(): Int = animals.size

    private fun setDateField(input: TextInputEditText, millis: Long) {
        val utcFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        input.setText(utcFormat.format(Date(millis)))
    }
}
