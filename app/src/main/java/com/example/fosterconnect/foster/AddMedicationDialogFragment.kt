package com.example.fosterconnect.foster

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.medication.Medication
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

    private var nameInput: TextInputEditText? = null
    private var instructionsInput: TextInputEditText? = null
    private var startDateButton: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_FosterConnect)
        fosterCaseId = requireArguments().getString(ARG_FOSTER_CASE_ID)!!
        startDateMillis = savedInstanceState?.getLong(STATE_START_DATE)
            ?: Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_START_DATE, startDateMillis)
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

        toolbar.setNavigationOnClickListener { dismiss() }

        if (savedInstanceState == null) {
            requireArguments().getString(ARG_PREFILL_NAME)?.let { nameInput?.setText(it) }
            requireArguments().getString(ARG_PREFILL_INSTRUCTIONS)?.let { instructionsInput?.setText(it) }
        }

        updateStartDateLabel()
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

        saveButton.setOnClickListener { save() }

        nameInput?.requestFocus()
    }

    override fun onDestroyView() {
        nameInput = null
        instructionsInput = null
        startDateButton = null
        super.onDestroyView()
    }

    private fun updateStartDateLabel() {
        startDateButton?.text =
            getString(R.string.start_date_format, dateFormat.format(Date(startDateMillis)))
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
            startDateMillis = startDateMillis
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
