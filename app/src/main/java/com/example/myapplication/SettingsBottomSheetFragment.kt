package com.example.myapplication

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.example.myapplication.databinding.FragmentSettingsBottomSheetBinding
import com.example.myapplication.databinding.ItemSettingCardBinding
import com.example.myapplication.core.setDebouncedClickListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheetFragment : BottomSheetDialogFragment() {

    enum class Action {
        APP_THEME,
        APP_GUIDE,
        HISTORY,
        DEVICE_CHECK,
        STORAGE_OPTIMIZER,
        SETUP_LOG,
        TFLITE_MODEL,
        MICROPHONE,
        VOICE_TARGET,
        STORAGE_LOCATION
    }

    var onActionSelected: ((Action) -> Unit)? = null

    private var _binding: FragmentSettingsBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setDebouncedClickListener { dismiss() }

        setupCard(binding.cardAppTheme, android.R.drawable.ic_menu_manage, "App Theme", Action.APP_THEME)
        setupCard(binding.cardAppGuide, android.R.drawable.ic_menu_info_details, "App Guide", Action.APP_GUIDE)
        setupCard(binding.cardHistory, android.R.drawable.ic_menu_recent_history, "History", Action.HISTORY)
        setupCard(binding.cardDeviceCheck, android.R.drawable.ic_menu_view, "Device Check", Action.DEVICE_CHECK)
        setupCard(binding.cardStorageOptimizer, android.R.drawable.ic_menu_sort_by_size, "Storage Optimizer", Action.STORAGE_OPTIMIZER)
        setupCard(binding.cardSetupLog, android.R.drawable.ic_menu_agenda, "Setup Log", Action.SETUP_LOG)
        setupCard(binding.cardTfliteModel, android.R.drawable.ic_menu_compass, "TFLite Model", Action.TFLITE_MODEL)
        setupCard(binding.cardMicrophone, R.drawable.ic_mic, "Microphone", Action.MICROPHONE)
        setupCard(binding.cardVoiceTarget, android.R.drawable.ic_menu_mylocation, "Voice Target", Action.VOICE_TARGET)
        setupCard(binding.cardStorageLocation, android.R.drawable.ic_menu_save, "Storage Location", Action.STORAGE_LOCATION)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(bottomSheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupCard(card: ItemSettingCardBinding, iconRes: Int, title: String, action: Action) {
        card.settingIcon.setImageResource(iconRes)
        card.settingTitle.text = title
        card.root.setDebouncedClickListener {
            dismiss()
            onActionSelected?.invoke(action)
        }
    }
}
